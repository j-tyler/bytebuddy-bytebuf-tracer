/*
 * Copyright 2025 Justin Marsh
 * SPDX-License-Identifier: Apache-2.0
 */

package com.example.demo.custom;

import com.example.bytebuf.tracker.ByteBufFlowTracker;
import com.example.bytebuf.tracker.ObjectTrackerRegistry;
import com.example.bytebuf.tracker.view.TrieRenderer;

import java.io.File;
import java.io.RandomAccessFile;

/**
 * Example showing how to track custom objects (not ByteBuf).
 *
 * <p><b>IMPORTANT:</b> Custom handlers must be registered at launch time via the
 * {@code -Dobject.tracker.handlers} system property. Runtime registration is NO LONGER
 * SUPPORTED because the agent analyzes methods at instrumentation time to determine
 * which advice to apply.
 *
 * <p>This example demonstrates tracking RandomAccessFile objects to detect
 * file handle leaks using the recommended system property approach.
 *
 * <p>To run with file handle tracking:
 * <pre>
 *   mvn exec:java \
 *     -Dexec.mainClass="com.example.demo.custom.CustomObjectExample" \
 *     -Dobject.tracker.handlers="com.example.demo.custom.FileHandleTracker"
 * </pre>
 *
 * <p>Or use the Gradle task which configures this automatically:
 * <pre>
 *   ./gradlew runCustomObjectExample
 * </pre>
 *
 * <p><b>Why no runtime registration?</b> The agent must know about custom handlers
 * BEFORE instrumenting classes. At instrumentation time, it analyzes each method to
 * determine which advice to apply (optimized ByteBuf advice vs. generic advice). If
 * handlers could be registered at runtime, already-instrumented classes would have the
 * wrong advice applied.
 */
public class CustomObjectExample {

    public static void main(String[] args) throws Exception {
        System.out.println("=== Custom Object Tracking Example ===\n");

        // Verify handler was registered via system property
        String handlersConfig = System.getProperty("object.tracker.handlers");
        if (handlersConfig == null || handlersConfig.isEmpty()) {
            System.err.println("ERROR: No handlers configured!");
            System.err.println("Please run with: -Dobject.tracker.handlers=com.example.demo.custom.FileHandleTracker");
            System.err.println("Or use: ./gradlew runCustomObjectExample");
            System.exit(1);
        }

        System.out.println("Handlers configured: " + handlersConfig);
        System.out.println("Custom handlers registered: " +
            ObjectTrackerRegistry.getCustomHandlers().size());
        if (!ObjectTrackerRegistry.getCustomHandlers().isEmpty()) {
            ObjectTrackerRegistry.getCustomHandlers().forEach(h ->
                System.out.println("  - " + h.getClass().getName() + " tracking " + h.getObjectType())
            );
        }
        System.out.println();

        // Use your custom objects normally - tracking happens automatically
        CustomObjectExample example = new CustomObjectExample();

        // Normal usage - file is properly closed
        System.out.println("Creating properly managed file handles...");
        for (int i = 0; i < 3; i++) {
            example.properFileUsage();
        }

        // Leaked usage - file is never closed
        System.out.println("Creating leaked file handle...\n");
        example.leakedFileUsage();

        // Give tracker a moment
        Thread.sleep(100);

        // Analyze the tracking results
        System.out.println("\n=== File Handle Flow Analysis ===\n");
        printFlowAnalysis();
    }

    /**
     * Proper file usage - file is closed
     */
    public void properFileUsage() throws Exception {
        File tempFile = File.createTempFile("test", ".tmp");
        tempFile.deleteOnExit();

        RandomAccessFile file = new RandomAccessFile(tempFile, "rw");
        try {
            processFile(file);
            writeData(file);
        } finally {
            file.close(); // Properly closed
        }
    }

    /**
     * Leaked file usage - file is never closed
     */
    public void leakedFileUsage() throws Exception {
        File tempFile = File.createTempFile("leak", ".tmp");
        tempFile.deleteOnExit();

        RandomAccessFile file = new RandomAccessFile(tempFile, "rw");
        processFile(file);
        // OOPS! Forgot to close the file - this will show as a leak
    }

    /**
     * Process file - intermediate method
     */
    private void processFile(RandomAccessFile file) throws Exception {
        // Simulate some processing
        file.seek(0);
    }

    /**
     * Write data - another intermediate method
     */
    private void writeData(RandomAccessFile file) throws Exception {
        file.writeBytes("test data");
    }

    /**
     * Print flow analysis
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

        System.out.println("Look for leaf nodes with metric=1 - those are file handles that weren't closed!");
        System.out.println("The 'leakedFileUsage' path should show as a leak.");
        System.out.println();
        System.out.println("ARCHITECTURE NOTE: Handler was registered at launch time via");
        System.out.println("-Dobject.tracker.handlers system property. Runtime registration is not");
        System.out.println("supported because the agent analyzes methods during instrumentation.");
    }
}
