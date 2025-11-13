/*
 * Copyright 2025 Justin Marsh
 * SPDX-License-Identifier: Apache-2.0
 */

package com.example.bytebuf.api.metrics;

/**
 * Types of metrics that can be captured and pushed to handlers.
 * Handlers declare which metrics they need via {@link MetricHandler#getRequiredMetrics()}.
 * Only requested metrics are captured (memory/performance optimization).
 */
public enum MetricType {

    /**
     * Direct buffer leaks (critical - off-heap, never GC'd, will crash JVM).
     *
     * <p>Provides: List of {@link LeakRecord}, one per unique leak path, containing:
     * <ul>
     *   <li>LLM-optimized flow representation with embedded leak count</li>
     *   <li>Leak count (how many times this path leaked)</li>
     *   <li>Capture timestamp</li>
     * </ul>
     *
     * <p>Format: {@code root=Method|final_ref=N|leak_count=500|leak_rate=100.0%|path=A -> B -> C}
     *
     * <p>Memory cost: ~200 bytes per unique leak path (NOT per leak occurrence)
     *
     * <p>Use case:
     * <ul>
     *   <li>Alerting: Sum up leakCount across all paths for total leak count</li>
     *   <li>Prioritization: Sort by leakCount to fix most frequent leaks first</li>
     *   <li>Forensics: Each path shows exactly where to add .release()</li>
     * </ul>
     */
    DIRECT_LEAKS,

    /**
     * Heap buffer leaks (moderate severity - eventually GC'd, but wastes memory).
     *
     * <p>Provides: List of {@link LeakRecord}, one per unique leak path, containing:
     * <ul>
     *   <li>LLM-optimized flow representation with embedded leak count</li>
     *   <li>Leak count (how many times this path leaked)</li>
     *   <li>Capture timestamp</li>
     * </ul>
     *
     * <p>Format: {@code root=Method|final_ref=N|leak_count=100|leak_rate=25.0%|path=A -> B -> C}
     *
     * <p>Memory cost: ~200 bytes per unique leak path (NOT per leak occurrence)
     *
     * <p>Use case: Performance optimization, finding inefficient code paths,
     * detecting gradual memory leaks before they cause issues.
     */
    HEAP_LEAKS
}
