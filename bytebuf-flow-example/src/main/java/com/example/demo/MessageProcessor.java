package com.example.demo;

import io.netty.buffer.ByteBuf;

/**
 * Example message processor that handles ByteBufs.
 * This class will be instrumented by the ByteBuddy agent.
 */
public class MessageProcessor {

    /**
     * Process a message buffer
     */
    public void process(ByteBuf message) {
        // Validate the message
        validate(message);

        // Parse the content
        String content = parseContent(message);

        // Store or forward
        store(message, content);
    }

    /**
     * Process with potential error
     */
    public void processWithPotentialError(ByteBuf message) {
        validate(message);

        // Simulate potential error
        if (message.readableBytes() < 10) {
            throw new RuntimeException("Message too short");
        }

        String content = parseContent(message);
        store(message, content);
    }

    /**
     * Validate message
     */
    private void validate(ByteBuf message) {
        if (message.readableBytes() == 0) {
            throw new IllegalArgumentException("Empty message");
        }
    }

    /**
     * Parse message content
     */
    private String parseContent(ByteBuf message) {
        byte[] bytes = new byte[message.readableBytes()];
        message.getBytes(message.readerIndex(), bytes);
        return new String(bytes);
    }

    /**
     * Store the message (simulate)
     */
    private void store(ByteBuf message, String content) {
        // In real application, this would store to database, forward to another service, etc.
        // For demo, we just track that it happened
    }
}
