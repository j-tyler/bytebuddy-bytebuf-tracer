/*
 * Copyright 2025 Justin Marsh
 * SPDX-License-Identifier: Apache-2.0
 */

package com.example.bytebuf.tracker.integration.testapp;

import com.example.bytebuf.api.metrics.MetricHandler;
import com.example.bytebuf.api.metrics.MetricSnapshot;
import com.example.bytebuf.api.metrics.MetricType;
import com.example.bytebuf.tracker.ByteBufFlowTracker;
import com.example.bytebuf.tracker.metrics.MetricHandlerRegistry;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Test app for metric handler integration testing.
 * Creates known leak patterns and verifies metric handler receives correct data.
 */
public class MetricHandlerTestApp {

    private static final List<MetricSnapshot> capturedSnapshots = new ArrayList<MetricSnapshot>();

    // Instance variables to keep leaked buffers alive (like LeakDetectionApp pattern)
    private ByteBuf leaked1;
    private ByteBuf leaked2;
    private ByteBuf leaked3;
    private ByteBuf leaked4;
    private ByteBuf leaked5;

    public static void main(String[] args) throws Exception {
        System.out.println("[MetricHandlerTestApp] Starting test");

        // Register test metric handler
        TestMetricHandler handler = new TestMetricHandler();
        MetricHandlerRegistry.register(handler);
        System.out.println("[MetricHandlerTestApp] Registered test handler");

        // Create app instance (keeps buffers alive as instance variables)
        MetricHandlerTestApp app = new MetricHandlerTestApp();
        app.runTest();
    }

    private void runTest() throws Exception {
        // Pattern 1: Direct buffer leak (3 times, same path)
        System.out.println("[MetricHandlerTestApp] Creating direct buffer leaks (3x)");
        leaked1 = leakDirectBuffer();
        leaked2 = leakDirectBuffer();
        leaked3 = leakDirectBuffer();

        // Pattern 2: Heap buffer leak (2 times, same path)
        System.out.println("[MetricHandlerTestApp] Creating heap buffer leaks (2x)");
        leaked4 = leakHeapBuffer();
        leaked5 = leakHeapBuffer();

        // Pattern 3: Clean release (should not appear in leaks)
        System.out.println("[MetricHandlerTestApp] Creating clean releases");
        cleanReleaseDirectBuffer();
        cleanReleaseHeapBuffer();

        // Finalize flows and mark leaks
        // This processes any GC'd buffers and marks leaf nodes with refCount > 0 as leaks
        System.out.println("[MetricHandlerTestApp] Finalizing flows to detect leaks");
        ByteBufFlowTracker.getInstance().onShutdown();

        // Wait for next metric push to pick up the leaks
        // (push interval is 1 second)
        System.out.println("[MetricHandlerTestApp] Waiting for metrics...");
        Thread.sleep(2000);  // Wait 2 seconds to ensure at least one push after finalize

        // Verify metrics using most recent snapshot
        System.out.println("[MetricHandlerTestApp] Verifying metrics");
        verifyMetrics();

        // Now safe to release leaked buffer references (after leak detection)
        System.out.println("[MetricHandlerTestApp] Releasing leaked buffer references");
        if (leaked1 != null) leaked1.release();
        if (leaked2 != null) leaked2.release();
        if (leaked3 != null) leaked3.release();
        if (leaked4 != null) leaked4.release();
        if (leaked5 != null) leaked5.release();

        System.out.println("[MetricHandlerTestApp] Test completed successfully");
    }

    private ByteBuf leakDirectBuffer() {
        ByteBuf buf = Unpooled.directBuffer(256);
        buf.writeLong(12345L);
        buf.readLong();
        // Return to caller who stores in instance variable
        return buf;
    }

    private ByteBuf leakHeapBuffer() {
        ByteBuf buf = Unpooled.buffer(256);  // heap buffer
        buf.writeLong(67890L);
        buf.readLong();
        // Return to caller who stores in instance variable
        return buf;
    }

    private void cleanReleaseDirectBuffer() {
        ByteBuf buf = Unpooled.directBuffer(256);
        processDirectClean(buf);
        buf.release();
    }

    private void processDirectClean(ByteBuf buf) {
        buf.writeLong(11111L);
    }

    private void cleanReleaseHeapBuffer() {
        ByteBuf buf = Unpooled.buffer(256);  // heap buffer
        processHeapClean(buf);
        buf.release();
    }

    private void processHeapClean(ByteBuf buf) {
        buf.writeLong(22222L);
    }

    private static void verifyMetrics() {
        if (capturedSnapshots.isEmpty()) {
            System.err.println("[MetricHandlerTestApp] ERROR: No snapshots captured");
            System.exit(1);
        }

        // Use the most recent snapshot (should have leak data after GC)
        MetricSnapshot snapshot = capturedSnapshots.get(capturedSnapshots.size() - 1);

        // Verify direct leaks
        long totalDirectLeaks = snapshot.getTotalDirectLeaks();
        List<String> directLeakFlows = snapshot.getDirectLeakFlows();
        System.out.println("[MetricHandlerTestApp] Total direct leaks: " + totalDirectLeaks);
        System.out.println("[MetricHandlerTestApp] Direct leak flows: " + directLeakFlows.size());

        for (String flow : directLeakFlows) {
            System.out.println("[MetricHandlerTestApp] Direct leak flow: " + flow);

            // Verify leak path exists (just check it's not empty)
            if (flow == null || flow.isEmpty()) {
                System.err.println("[MetricHandlerTestApp] ERROR: Direct leak path is empty");
                System.exit(1);
            }

            // Verify leak_count is embedded in flow representation
            if (!flow.contains("|leak_count=")) {
                System.err.println("[MetricHandlerTestApp] ERROR: Flow missing leak_count field");
                System.exit(1);
            }
        }

        if (totalDirectLeaks != 3) {
            System.err.println("[MetricHandlerTestApp] ERROR: Expected 3 direct leaks, got " + totalDirectLeaks);
            System.exit(1);
        }
        System.out.println("[MetricHandlerTestApp] ✓ Direct leak count correct: 3");

        // Verify heap leaks
        long totalHeapLeaks = snapshot.getTotalHeapLeaks();
        List<String> heapLeakFlows = snapshot.getHeapLeakFlows();
        System.out.println("[MetricHandlerTestApp] Total heap leaks: " + totalHeapLeaks);
        System.out.println("[MetricHandlerTestApp] Heap leak flows: " + heapLeakFlows.size());

        for (String flow : heapLeakFlows) {
            System.out.println("[MetricHandlerTestApp] Heap leak flow: " + flow);

            // Verify leak path exists (just check it's not empty)
            if (flow == null || flow.isEmpty()) {
                System.err.println("[MetricHandlerTestApp] ERROR: Heap leak path is empty");
                System.exit(1);
            }
        }

        if (totalHeapLeaks != 2) {
            System.err.println("[MetricHandlerTestApp] ERROR: Expected 2 heap leaks, got " + totalHeapLeaks);
            System.exit(1);
        }
        System.out.println("[MetricHandlerTestApp] ✓ Heap leak count correct: 2");

        System.out.println("[MetricHandlerTestApp] ✓ Leak counts embedded in flow representation");
    }

    /**
     * Test metric handler that captures snapshots for verification.
     */
    private static class TestMetricHandler implements MetricHandler {

        @Override
        public Set<MetricType> getRequiredMetrics() {
            return EnumSet.of(MetricType.DIRECT_LEAKS, MetricType.HEAP_LEAKS);
        }

        @Override
        public void onMetrics(MetricSnapshot snapshot) {
            System.out.println("[TestMetricHandler] Received metrics snapshot");
            System.out.println("[TestMetricHandler] Total direct leaks: " + snapshot.getTotalDirectLeaks());
            System.out.println("[TestMetricHandler] Total heap leaks: " + snapshot.getTotalHeapLeaks());
            System.out.println("[TestMetricHandler] Direct leak flows: " + snapshot.getDirectLeakFlows().size());
            System.out.println("[TestMetricHandler] Heap leak flows: " + snapshot.getHeapLeakFlows().size());

            capturedSnapshots.add(snapshot);
        }

        @Override
        public String getName() {
            return "TestMetricHandler";
        }
    }
}
