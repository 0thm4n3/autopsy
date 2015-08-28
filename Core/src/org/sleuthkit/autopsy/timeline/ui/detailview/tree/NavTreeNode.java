/*
 * Autopsy Forensic Browser
 *
 * Copyright 2014 Basis Technology Corp.
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
package org.sleuthkit.autopsy.timeline.ui.detailview.tree;

import javax.annotation.concurrent.Immutable;
import org.sleuthkit.autopsy.timeline.events.type.EventType;

/**
 * The data item for the nav tree. Represents a combination of type and
 * description, as well as the corresponding number of events
 */
@Immutable
public class NavTreeNode {

    final private EventType type;

    final private String Description;

    final private int count;

    public EventType getType() {
        return type;
    }

    public String getDescription() {
        return Description;
    }

    public int getCount() {
        return count;
    }

    public NavTreeNode(EventType type, String Description, int count) {
        this.type = type;
        this.Description = Description;
        this.count = count;
    }

}
