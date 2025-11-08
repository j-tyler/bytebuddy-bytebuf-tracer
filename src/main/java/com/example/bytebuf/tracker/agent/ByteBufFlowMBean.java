/*
 * Copyright 2025 Justin Marsh
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

package com.example.bytebuf.tracker.agent;

/**
 * JMX MBean interface for ByteBuf flow monitoring.
 * Follows JMX Standard MBean naming convention:
 * - Interface: ByteBufFlowMBean
 * - Implementation: ByteBufFlow
 */
public interface ByteBufFlowMBean {
    // View operations
    String getTreeView();
    String getLLMView();
    String getSummary();

    // Statistics
    int getRootCount();
    int getActiveFlowCount();

    // Export operations
    void exportToFile(String filepath, String format);

    // Control operations
    void reset();
}
