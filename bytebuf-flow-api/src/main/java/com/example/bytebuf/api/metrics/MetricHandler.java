/*
 * Copyright 2025 Justin Marsh
 * SPDX-License-Identifier: Apache-2.0
 */

package com.example.bytebuf.api.metrics;

import java.util.Set;

/**
 * Handler for receiving ByteBuf tracking metrics at a configurable rate.
 *
 * <p><b>Thread Safety:</b> The {@link #onMetrics(MetricSnapshot)} method will be called from a single
 * background thread. Handlers must be thread-safe if they maintain state.
 *
 * <p><b>Performance:</b> Handlers should process metrics quickly to avoid
 * blocking the metric push thread. If expensive processing is needed (network I/O,
 * disk writes), queue the work for a separate thread.
 *
 * <p><b>Registration:</b> Register via:
 * <ul>
 *   <li>Programmatic: {@code MetricHandlerRegistry.register(handler)}</li>
 *   <li>System property: {@code -Dmetric.handlers=com.example.MyHandler}</li>
 *   <li>ServiceLoader: {@code META-INF/services/com.example.bytebuf.api.metrics.MetricHandler}</li>
 * </ul>
 *
 * <p><b>Example Implementation:</b>
 * <pre>
 * public class LoggingHandler implements MetricHandler {
 *     private static final Logger log = LoggerFactory.getLogger("ByteBufMetrics");
 *
 *     public Set&lt;MetricType&gt; getRequiredMetrics() {
 *         return EnumSet.of(MetricType.DIRECT_LEAKS);
 *     }
 *
 *     public void onMetrics(MetricSnapshot snapshot) {
 *         List&lt;LeakRecord&gt; leaks = snapshot.getDirectLeaks();
 *         for (LeakRecord leak : leaks) {
 *             log.error("CRITICAL LEAK: " + leak.flowRepresentation);
 *         }
 *     }
 *
 *     public String getName() { return "Logging"; }
 * }
 * </pre>
 */
public interface MetricHandler {

    /**
     * Declare which metrics this handler needs.
     * Called once during registration (cached by registry).
     * Only requested metrics will be captured and included in snapshots.
     *
     * @return Set of required metric types (never null, may be empty)
     */
    Set<MetricType> getRequiredMetrics();

    /**
     * Receive metrics snapshot at configured rate (default: every 60 seconds).
     * Called from a single background thread (not the tracking hot path).
     *
     * <p><b>Contract:</b>
     * <ul>
     *   <li>Process quickly or queue for separate thread</li>
     *   <li>Exceptions are caught and logged (won't crash tracker)</li>
     *   <li>Snapshot contains only metrics from {@link #getRequiredMetrics()}</li>
     * </ul>
     *
     * @param snapshot Captured metrics (only contains requested types)
     */
    void onMetrics(MetricSnapshot snapshot);

    /**
     * Get handler name for logging/debugging.
     * @return Handler name (e.g., "Prometheus", "CloudWatch", "Logging")
     */
    String getName();
}
