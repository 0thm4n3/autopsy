/*
 * Autopsy Forensic Browser
 *
 * Copyright 2013-15 Basis Technology Corp.
 * Contact: carrier <at> sleuthkit <dot> org
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.sleuthkit.autopsy.timeline.events.db;

import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.stream.Collectors;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableMap;
import javax.annotation.concurrent.GuardedBy;
import javax.swing.JOptionPane;
import javax.swing.SwingWorker;
import org.apache.commons.lang3.StringUtils;
import org.joda.time.Interval;
import org.openide.util.NbBundle;
import org.sleuthkit.autopsy.casemodule.Case;
import org.sleuthkit.autopsy.casemodule.services.TagsManager;
import org.sleuthkit.autopsy.coreutils.Logger;
import org.sleuthkit.autopsy.timeline.ProgressWindow;
import org.sleuthkit.autopsy.timeline.events.AggregateEvent;
import org.sleuthkit.autopsy.timeline.events.FilteredEventsModel;
import org.sleuthkit.autopsy.timeline.events.TimeLineEvent;
import org.sleuthkit.autopsy.timeline.events.type.ArtifactEventType;
import org.sleuthkit.autopsy.timeline.events.type.EventType;
import org.sleuthkit.autopsy.timeline.events.type.FileSystemTypes;
import org.sleuthkit.autopsy.timeline.events.type.RootEventType;
import org.sleuthkit.autopsy.timeline.filters.RootFilter;
import org.sleuthkit.autopsy.timeline.zooming.ZoomParams;
import org.sleuthkit.datamodel.AbstractFile;
import org.sleuthkit.datamodel.BlackboardArtifact;
import org.sleuthkit.datamodel.SleuthkitCase;
import org.sleuthkit.datamodel.TskCoreException;
import org.sleuthkit.datamodel.TskData;

/**
 * Provides public API (over EventsDB) to access events. In theory this
 * insulates the rest of the timeline module form the details of the db
 * implementation. Since there are no other implementations of the database or
 * clients of this class, and no Java Interface defined yet, in practice this
 * just delegates everything to the eventDB
 *
 * Concurrency Policy:
 *
 * Since almost everything just delegates to the EventDB, which is internally
 * synchronized, we only have to worry about rebuildRepository() which we
 * synchronize on our intrinsic lock.
 *
 */
public class EventsRepository {

    private final static Logger LOGGER = Logger.getLogger(EventsRepository.class.getName());

    private final EventDB eventDB;

    @GuardedBy("this")
    private SwingWorker<Void, ProgressWindow.ProgressUpdate> dbPopulationWorker;

    private final LoadingCache<Object, Long> maxCache;

    private final LoadingCache<Object, Long> minCache;

    private final FilteredEventsModel modelInstance;

    private final LoadingCache<Long, TimeLineEvent> idToEventCache;
    private final LoadingCache<ZoomParams, Map<EventType, Long>> eventCountsCache;
    private final LoadingCache<ZoomParams, List<AggregateEvent>> aggregateEventsCache;

    private final ObservableMap<Long, String> datasourcesMap = FXCollections.observableHashMap();
    private final ObservableMap<Long, String> hashSetMap = FXCollections.observableHashMap();
    private final Case autoCase;

    public Case getAutoCase() {
        return autoCase;
    }

    synchronized public ObservableMap<Long, String> getDatasourcesMap() {
        return datasourcesMap;
    }

    synchronized public ObservableMap<Long, String> getHashSetMap() {
        return hashSetMap;
    }

    public Interval getBoundingEventsInterval(Interval timeRange, RootFilter filter) {
        return eventDB.getBoundingEventsInterval(timeRange, filter);
    }

    /**
     * @return a FilteredEvetns object with this repository as underlying source
     *         of events
     */
    public FilteredEventsModel getEventsModel() {
        return modelInstance;
    }

    public EventsRepository(Case autoCase, ReadOnlyObjectProperty<ZoomParams> currentStateProperty) {
        this.autoCase = autoCase;
        //TODO: we should check that case is open, or get passed a case object/directory -jm
        this.eventDB = EventDB.getEventDB(autoCase);
        populateFilterMaps(autoCase.getSleuthkitCase());
        idToEventCache = CacheBuilder.newBuilder()
                .maximumSize(5000L)
                .expireAfterAccess(10, TimeUnit.MINUTES)
                .build(CacheLoader.from(eventDB::getEventById));
        eventCountsCache = CacheBuilder.newBuilder()
                .maximumSize(1000L)
                .expireAfterAccess(10, TimeUnit.MINUTES)
                .build(CacheLoader.from(eventDB::countEventsByType));
        aggregateEventsCache = CacheBuilder.newBuilder()
                .maximumSize(1000L)
                .expireAfterAccess(10, TimeUnit.MINUTES
                ).build(CacheLoader.from(eventDB::getAggregatedEvents));
        maxCache = CacheBuilder.newBuilder().build(CacheLoader.from(eventDB::getMaxTime));
        minCache = CacheBuilder.newBuilder().build(CacheLoader.from(eventDB::getMinTime));
        this.modelInstance = new FilteredEventsModel(this, currentStateProperty);
    }

    /**
     * @return min time (in seconds from unix epoch)
     */
    public Long getMaxTime() {
        return maxCache.getUnchecked("max"); // NON-NLS
//        return eventDB.getMaxTime();
    }

    /**
     * @return max tie (in seconds from unix epoch)
     */
    public Long getMinTime() {
        return minCache.getUnchecked("min"); // NON-NLS
//        return eventDB.getMinTime();
    }

    public void recordLastArtifactID(long lastArtfID) {
        eventDB.recordLastArtifactID(lastArtfID);
    }

    public void recordWasIngestRunning(Boolean wasIngestRunning) {
        eventDB.recordWasIngestRunning(wasIngestRunning);
    }

    public void recordLastObjID(Long lastObjID) {
        eventDB.recordLastObjID(lastObjID);
    }

    public boolean getWasIngestRunning() {
        return eventDB.getWasIngestRunning();
    }

    public Long getLastObjID() {
        return eventDB.getLastObjID();
    }

    public long getLastArtfactID() {
        return eventDB.getLastArtfactID();
    }

    public TimeLineEvent getEventById(Long eventID) {
        return idToEventCache.getUnchecked(eventID);
    }

    synchronized public Set<TimeLineEvent> getEventsById(Collection<Long> eventIDs) {
        return eventIDs.stream()
                .map(idToEventCache::getUnchecked)
                .collect(Collectors.toSet());

    }

    synchronized public List<AggregateEvent> getAggregatedEvents(ZoomParams params) {
        return aggregateEventsCache.getUnchecked(params);
    }

    synchronized public Map<EventType, Long> countEvents(ZoomParams params) {
        return eventCountsCache.getUnchecked(params);
    }

    private void invalidateCaches() {
        minCache.invalidateAll();
        maxCache.invalidateAll();
        eventCountsCache.invalidateAll();
        aggregateEventsCache.invalidateAll();
        idToEventCache.invalidateAll();
    }

    public Set<Long> getEventIDs(Interval timeRange, RootFilter filter) {
        return eventDB.getEventIDs(timeRange, filter);
    }

    public Interval getSpanningInterval(Collection<Long> eventIDs) {
        return eventDB.getSpanningInterval(eventIDs);
    }

    synchronized public void rebuildRepository(Runnable r) {
        if (dbPopulationWorker != null) {
            dbPopulationWorker.cancel(true);

        }
        dbPopulationWorker = new DBPopulationWorker(r);
        dbPopulationWorker.execute();
    }

    public boolean hasDataSourceInfo() {
        return eventDB.hasNewColumns();
    }

    private class DBPopulationWorker extends SwingWorker<Void, ProgressWindow.ProgressUpdate> {

        private final ProgressWindow progressDialog;

        //TODO: can we avoid this with a state listener?  does it amount to the same thing?
        //post population operation to execute
        private final Runnable postPopulationOperation;
        private final SleuthkitCase skCase;
        private final TagsManager tagsManager;

        public DBPopulationWorker(Runnable postPopulationOperation) {
            progressDialog = new ProgressWindow(null, true, this);
            progressDialog.setVisible(true);

            skCase = autoCase.getSleuthkitCase();
            tagsManager = autoCase.getServices().getTagsManager();

            this.postPopulationOperation = postPopulationOperation;
        }

        @Override
        @NbBundle.Messages({"progressWindow.msg.populateMacEventsFiles=populating mac events for files:",
            "progressWindow.msg.reinit_db=(re)initializing events database",
            "progressWindow.msg.commitingDb=committing events db"})
        protected Void doInBackground() throws Exception {
            publish(new ProgressWindow.ProgressUpdate(0, -1, Bundle.progressWindow_msg_reinit_db(), ""));
            //reset database 
            //TODO: can we do more incremental updates? -jm
            eventDB.reInitializeDB();

            //grab ids of all files
            List<Long> files = skCase.findAllFileIdsWhere("name != '.' AND name != '..'");

            final int numFiles = files.size();

            publish(new ProgressWindow.ProgressUpdate(0, numFiles, Bundle.progressWindow_msg_populateMacEventsFiles(), ""));

            //insert file events into db
            int i = 1;
            EventDB.EventTransaction trans = eventDB.beginTransaction();
            for (final Long fID : files) {
                if (isCancelled()) {
                    break;
                } else {
                    try {
                        AbstractFile f = skCase.getAbstractFileById(fID);

                        if (f == null) {
                            LOGGER.log(Level.WARNING, "Failed to get data for file : {0}", fID); // NON-NLS
                        } else {
                            //TODO: This is broken for logical files? fix -jm
                            //TODO: logical files don't necessarily have valid timestamps, so ... -jm
                            final String uniquePath = f.getUniquePath();
                            final String parentPath = f.getParentPath();
                            long datasourceID = f.getDataSource().getId();
                            String datasourceName = StringUtils.substringBefore(StringUtils.stripStart(uniquePath, "/"), parentPath);
                            String rootFolder = StringUtils.substringBetween(parentPath, "/", "/");
                            String shortDesc = datasourceName + "/" + StringUtils.defaultIfBlank(rootFolder, "");
                            String medD = datasourceName + parentPath;
                            final TskData.FileKnown known = f.getKnown();
                            Set<String> hashSets = f.getHashSetNames();
                            boolean tagged = !tagsManager.getContentTagsByContent(f).isEmpty();

                            //insert it into the db if time is > 0  => time is legitimate (drops logical files)
                            if (f.getAtime() > 0) {
                                eventDB.insertEvent(f.getAtime(), FileSystemTypes.FILE_ACCESSED, datasourceID, fID, null, uniquePath, medD, shortDesc, known, hashSets, tagged, trans);
                            }
                            if (f.getMtime() > 0) {
                                eventDB.insertEvent(f.getMtime(), FileSystemTypes.FILE_MODIFIED, datasourceID, fID, null, uniquePath, medD, shortDesc, known, hashSets, tagged, trans);
                            }
                            if (f.getCtime() > 0) {
                                eventDB.insertEvent(f.getCtime(), FileSystemTypes.FILE_CHANGED, datasourceID, fID, null, uniquePath, medD, shortDesc, known, hashSets, tagged, trans);
                            }
                            if (f.getCrtime() > 0) {
                                eventDB.insertEvent(f.getCrtime(), FileSystemTypes.FILE_CREATED, datasourceID, fID, null, uniquePath, medD, shortDesc, known, hashSets, tagged, trans);
                            }

                            publish(new ProgressWindow.ProgressUpdate(i, numFiles,
                                    Bundle.progressWindow_msg_populateMacEventsFiles(), f.getName()));
                        }
                    } catch (TskCoreException tskCoreException) {
                        LOGGER.log(Level.WARNING, "failed to insert mac event for file : " + fID, tskCoreException); // NON-NLS
                    }
                }
                i++;
            }

            //insert artifact based events
            //TODO: use (not-yet existing api) to grab all artifacts with timestamps, rather than the hardcoded lists in EventType -jm
            for (EventType type : RootEventType.allTypes) {
                if (isCancelled()) {
                    break;
                }
                //skip file_system events, they are already handled above.
                if (type instanceof ArtifactEventType) {
                    populateEventType((ArtifactEventType) type, trans);
                }
            }

            publish(new ProgressWindow.ProgressUpdate(0, -1, Bundle.progressWindow_msg_commitingDb(), ""));

            if (isCancelled()) {
                eventDB.rollBackTransaction(trans);
            } else {
                eventDB.commitTransaction(trans, true);
            }

            populateFilterMaps(skCase);
            invalidateCaches();

            return null;
        }

        /**
         * handle intermediate 'results': just update progress dialog
         *
         * @param chunks
         */
        @Override
        protected void process(List<ProgressWindow.ProgressUpdate> chunks) {
            super.process(chunks);
            ProgressWindow.ProgressUpdate chunk = chunks.get(chunks.size() - 1);
            progressDialog.update(chunk);
        }

        @Override
        @NbBundle.Messages("msgdlg.problem.text=There was a problem populating the timeline."
                + "  Not all events may be present or accurate. See the log for details.")
        protected void done() {
            super.done();
            try {
                progressDialog.close();
                get();
            } catch (CancellationException ex) {
                LOGGER.log(Level.INFO, "Database population was cancelled by the user.  Not all events may be present or accurate. See the log for details.", ex); // NON-NLS
            } catch (InterruptedException | ExecutionException ex) {
                LOGGER.log(Level.WARNING, "Exception while populating database.", ex); // NON-NLS
                JOptionPane.showMessageDialog(null, Bundle.msgdlg_problem_text());
            } catch (Exception ex) {
                LOGGER.log(Level.WARNING, "Unexpected exception while populating database.", ex); // NON-NLS
                JOptionPane.showMessageDialog(null, Bundle.msgdlg_problem_text());
            }
            postPopulationOperation.run();  //execute post db population operation
        }

        /**
         * populate all the events of one subtype
         *
         * @param subType the subtype to populate
         * @param trans   the db transaction to use
         * @param skCase  a reference to the sleuthkit case
         */
        @NbBundle.Messages({"# {0} - event type ", "progressWindow.populatingXevents=populating {0} events"})
        private void populateEventType(final ArtifactEventType type, EventDB.EventTransaction trans) {
            try {
                //get all the blackboard artifacts corresponding to the given event sub_type
                final ArrayList<BlackboardArtifact> blackboardArtifacts = skCase.getBlackboardArtifacts(type.getArtifactType());
                final int numArtifacts = blackboardArtifacts.size();

                publish(new ProgressWindow.ProgressUpdate(0, numArtifacts,
                        Bundle.progressWindow_populatingXevents(type.toString()), ""));

                int i = 0;
                for (final BlackboardArtifact bbart : blackboardArtifacts) {
                    //for each artifact, extract the relevant information for the descriptions
                    ArtifactEventType.AttributeEventDescription eventDescription = ArtifactEventType.AttributeEventDescription.buildEventDescription(type, bbart);

                    if (eventDescription != null && eventDescription.getTime() > 0L) {  //insert it into the db if time is > 0  => time is legitimate
                        long datasourceID = skCase.getContentById(bbart.getObjectID()).getDataSource().getId();

                        AbstractFile f = skCase.getAbstractFileById(bbart.getObjectID());
                        Set<String> hashSets = f.getHashSetNames();
                        boolean tagged = tagsManager.getBlackboardArtifactTagsByArtifact(bbart).isEmpty() == false;

                        eventDB.insertEvent(eventDescription.getTime(), type, datasourceID, bbart.getObjectID(), bbart.getArtifactID(), eventDescription.getFullDescription(), eventDescription.getMedDescription(), eventDescription.getShortDescription(), null, hashSets, tagged, trans);
                    }

                    i++;
                    publish(new ProgressWindow.ProgressUpdate(i, numArtifacts,
                            Bundle.progressWindow_populatingXevents(type), ""));
                }
            } catch (TskCoreException ex) {
                LOGGER.log(Level.SEVERE, "There was a problem getting events with sub type = " + type.toString() + ".", ex); // NON-NLS
            }
        }
    }

    /**
     * use the given SleuthkitCase to look up the names for the datasources in
     * the events table.
     *
     * TODO: we could keep a table of id -> name in the eventdb but I am wary of
     * having too much redundant info.
     *
     * @param skCase
     */
    synchronized private void populateFilterMaps(SleuthkitCase skCase) {

        for (Map.Entry<Long, String> hashSet : eventDB.getHashSetNames().entrySet()) {
            hashSetMap.putIfAbsent(hashSet.getKey(), hashSet.getValue());
        }
        //because there is no way to remove a datasource we only add to this map.
        for (Long id : eventDB.getDataSourceIDs()) {
            try {
                datasourcesMap.putIfAbsent(id, skCase.getContentById(id).getDataSource().getName());
            } catch (TskCoreException ex) {
                LOGGER.log(Level.SEVERE, "Failed to get datasource by ID.", ex);
            }
        }
    }

    synchronized public Set<Long> markEventsTagged(long objID, Long artifactID, boolean tagged) {
        Set<Long> updatedEventIDs = eventDB.markEventsTagged(objID, artifactID, tagged);
        if (!updatedEventIDs.isEmpty()) {
            aggregateEventsCache.invalidateAll();
            idToEventCache.invalidateAll(updatedEventIDs);
        }
        return updatedEventIDs;
    }
}
