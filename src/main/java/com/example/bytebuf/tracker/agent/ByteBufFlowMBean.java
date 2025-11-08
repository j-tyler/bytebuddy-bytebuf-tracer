/*
 * Copyright 2025 Justin Marsh
 * SPDX-License-Identifier: Apache-2.0
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
