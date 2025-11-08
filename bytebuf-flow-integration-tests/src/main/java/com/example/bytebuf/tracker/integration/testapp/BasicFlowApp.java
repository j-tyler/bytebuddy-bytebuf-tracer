package com.example.bytebuf.tracker.integration.testapp;

import com.example.bytebuf.tracker.ByteBufFlowTracker;
import com.example.bytebuf.tracker.view.TrieRenderer;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

/**
 * Simple test application that demonstrates basic ByteBuf flow tracking.
 * This app should be instrumented by the agent, and all public methods
 * with ByteBuf parameters/returns should be automatically tracked.
 */
public class BasicFlowApp {

    public static void main(String[] args) {
        System.out.println("=== BasicFlowApp Starting ===");

        BasicFlowApp app = new BasicFlowApp();
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

        System.out.println("\n=== BasicFlowApp Complete ===");
    }

    public void run() {
        // Allocate a ByteBuf
        ByteBuf buffer = allocate();

        // Process it through multiple methods
        buffer = processStep1(buffer);
        buffer = processStep2(buffer);
        buffer = processStep3(buffer);

        // Clean up
        cleanup(buffer);
    }

    public ByteBuf allocate() {
        ByteBuf buffer = Unpooled.buffer(256);
        buffer.writeBytes("Hello, World!".getBytes());
        return buffer;
    }

    public ByteBuf processStep1(ByteBuf buffer) {
        // Simulate some processing
        int readableBytes = buffer.readableBytes();
        System.out.println("Step 1: Processing " + readableBytes + " bytes");
        return buffer;
    }

    public ByteBuf processStep2(ByteBuf buffer) {
        // Simulate more processing
        byte[] data = new byte[buffer.readableBytes()];
        buffer.getBytes(0, data);
        System.out.println("Step 2: Read " + data.length + " bytes");
        return buffer;
    }

    public ByteBuf processStep3(ByteBuf buffer) {
        // Final processing
        System.out.println("Step 3: Final processing");
        return buffer;
    }

    public void cleanup(ByteBuf buffer) {
        System.out.println("Cleanup: Releasing buffer");
        buffer.release();
    }
}
