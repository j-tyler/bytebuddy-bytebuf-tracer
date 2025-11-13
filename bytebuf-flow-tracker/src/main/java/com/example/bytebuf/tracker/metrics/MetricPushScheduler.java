/*
 * Copyright 2025 Justin Marsh
 * SPDX-License-Identifier: Apache-2.0
 */

package com.example.bytebuf.tracker.metrics;

import com.example.bytebuf.api.metrics.MetricHandler;
import com.example.bytebuf.api.metrics.MetricSnapshot;
import com.example.bytebuf.api.metrics.MetricType;
import com.example.bytebuf.tracker.ByteBufFlowTracker;

import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Background thread that pushes metrics to handlers at a configurable rate.
 * Single thread ensures handlers can't impact tracker performance.
 */
public class MetricPushScheduler {

    private static final String THREAD_NAME = "ByteBuf-Metric-Push";

    // Configurable via system properties
    private static final long DEFAULT_PUSH_INTERVAL_SECONDS = 60;
    private static final long pushIntervalSeconds = Long.getLong(
        "bytebuf.metrics.pushInterval", DEFAULT_PUSH_INTERVAL_SECONDS);

    private final ScheduledExecutorService scheduler;
    private final MetricCollector collector;

    public MetricPushScheduler(ByteBufFlowTracker tracker) {
        this.collector = new MetricCollector(tracker);
        this.scheduler = Executors.newSingleThreadScheduledExecutor(new java.util.concurrent.ThreadFactory() {
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, THREAD_NAME);
                t.setDaemon(true);  // Don't prevent JVM shutdown
                return t;
            }
        });
    }

    /**
     * Start pushing metrics at configured rate.
     * Scheduler runs even if no handlers are registered (they can be added later).
     * The pushMetrics() method checks for handlers and skips if none exist.
     */
    public void start() {
        System.out.println("[MetricPushScheduler] Starting with interval: " +
                          pushIntervalSeconds + " seconds");

        scheduler.scheduleAtFixedRate(
            new Runnable() {
                public void run() {
                    pushMetrics();
                }
            },
            pushIntervalSeconds,  // Initial delay
            pushIntervalSeconds,  // Period
            TimeUnit.SECONDS
        );
    }

    /**
     * Stop pushing metrics and shutdown thread.
     */
    public void shutdown() {
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Push metrics to all handlers (called on background thread).
     */
    private void pushMetrics() {
        try {
            // Capture handlers list once to avoid TOCTOU (handlers being unregistered mid-push)
            java.util.List<MetricHandler> handlers = MetricHandlerRegistry.getHandlers();
            if (handlers.isEmpty()) {
                return;
            }

            Set<MetricType> requiredMetrics = MetricHandlerRegistry.getRequiredMetrics();
            if (requiredMetrics.isEmpty()) {
                return;
            }

            // Capture snapshot (drains event queue)
            MetricSnapshot snapshot = collector.captureSnapshot(requiredMetrics);

            // Push to all handlers using captured list
            for (MetricHandler handler : handlers) {
                try {
                    handler.onMetrics(snapshot);
                } catch (Exception e) {
                    System.err.println("[MetricPushScheduler] Handler failed: " +
                                      handler.getName());
                    e.printStackTrace();
                }
            }
        } catch (Exception e) {
            System.err.println("[MetricPushScheduler] Unexpected error in push cycle");
            e.printStackTrace();
        }
    }
}
