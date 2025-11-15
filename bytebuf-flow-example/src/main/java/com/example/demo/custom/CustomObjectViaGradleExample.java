/*
 * Copyright 2025 Justin Marsh
 * SPDX-License-Identifier: Apache-2.0
 */

package com.example.demo.custom;

import com.example.bytebuf.tracker.ByteBufFlowTracker;
import com.example.bytebuf.tracker.ObjectTrackerRegistry;
import com.example.bytebuf.tracker.view.TrieRenderer;

import java.sql.Connection;
import java.sql.DriverManager;

/**
 * Example showing how to track custom objects using Gradle configuration ONLY.
 *
 * This demonstrates the zero-code-change approach: the custom tracker is configured
 * entirely via Gradle system properties, requiring NO changes to application code.
 *
 * To run:
 *   ./gradlew runCustomObjectViaGradle
 *
 * The build.gradle configures the tracker via:
 *   -Dobject.tracker.handlers=com.example.demo.custom.DatabaseConnectionTracker
 *
 * This is the RECOMMENDED approach for real projects because:
 *   1. No code changes required
 *   2. Easy to enable/disable tracking
 *   3. Can be configured per environment (dev/staging/prod)
 *   4. Works with legacy code that can't be modified
 *
 * NOTE: Handlers must be registered at build/launch time via the system property.
 * Runtime registration is not supported because the agent analyzes methods at
 * instrumentation time to determine which advice to apply.
 */
public class CustomObjectViaGradleExample {

    public static void main(String[] args) throws Exception {
        System.out.println("=== Custom Object Tracking via Gradle Configuration ===\n");

        // NOTE: Handlers are registered automatically by the agent during premain()
        // from the -Dobject.tracker.handlers system property in build.gradle

        // Verify the handler was registered by Gradle
        String handlersConfig = System.getProperty("object.tracker.handlers");
        System.out.println("Handlers configured via Gradle: " + handlersConfig);
        System.out.println("Custom handlers registered: " +
            ObjectTrackerRegistry.getCustomHandlers().size());
        if (!ObjectTrackerRegistry.getCustomHandlers().isEmpty()) {
            ObjectTrackerRegistry.getCustomHandlers().forEach(h ->
                System.out.println("  - " + h.getClass().getName() + " tracking " + h.getObjectType())
            );
        }
        System.out.println();

        // Now use the application normally - tracking happens automatically
        CustomObjectViaGradleExample example = new CustomObjectViaGradleExample();

        // Create a mock database connection pool
        System.out.println("Creating database connections...\n");

        // Normal usage - connections are properly closed
        for (int i = 0; i < 3; i++) {
            example.properConnectionUsage();
        }

        // Leaked usage - connection is never closed
        System.out.println("Creating leaked connection...\n");
        example.leakedConnectionUsage();

        // Give tracker a moment
        Thread.sleep(100);

        // Analyze the tracking results
        System.out.println("\n=== Database Connection Flow Analysis ===\n");
        printFlowAnalysis();
    }

    /**
     * Proper connection usage - connection is closed
     */
    public void properConnectionUsage() throws Exception {
        // Use H2 in-memory database for this example
        Connection conn = DriverManager.getConnection("jdbc:h2:mem:test", "sa", "");
        try {
            executeQuery(conn);
            processResults(conn);
        } finally {
            conn.close(); // Properly closed
        }
    }

    /**
     * Leaked connection usage - connection is never closed
     */
    public void leakedConnectionUsage() throws Exception {
        Connection conn = DriverManager.getConnection("jdbc:h2:mem:test", "sa", "");
        executeQuery(conn);
        processResults(conn);
        // OOPS! Forgot to close the connection - this will show as a leak
    }

    /**
     * Execute query - intermediate method
     */
    private void executeQuery(Connection conn) throws Exception {
        // Simulate query execution
        conn.createStatement();
    }

    /**
     * Process results - another intermediate method
     */
    private void processResults(Connection conn) throws Exception {
        // Simulate result processing
        conn.getMetaData();
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

        System.out.println("Look for leaf nodes with metric=1 - those are connections that weren't closed!");
        System.out.println("The 'leakedConnectionUsage' path should show as a leak.");
        System.out.println();
        System.out.println("KEY INSIGHT: This tracking was configured ENTIRELY via Gradle!");
        System.out.println("No code changes were needed - just system property configuration.");
        System.out.println();
        System.out.println("ARCHITECTURE NOTE: Handlers must be registered at launch time via");
        System.out.println("-Dobject.tracker.handlers system property. Runtime registration is not");
        System.out.println("supported because the agent analyzes methods during instrumentation.");
    }
}
