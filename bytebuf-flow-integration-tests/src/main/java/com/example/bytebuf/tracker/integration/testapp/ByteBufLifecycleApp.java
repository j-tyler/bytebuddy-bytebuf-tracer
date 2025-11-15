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
 * Test application for ByteBuf lifecycle methods (retain, release, retainedDuplicate).
 * Used to verify that these methods are instrumented exactly once (not double-instrumented).
 */
public class ByteBufLifecycleApp {

    public static void main(String[] args) {
        System.out.println("=== ByteBufLifecycleApp Starting ===");

        ByteBufLifecycleApp app = new ByteBufLifecycleApp();
        app.testRetain();
        app.testRelease();
        app.testRetainedDuplicate();

        // Print tracking results
        ByteBufFlowTracker tracker = ByteBufFlowTracker.getInstance();
        tracker.onShutdown();
        TrieRenderer renderer = new TrieRenderer(tracker.getTrie());

        System.out.println("\n=== Flow Tree ===");
        System.out.println(renderer.renderIndentedTree());

        System.out.println("\n=== ByteBufLifecycleApp Complete ===");
    }

    /**
     * Test retain() method - returns ByteBuf, so could potentially match both transformers.
     */
    public void testRetain() {
        System.out.println("\n--- Testing retain() ---");
        ByteBuf buf = Unpooled.buffer(256);
        System.out.println("Initial refCount: " + buf.refCnt());

        // Call retain() - this is the method we're testing for double instrumentation
        ByteBuf retained = buf.retain();
        System.out.println("After retain(), refCount: " + buf.refCnt());

        // Cleanup
        buf.release(2);
    }

    /**
     * Test release() method - returns boolean, so should only match ByteBufLifecycleTransformer.
     */
    public void testRelease() {
        System.out.println("\n--- Testing release() ---");
        ByteBuf buf = Unpooled.buffer(256);
        System.out.println("Before release(), refCount: " + buf.refCnt());

        // Call release() - should appear exactly once in flow
        boolean released = buf.release();
        System.out.println("release() returned: " + released + ", refCount: " + buf.refCnt());
    }

    /**
     * Test retainedDuplicate() method - returns ByteBuf, similar to retain().
     */
    public void testRetainedDuplicate() {
        System.out.println("\n--- Testing retainedDuplicate() ---");
        ByteBuf buf = Unpooled.buffer(256);
        System.out.println("Initial refCount: " + buf.refCnt());

        // Call retainedDuplicate() - this is the method we're testing for double instrumentation
        ByteBuf duplicated = buf.retainedDuplicate();
        System.out.println("After retainedDuplicate(), refCount: " + buf.refCnt());

        // Cleanup
        buf.release();
        duplicated.release();
    }
}
