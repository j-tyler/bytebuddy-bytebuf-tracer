/*
 * Copyright 2025 Justin Marsh
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

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
