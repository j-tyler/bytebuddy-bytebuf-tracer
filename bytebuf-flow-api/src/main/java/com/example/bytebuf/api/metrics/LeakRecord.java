/*
 * Copyright 2025 Justin Marsh
 * SPDX-License-Identifier: Apache-2.0
 */

package com.example.bytebuf.api.metrics;

/**
 * Record of a leak path with occurrence count.
 * Immutable and memory-efficient.
 */
public final class LeakRecord {

    /**
     * LLM-optimized flow representation showing the complete path from allocation to leak.
     * Format: "root=MethodName|final_ref=N|leak_count=N|leak_rate=X.X%|path=Method1[ref=N] -> Method2[ref=N] -> ..."
     */
    public final String flowRepresentation;

    /**
     * Number of times this exact path leaked (how many objects followed this path and were GC'd without release).
     */
    public final long leakCount;

    /**
     * Timestamp of the most recent snapshot capture (when metrics were collected).
     * Epoch milliseconds.
     */
    public final long captureTimestampMs;

    public LeakRecord(String flowRepresentation, long leakCount, long captureTimestampMs) {
        this.flowRepresentation = flowRepresentation;
        this.leakCount = leakCount;
        this.captureTimestampMs = captureTimestampMs;
    }

    @Override
    public String toString() {
        return "LeakRecord{" +
                "flow='" + flowRepresentation + '\'' +
                ", leakCount=" + leakCount +
                ", captureTimestamp=" + captureTimestampMs +
                '}';
    }
}
