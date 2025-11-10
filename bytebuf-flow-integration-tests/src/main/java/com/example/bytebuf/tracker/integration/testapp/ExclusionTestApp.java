/*
 * Copyright 2025 Justin Marsh
 * SPDX-License-Identifier: Apache-2.0
 */

package com.example.bytebuf.tracker.integration.testapp;

import com.example.bytebuf.tracker.ByteBufFlowTracker;
import com.example.bytebuf.tracker.integration.testapp.excluded.ExcludedPackageHelper;
import com.example.bytebuf.tracker.view.TrieRenderer;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

/**
 * Test application for demonstrating class-level and package-level exclusions.
 *
 * This app contains:
 * - Included methods (should be tracked)
 * - A specific excluded class (OptionallyExcludedHelper - should NOT be tracked)
 * - An excluded package (excluded.* - should NOT be tracked)
 */
public class ExclusionTestApp {

    public static void main(String[] args) {
        System.out.println("=== ExclusionTestApp Starting ===");

        ExclusionTestApp app = new ExclusionTestApp();
        app.run();

        // Print tracking results
        ByteBufFlowTracker tracker = ByteBufFlowTracker.getInstance();
        TrieRenderer renderer = new TrieRenderer(tracker.getTrie());

        System.out.println("\n=== Flow Summary ===");
        System.out.println(renderer.renderSummary());

        System.out.println("\n=== Flow Tree ===");
        System.out.println(renderer.renderIndentedTree());

        System.out.println("\n=== Flat Paths ===");
        System.out.println(renderer.renderForLLM());

        System.out.println("\n=== ExclusionTestApp Complete ===");
    }

    public void run() {
        // Allocate a ByteBuf
        ByteBuf buffer = allocate();

        // Process through included method (should be tracked)
        buffer = includedProcess(buffer);

        // Process through excluded helper class (should NOT be tracked)
        buffer = OptionallyExcludedHelper.processInExcludedClass(buffer);

        // Process through excluded package helper (should NOT be tracked)
        buffer = ExcludedPackageHelper.processInExcludedPackage(buffer);

        // Process through another included method (should be tracked)
        buffer = anotherIncludedProcess(buffer);

        // Clean up
        cleanup(buffer);
    }

    public ByteBuf allocate() {
        ByteBuf buffer = Unpooled.buffer(256);
        buffer.writeBytes("Test data".getBytes());
        return buffer;
    }

    public ByteBuf includedProcess(ByteBuf buffer) {
        System.out.println("includedProcess: Processing buffer");
        return buffer;
    }

    public ByteBuf anotherIncludedProcess(ByteBuf buffer) {
        System.out.println("anotherIncludedProcess: Processing buffer");
        return buffer;
    }

    public void cleanup(ByteBuf buffer) {
        System.out.println("cleanup: Releasing buffer");
        buffer.release();
    }
}
