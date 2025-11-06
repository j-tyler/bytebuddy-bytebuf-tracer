package com.example.demo.custom;

import com.example.bytebuf.tracker.ByteBufFlowTracker;
import com.example.bytebuf.tracker.ObjectTrackerRegistry;
import com.example.bytebuf.tracker.view.TrieRenderer;

import java.io.File;
import java.io.RandomAccessFile;

/**
 * Example showing how to track custom objects (not ByteBuf).
 *
 * This demonstrates tracking RandomAccessFile objects to detect
 * file handle leaks.
 *
 * To run with file handle tracking:
 *   mvn exec:java -Dexec.mainClass="com.example.demo.custom.CustomObjectExample"
 */
public class CustomObjectExample {

    public static void main(String[] args) throws Exception {
        System.out.println("=== Custom Object Tracking Example ===\n");

        // STEP 1: Set the custom handler BEFORE any tracked objects are created
        ObjectTrackerRegistry.setHandler(new FileHandleTracker());

        // STEP 2: Use your custom objects normally
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

        // STEP 3: Analyze the tracking results
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
        System.out.println(renderer.renderFlatPaths());
        System.out.println();

        System.out.println("Look for leaf nodes with metric=1 - those are file handles that weren't closed!");
        System.out.println("The 'leakedFileUsage' path should show as a leak.");
    }
}
