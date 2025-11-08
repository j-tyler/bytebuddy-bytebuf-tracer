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
 * Test application that uses both static and instance methods.
 * The agent should track both types of methods.
 */
public class StaticMethodApp {

    public static void main(String[] args) {
        System.out.println("=== StaticMethodApp Starting ===");

        StaticMethodApp app = new StaticMethodApp();
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

        System.out.println("\n=== StaticMethodApp Complete ===");
    }

    public void run() {
        // Flow using instance methods
        ByteBuf buffer1 = allocateInstance();
        buffer1 = processInstance(buffer1);
        releaseInstance(buffer1);

        // Flow using static methods
        ByteBuf buffer2 = allocateStatic();
        buffer2 = processStatic(buffer2);
        releaseStatic(buffer2);

        // Mixed flow: instance -> static -> instance
        ByteBuf buffer3 = allocateInstance();
        buffer3 = processStatic(buffer3);
        releaseInstance(buffer3);
    }

    // Instance methods
    public ByteBuf allocateInstance() {
        return Unpooled.buffer(128);
    }

    public ByteBuf processInstance(ByteBuf buffer) {
        buffer.writeBytes("Instance".getBytes());
        return buffer;
    }

    public void releaseInstance(ByteBuf buffer) {
        buffer.release();
    }

    // Static methods
    public static ByteBuf allocateStatic() {
        return Unpooled.buffer(128);
    }

    public static ByteBuf processStatic(ByteBuf buffer) {
        buffer.writeBytes("Static".getBytes());
        return buffer;
    }

    public static void releaseStatic(ByteBuf buffer) {
        buffer.release();
    }
}
