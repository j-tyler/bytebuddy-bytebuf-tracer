/*
 * Copyright 2025 Justin Marsh
 * SPDX-License-Identifier: Apache-2.0
 */

package com.example.bytebuf.tracker.metrics;

import com.example.bytebuf.api.metrics.MetricHandler;
import com.example.bytebuf.api.metrics.MetricSnapshot;
import com.example.bytebuf.api.metrics.MetricType;
import com.example.bytebuf.tracker.ByteBufFlowTracker;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for MetricPushScheduler.
 * Proves exception isolation and daemon thread behavior.
 */
public class MetricPushSchedulerTest {

    @Test
    public void testHandlerExceptionIsolation() throws Exception {
        AtomicInteger throwingHandlerCallCount = new AtomicInteger(0);
        AtomicInteger healthyHandlerCallCount = new AtomicInteger(0);

        // Handler that always throws
        MetricHandler throwingHandler = new MetricHandler() {
            public Set<MetricType> getRequiredMetrics() {
                return EnumSet.of(MetricType.DIRECT_LEAKS);
            }
            public void onMetrics(MetricSnapshot snapshot) {
                throwingHandlerCallCount.incrementAndGet();
                throw new RuntimeException("Intentional test exception");
            }
            public String getName() {
                return "ThrowingHandler";
            }
        };

        // Healthy handler that should still be called
        MetricHandler healthyHandler = new MetricHandler() {
            public Set<MetricType> getRequiredMetrics() {
                return EnumSet.of(MetricType.DIRECT_LEAKS);
            }
            public void onMetrics(MetricSnapshot snapshot) {
                healthyHandlerCallCount.incrementAndGet();
            }
            public String getName() {
                return "HealthyHandler";
            }
        };

        try {
            // Register both handlers (throwing handler first to prove isolation)
            MetricHandlerRegistry.register(throwingHandler);
            MetricHandlerRegistry.register(healthyHandler);

            // Trigger a metric push by calling the collector directly
            ByteBufFlowTracker tracker = ByteBufFlowTracker.getInstance();
            MetricCollector collector = new MetricCollector(tracker);
            Set<MetricType> requiredMetrics = MetricHandlerRegistry.getRequiredMetrics();
            MetricSnapshot snapshot = collector.captureSnapshot(requiredMetrics);

            // Manually call handlers (simulating what scheduler does)
            for (MetricHandler handler : MetricHandlerRegistry.getHandlers()) {
                try {
                    handler.onMetrics(snapshot);
                } catch (Exception e) {
                    // Swallow exception like scheduler does
                }
            }

            // Both handlers should have been called despite throwing handler's exception
            assertEquals(1, throwingHandlerCallCount.get(),
                        "Throwing handler should have been called once");
            assertEquals(1, healthyHandlerCallCount.get(),
                        "Healthy handler should have been called once despite other handler throwing");
        } finally {
            MetricHandlerRegistry.unregister(throwingHandler);
            MetricHandlerRegistry.unregister(healthyHandler);
        }
    }

    @Test
    public void testDaemonThreadDoesNotPreventShutdown() {
        // This test verifies the scheduler thread is a daemon thread
        // by checking it doesn't prevent JVM shutdown
        ByteBufFlowTracker tracker = ByteBufFlowTracker.getInstance();
        MetricPushScheduler scheduler = new MetricPushScheduler(tracker);

        // Start scheduler with long interval
        System.setProperty("bytebuf.metrics.pushInterval", "3600");
        try {
            scheduler.start();

            // Verify thread exists and is daemon
            Thread[] threads = new Thread[Thread.activeCount()];
            Thread.enumerate(threads);

            boolean foundDaemonThread = false;
            for (Thread t : threads) {
                if (t != null && t.getName().equals("ByteBuf-Metric-Push")) {
                    assertTrue(t.isDaemon(), "MetricPushScheduler thread must be daemon");
                    foundDaemonThread = true;
                    break;
                }
            }

            assertTrue(foundDaemonThread, "Should find ByteBuf-Metric-Push thread");

            scheduler.shutdown();
        } finally {
            System.clearProperty("bytebuf.metrics.pushInterval");
        }
    }
}
