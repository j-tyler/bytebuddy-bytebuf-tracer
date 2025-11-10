/*
 * Copyright 2025 Justin Marsh
 * SPDX-License-Identifier: Apache-2.0
 */

package com.example.bytebuf.tracker.integration.testapp;

import com.example.bytebuf.tracker.ByteBufFlowTracker;
import com.example.bytebuf.tracker.view.TrieRenderer;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

/**
 * Test application for demonstrating inner class exclusions.
 * Used to verify that inner classes (with $ separator) can be excluded.
 */
public class InnerClassTestApp {

    public static void main(String[] args) {
        System.out.println("=== InnerClassTestApp Starting ===");

        InnerClassTestApp app = new InnerClassTestApp();
        app.run();

        // Print tracking results
        ByteBufFlowTracker tracker = ByteBufFlowTracker.getInstance();
        tracker.onShutdown();  // Finalize flows and mark leaks
        TrieRenderer renderer = new TrieRenderer(tracker.getTrie());

        System.out.println("\n=== Flow Summary ===");
        System.out.println(renderer.renderSummary());

        System.out.println("\n=== Flow Tree ===");
        System.out.println(renderer.renderIndentedTree());

        System.out.println("\n=== Flat Paths ===");
        System.out.println(renderer.renderForLLM());

        System.out.println("\n=== InnerClassTestApp Complete ===");
    }

    public void run() {
        // Allocate a ByteBuf
        ByteBuf buffer = allocate();

        // Process through outer class method (should be tracked)
        buffer = outerProcess(buffer);

        // Process through inner class helper (might be excluded)
        buffer = InnerHelper.processInInnerClass(buffer);

        // Process through another outer method (should be tracked)
        buffer = anotherOuterProcess(buffer);

        // Clean up
        cleanup(buffer);
    }

    public ByteBuf allocate() {
        ByteBuf buffer = Unpooled.buffer(256);
        buffer.writeBytes("Inner class test data".getBytes());
        return buffer;
    }

    public ByteBuf outerProcess(ByteBuf buffer) {
        System.out.println("outerProcess: Processing buffer");
        return buffer;
    }

    public ByteBuf anotherOuterProcess(ByteBuf buffer) {
        System.out.println("anotherOuterProcess: Processing buffer");
        return buffer;
    }

    public void cleanup(ByteBuf buffer) {
        System.out.println("cleanup: Releasing buffer");
        buffer.release();
    }

    /**
     * Inner class that can be excluded using class:OuterClass$InnerClass syntax.
     */
    public static class InnerHelper {
        public static ByteBuf processInInnerClass(ByteBuf buffer) {
            System.out.println("InnerHelper.processInInnerClass: This should NOT be tracked when inner class is excluded");
            byte[] data = new byte[buffer.readableBytes()];
            buffer.getBytes(0, data);
            return buffer;
        }
    }
}
