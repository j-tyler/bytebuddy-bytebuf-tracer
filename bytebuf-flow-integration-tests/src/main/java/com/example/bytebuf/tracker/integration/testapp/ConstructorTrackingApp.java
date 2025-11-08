package com.example.bytebuf.tracker.integration.testapp;

import com.example.bytebuf.tracker.ByteBufFlowTracker;
import com.example.bytebuf.tracker.view.TrieRenderer;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

/**
 * Test application for constructor tracking.
 * When run with trackConstructors=...Message, constructors should be tracked.
 * When run without it, constructors should NOT be tracked.
 */
public class ConstructorTrackingApp {

    public static void main(String[] args) {
        System.out.println("=== ConstructorTrackingApp Starting ===");

        ConstructorTrackingApp app = new ConstructorTrackingApp();
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

        System.out.println("\n=== ConstructorTrackingApp Complete ===");
    }

    public void run() {
        // Allocate ByteBuf
        ByteBuf buffer = allocate();

        // Pass to a method
        buffer = prepare(buffer);

        // Pass to constructor (may or may not be tracked depending on config)
        Message message = new Message(buffer);

        // Pass wrapped object to method
        process(message);

        // Extract and release
        ByteBuf extracted = message.getBuffer();
        extracted.release();
    }

    public ByteBuf allocate() {
        return Unpooled.buffer(256);
    }

    public ByteBuf prepare(ByteBuf buffer) {
        buffer.writeBytes("Constructor test".getBytes());
        return buffer;
    }

    public void process(Message message) {
        ByteBuf buffer = message.getBuffer();
        System.out.println("Processing message with " + buffer.readableBytes() + " bytes");
    }

    /**
     * Wrapper class that takes ByteBuf in constructor.
     * With trackConstructors config, the constructor should be tracked.
     */
    public static class Message {
        private final ByteBuf buffer;
        private final long timestamp;

        public Message(ByteBuf buffer) {
            this.buffer = buffer;
            this.timestamp = System.currentTimeMillis();
        }

        public ByteBuf getBuffer() {
            return buffer;
        }

        public long getTimestamp() {
            return timestamp;
        }
    }
}
