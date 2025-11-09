/*
 * Copyright 2025 Justin Marsh
 * SPDX-License-Identifier: Apache-2.0
 */

package com.example.demo;

import com.example.bytebuf.tracker.ByteBufFlowTracker;
import com.example.bytebuf.tracker.view.TrieRenderer;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.Unpooled;
import io.netty.buffer.UnpooledByteBufAllocator;

/**
 * Demo application showing ByteBuf Flow Tracker in action.
 *
 * This demonstrates how an external project would use the tracker.
 * Shows BOTH heap and direct buffer leaks with emoji differentiation:
 * - Heap buffer leaks: ⚠️ LEAK (moderate - will GC)
 * - Direct buffer leaks: 🚨 LEAK (critical - never GC'd!)
 *
 * Run with:
 *   mvn exec:java
 *
 * Or manually:
 *   java -javaagent:../bytebuf-flow-tracker/target/bytebuf-flow-tracker-*-agent.jar=include=com.example.demo \
 *        -cp target/bytebuf-flow-example-*.jar \
 *        com.example.demo.DemoApplication
 */
public class DemoApplication {

    private final ByteBufAllocator allocator = UnpooledByteBufAllocator.DEFAULT;

    public static void main(String[] args) throws InterruptedException {
        System.out.println("=== ByteBuf Flow Tracker Demo ===");
        System.out.println("=== Demonstrating Direct vs Heap Buffer Leak Detection ===\n");

        DemoApplication app = new DemoApplication();

        // Simulate various ByteBuf usage patterns
        System.out.println("Running ByteBuf operations...\n");

        // Normal flow - properly released
        for (int i = 0; i < 5; i++) {
            app.handleNormalRequest();
        }

        // Flow with error handling - also properly released
        for (int i = 0; i < 3; i++) {
            app.handleRequestWithError();
        }

        // CRITICAL: Direct buffer leak (🚨)
        System.out.println("Creating CRITICAL direct buffer leak...\n");
        app.createDirectBufferLeak();

        // MODERATE: Heap buffer leak (⚠️)
        System.out.println("Creating moderate heap buffer leak...\n");
        app.createHeapBufferLeak();

        // Give the agent a moment to finish tracking
        Thread.sleep(100);

        // Print the flow analysis
        System.out.println("\n=== Flow Analysis ===\n");
        printFlowAnalysis();
    }

    /**
     * Normal request handling - ByteBuf is properly released
     */
    public void handleNormalRequest() {
        ByteBuf request = Unpooled.buffer(256);
        request.writeBytes("Normal request data".getBytes());

        try {
            MessageProcessor processor = new MessageProcessor();
            processor.process(request);
        } finally {
            request.release();
        }
    }

    /**
     * Request with error - ByteBuf is still properly released in finally block
     */
    public void handleRequestWithError() {
        ByteBuf request = Unpooled.buffer(256);
        request.writeBytes("Error request data".getBytes());

        try {
            MessageProcessor processor = new MessageProcessor();
            processor.processWithPotentialError(request);
        } catch (Exception e) {
            ErrorHandler errorHandler = new ErrorHandler();
            errorHandler.handleError(request, e);
        } finally {
            request.release();
        }
    }

    /**
     * CRITICAL: Direct buffer leak - memory is NEVER garbage collected!
     * Shows 🚨 LEAK emoji to indicate critical severity
     */
    public void createDirectBufferLeak() {
        ByteBuf directBuffer = allocator.directBuffer(1024);
        directBuffer.writeBytes("CRITICAL: Direct memory leak - never GC'd!".getBytes());

        DirectLeakyService directLeaky = new DirectLeakyService();
        directLeaky.forgetsToReleaseDirect(directBuffer);

        // BUG: Direct buffer never released!
        // This is CRITICAL - direct memory is NOT garbage collected
        // The tracker will show this with 🚨 LEAK
    }

    /**
     * MODERATE: Heap buffer leak - will eventually be garbage collected
     * Shows ⚠️ LEAK emoji to indicate moderate severity (still a bug!)
     */
    public void createHeapBufferLeak() {
        ByteBuf heapBuffer = allocator.heapBuffer(256);
        heapBuffer.writeBytes("Moderate: Heap leak - will GC".getBytes());

        LeakyService leakyService = new LeakyService();
        leakyService.forgetsToRelease(heapBuffer);

        // BUG: Heap buffer never released!
        // This is moderate severity - heap memory will eventually GC
        // But still a bug that should be fixed!
        // The tracker will show this with ⚠️ LEAK
    }

    /**
     * Print flow analysis using the tracker
     */
    private static void printFlowAnalysis() {
        ByteBufFlowTracker tracker = ByteBufFlowTracker.getInstance();
        TrieRenderer renderer = new TrieRenderer(tracker.getTrie());

        // Summary statistics
        System.out.println(renderer.renderSummary());
        System.out.println();

        // Tree view showing all flows
        System.out.println("=== Flow Tree ===");
        System.out.println(renderer.renderIndentedTree());
        System.out.println();

        // Flat paths highlighting leaks
        System.out.println("=== Flat Paths (Leaks Highlighted) ===");
        System.out.println(renderer.renderForLLM());
        System.out.println();

        System.out.println("=== Leak Severity Guide ===");
        System.out.println("🚨 LEAK - CRITICAL: Direct buffer leak (memory never GC'd - fix IMMEDIATELY!)");
        System.out.println("⚠️ LEAK  - MODERATE: Heap buffer leak (will GC, but still a bug - fix soon!)");
        System.out.println();
        System.out.println("Look for:");
        System.out.println("- DirectLeakyService.forgetsToReleaseDirect -> 🚨 CRITICAL");
        System.out.println("- LeakyService.forgetsToRelease -> ⚠️ MODERATE");
    }
}
