package com.example.bytebuf.tracker.integration.testapp;

import com.example.bytebuf.tracker.ByteBufFlowTracker;
import com.example.bytebuf.tracker.view.TrieRenderer;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

/**
 * Test application that wraps ByteBuf in custom objects.
 * This tests whether tracking continues when ByteBuf is wrapped.
 */
public class WrapperObjectApp {

    public static void main(String[] args) {
        System.out.println("=== WrapperObjectApp Starting ===");

        WrapperObjectApp app = new WrapperObjectApp();
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

        System.out.println("\n=== WrapperObjectApp Complete ===");
    }

    public void run() {
        // Allocate ByteBuf
        ByteBuf buffer = allocate();

        // Wrap in custom object
        Envelope envelope = wrap(buffer);

        // Process the wrapper
        processEnvelope(envelope);

        // Validate the wrapper
        validateEnvelope(envelope);

        // Extract and release
        ByteBuf extracted = unwrap(envelope);
        extracted.release();
    }

    public ByteBuf allocate() {
        ByteBuf buffer = Unpooled.buffer(256);
        buffer.writeBytes("Wrapped data".getBytes());
        return buffer;
    }

    public Envelope wrap(ByteBuf buffer) {
        return new Envelope(buffer);
    }

    public void processEnvelope(Envelope envelope) {
        // The envelope doesn't expose ByteBuf in signature,
        // but internally it holds one
        System.out.println("Processing envelope");
    }

    public void validateEnvelope(Envelope envelope) {
        System.out.println("Validating envelope");
    }

    public ByteBuf unwrap(Envelope envelope) {
        return envelope.getPayload();
    }

    /**
     * Wrapper class that encapsulates a ByteBuf
     */
    public static class Envelope {
        private final ByteBuf payload;
        private final String id;

        public Envelope(ByteBuf payload) {
            this.payload = payload;
            this.id = "ENV-" + System.currentTimeMillis();
        }

        public ByteBuf getPayload() {
            return payload;
        }

        public String getId() {
            return id;
        }
    }
}
