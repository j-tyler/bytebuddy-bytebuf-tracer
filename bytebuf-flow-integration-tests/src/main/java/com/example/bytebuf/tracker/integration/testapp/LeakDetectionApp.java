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
 * Test application that intentionally creates a ByteBuf leak.
 * The agent should detect and flag this leak in the output.
 */
public class LeakDetectionApp {

    public static void main(String[] args) {
        System.out.println("=== LeakDetectionApp Starting ===");

        LeakDetectionApp app = new LeakDetectionApp();

        // Create a normal flow (no leak)
        app.normalFlow();

        // Create a leaky flow
        app.leakyFlow();

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

        System.out.println("\n=== LeakDetectionApp Complete ===");
    }

    public void normalFlow() {
        ByteBuf buffer = allocateNormal();
        buffer = processNormal(buffer);
        releaseNormal(buffer);
    }

    public void leakyFlow() {
        ByteBuf buffer = allocateLeaky();
        buffer = processLeaky(buffer);
        // Intentionally NOT releasing - this is a leak!
        forgetToRelease(buffer);
    }

    public ByteBuf allocateNormal() {
        return Unpooled.buffer(128);
    }

    public ByteBuf processNormal(ByteBuf buffer) {
        buffer.writeBytes("Normal data".getBytes());
        return buffer;
    }

    public void releaseNormal(ByteBuf buffer) {
        buffer.release();
    }

    public ByteBuf allocateLeaky() {
        return Unpooled.buffer(128);
    }

    public ByteBuf processLeaky(ByteBuf buffer) {
        buffer.writeBytes("Leaky data".getBytes());
        return buffer;
    }

    public void forgetToRelease(ByteBuf buffer) {
        // This method receives the buffer but doesn't release it
        // This should be flagged as a leak (refCount > 0 at leaf node)
        System.out.println("Oops, forgot to release! RefCount: " + buffer.refCnt());
    }
}
