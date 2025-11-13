/*
 * Copyright 2025 Justin Marsh
 * SPDX-License-Identifier: Apache-2.0
 */

package com.example.bytebuf.api.metrics;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Snapshot of captured metrics at a point in time.
 * Immutable and thread-safe.
 *
 * <p><b>API Design:</b> Single object with fixed fields allows adding new fields
 * in future versions without breaking existing handlers.
 *
 * <p><b>Memory Optimization:</b> Only contains metrics that were requested
 * via {@link MetricHandler#getRequiredMetrics()}. Unrequested metrics return 0/empty.
 */
public final class MetricSnapshot {

    private final long captureTimestampMs;
    private final long totalDirectLeaks;
    private final long totalHeapLeaks;
    private final List<String> directLeakFlows;
    private final List<String> heapLeakFlows;

    public MetricSnapshot(long captureTimestampMs,
                         long totalDirectLeaks,
                         long totalHeapLeaks,
                         List<String> directLeakFlows,
                         List<String> heapLeakFlows) {
        this.captureTimestampMs = captureTimestampMs;
        this.totalDirectLeaks = totalDirectLeaks;
        this.totalHeapLeaks = totalHeapLeaks;
        // Defensive copy prevents external mutation; private final field means no wrapper needed
        this.directLeakFlows = directLeakFlows != null && !directLeakFlows.isEmpty()
            ? new ArrayList<String>(directLeakFlows)
            : Collections.<String>emptyList();
        this.heapLeakFlows = heapLeakFlows != null && !heapLeakFlows.isEmpty()
            ? new ArrayList<String>(heapLeakFlows)
            : Collections.<String>emptyList();
    }

    /**
     * Get timestamp when this snapshot was captured.
     * @return Epoch milliseconds
     */
    public long getCaptureTimestamp() {
        return captureTimestampMs;
    }

    /**
     * Get total count of direct buffer leaks across all flows.
     * Direct leaks are CRITICAL - they are off-heap, never GC'd, and will crash the JVM.
     * @return Total number of direct buffer leaks, or 0 if not requested
     */
    public long getTotalDirectLeaks() {
        return totalDirectLeaks;
    }

    /**
     * Get total count of heap buffer leaks across all flows.
     * Heap leaks are moderate severity - they will eventually be GC'd but waste memory.
     * @return Total number of heap buffer leaks, or 0 if not requested
     */
    public long getTotalHeapLeaks() {
        return totalHeapLeaks;
    }

    /**
     * Get flow representations for direct buffer leaks.
     * Each string represents one unique leak path with embedded metadata.
     * Format: "root=Method|final_ref=N|leak_count=500|leak_rate=X.X%|path=A -> B -> C"
     *
     * <p><b>One flow per unique path:</b> If the same path leaked 500 times,
     * you get ONE string with leak_count=500 (not 500 separate strings).
     *
     * @return List of flow strings (requires {@link MetricType#DIRECT_LEAKS}), or empty if not requested
     */
    public List<String> getDirectLeakFlows() {
        return directLeakFlows;
    }

    /**
     * Get flow representations for heap buffer leaks.
     * Each string represents one unique leak path with embedded metadata.
     * Format: "root=Method|final_ref=N|leak_count=500|leak_rate=X.X%|path=A -> B -> C"
     *
     * <p><b>One flow per unique path:</b> If the same path leaked 500 times,
     * you get ONE string with leak_count=500 (not 500 separate strings).
     *
     * @return List of flow strings (requires {@link MetricType#HEAP_LEAKS}), or empty if not requested
     */
    public List<String> getHeapLeakFlows() {
        return heapLeakFlows;
    }

    @Override
    public String toString() {
        return "MetricSnapshot{" +
                "captureTimestamp=" + captureTimestampMs +
                ", totalDirectLeaks=" + totalDirectLeaks +
                ", totalHeapLeaks=" + totalHeapLeaks +
                ", directLeakFlows=" + directLeakFlows +
                ", heapLeakFlows=" + heapLeakFlows +
                '}';
    }
}
