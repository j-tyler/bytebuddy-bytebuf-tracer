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
import io.netty.buffer.Unpooled;
import com.example.bytebuf.tracker.ByteBufFlowTracker;
import com.example.bytebuf.tracker.view.TrieRenderer;

/**
 * Demonstrates ByteBuf flow tracking when ByteBuf is wrapped in custom objects.
 *
 * This example illustrates two key limitations:
 * 1. Constructors are not tracked (excluded by .and(not(isConstructor())))
 * 2. When ByteBuf is wrapped in a non-ByteBuf object, tracking breaks
 *
 * Scenario:
 * - ByteBuf allocated
 * - Passed to method A (tracked)
 * - Method A wraps ByteBuf in Message object via constructor (NOT tracked)
 * - Message object passed to method B (tracking BREAKS - Message is not a ByteBuf)
 * - Need manual tracking to maintain flow visibility
 */
public class WrappedObjectFlowExample {

    public static void main(String[] args) {
        System.out.println("=== Wrapped Object Flow Tracking Example ===\n");

        WrappedObjectFlowExample example = new WrappedObjectFlowExample();

        // Scenario 1: Automatic tracking (works partially)
        System.out.println("--- Scenario 1: Without Manual Tracking ---");
        example.scenarioWithoutManualTracking();

        // Reset tracker between scenarios
        ByteBufFlowTracker.getInstance().reset();

        // Scenario 2: Manual tracking (complete visibility)
        System.out.println("\n--- Scenario 2: With Manual Tracking ---");
        example.scenarioWithManualTracking();

        // Print results
        ByteBufFlowTracker tracker = ByteBufFlowTracker.getInstance();
        TrieRenderer renderer = new TrieRenderer(tracker.getTrie());

        System.out.println("\n=== ByteBuf Flow Summary ===");
        System.out.println(renderer.renderSummary());

        System.out.println("\n=== Flow Tree ===");
        System.out.println(renderer.renderIndentedTree());

        System.out.println("\n=== Analysis ===");
        System.out.println("The flow tree shows gaps where ByteBuf was wrapped in Message objects.");
        System.out.println("Methods receiving Message (not ByteBuf) are invisible without manual tracking.");
    }

    /**
     * Scenario without manual tracking - demonstrates where tracking breaks
     */
    public void scenarioWithoutManualTracking() {
        // Step 1: Allocate ByteBuf (tracked automatically by agent)
        ByteBuf buffer = Unpooled.buffer(256);
        buffer.writeBytes("Automatic tracking test".getBytes());

        // Step 2: Pass to method (tracked - ByteBuf is parameter)
        processBuffer(buffer);

        // Step 3: Wrap in Message object (constructor NOT tracked)
        Message message = new Message(buffer);

        // Step 4: Pass Message to method (NOT tracked - Message is not a ByteBuf)
        // Agent won't intercept this because shouldTrack(Message) returns false
        processMessage(message);

        // Step 5: Validate Message (NOT tracked)
        validateMessage(message);

        // Step 6: Extract and release (tracked when ByteBuf is parameter/return)
        ByteBuf extracted = message.getBuffer();
        extracted.release();

        System.out.println("Automatic tracking: Limited visibility into Message processing");
    }

    /**
     * Scenario with manual tracking - maintains continuous flow
     */
    public void scenarioWithManualTracking() {
        ByteBufFlowTracker tracker = ByteBufFlowTracker.getInstance();

        // Step 1: Allocate ByteBuf
        ByteBuf buffer = Unpooled.buffer(256);
        buffer.writeBytes("Manual tracking test".getBytes());

        // Step 2: Pass to method (tracked automatically)
        processBuffer(buffer);

        // Step 3: Wrap in Message with manual tracking in constructor
        MessageWithTracking message = new MessageWithTracking(buffer);

        // Step 4: Process Message with manual tracking
        processMessageWithTracking(message);

        // Step 5: Validate Message with manual tracking
        validateMessageWithTracking(message);

        // Step 6: Extract and release
        ByteBuf extracted = message.getBuffer();
        extracted.release();
        tracker.recordMethodCall(extracted, "WrappedObjectFlowExample", "release", extracted.refCnt());

        System.out.println("Manual tracking: Complete visibility maintained");
    }

    // ========== Methods for Scenario 1 (Automatic Tracking) ==========

    public void processBuffer(ByteBuf buffer) {
        // Agent intercepts this because ByteBuf is parameter
        System.out.println("Processing buffer: " + buffer.readableBytes() + " bytes");
    }

    public void processMessage(Message message) {
        // Agent DOES NOT intercept this - Message is not a ByteBuf
        System.out.println("Processing message (NOT tracked automatically)");
    }

    public void validateMessage(Message message) {
        // Agent DOES NOT intercept this
        System.out.println("Validating message (NOT tracked automatically)");
    }

    // ========== Methods for Scenario 2 (Manual Tracking) ==========

    public void processMessageWithTracking(MessageWithTracking message) {
        // Manually track the underlying ByteBuf
        ByteBuf buffer = message.getBuffer();
        ByteBufFlowTracker.getInstance().recordMethodCall(
            buffer, "WrappedObjectFlowExample", "processMessageWithTracking", buffer.refCnt());
        System.out.println("Processing message WITH tracking");
    }

    public void validateMessageWithTracking(MessageWithTracking message) {
        ByteBuf buffer = message.getBuffer();
        ByteBufFlowTracker.getInstance().recordMethodCall(
            buffer, "WrappedObjectFlowExample", "validateMessageWithTracking", buffer.refCnt());
        System.out.println("Validating message WITH tracking");
    }

    // ========== Helper Classes ==========

    /**
     * Simple wrapper class without tracking
     */
    public static class Message {
        private final ByteBuf buffer;
        private final String id;

        public Message(ByteBuf buffer) {
            this.buffer = buffer;
            this.id = "MSG-" + System.currentTimeMillis();
            // Constructor NOT tracked by agent
        }

        public ByteBuf getBuffer() {
            return buffer;
        }

        public String getId() {
            return id;
        }
    }

    /**
     * Wrapper class with manual tracking in constructor
     */
    public static class MessageWithTracking {
        private final ByteBuf buffer;
        private final String id;

        public MessageWithTracking(ByteBuf buffer) {
            this.buffer = buffer;
            this.id = "MSG-" + System.currentTimeMillis();

            // Manual tracking since constructors are excluded
            ByteBufFlowTracker.getInstance().recordMethodCall(
                buffer, "MessageWithTracking", "<init>", buffer.refCnt());
        }

        public ByteBuf getBuffer() {
            return buffer;
        }

        public String getId() {
            return id;
        }
    }

    /**
     * Real-world example: HTTP Request wrapper
     */
    public static class HttpRequest {
        private final ByteBuf body;
        private final String method;
        private final String path;

        public HttpRequest(String method, String path, ByteBuf body) {
            this.method = method;
            this.path = path;
            this.body = body;
            // Without manual tracking, this constructor call is invisible
        }

        public ByteBuf getBody() {
            return body;
        }

        public void process() {
            // If this method doesn't accept ByteBuf as parameter, it won't be tracked
            // Need to manually track the body ByteBuf
            ByteBufFlowTracker.getInstance().recordMethodCall(
                body, "HttpRequest", "process", body.refCnt());
        }
    }

    /**
     * Real-world example: Codec pattern
     */
    public static class MessageCodec {

        /**
         * Encode: ByteBuf -> Message
         * Constructor call is not tracked
         */
        public Message encode(ByteBuf buffer) {
            // This is tracked (ByteBuf parameter)
            return new Message(buffer);  // Constructor NOT tracked
        }

        /**
         * Decode: Message -> ByteBuf
         * Return value is tracked
         */
        public ByteBuf decode(Message message) {
            // This is NOT tracked (Message parameter, not ByteBuf)
            return message.getBuffer();  // Return value IS tracked
        }
    }
}
