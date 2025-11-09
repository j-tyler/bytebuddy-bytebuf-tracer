/*
 * Copyright 2025 Justin Marsh
 * SPDX-License-Identifier: Apache-2.0
 */

package com.example.demo;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import com.example.bytebuf.tracker.ByteBufFlowTracker;
import com.example.bytebuf.tracker.view.TrieRenderer;

/**
 * Demonstrates continuous ByteBuf flow tracking with constructor tracking enabled.
 *
 * This example shows the difference between:
 * 1. Default behavior (constructors NOT tracked, flow breaks)
 * 2. Constructor tracking enabled (continuous flow maintained)
 *
 * To run with constructor tracking enabled:
 * mvn exec:java -Dexec.mainClass="com.example.demo.ConstructorTrackingExample" \
 *     -Dexec.args="-javaagent:bytebuf-flow-tracker-agent.jar=include=com.example.demo;trackConstructors=com.example.demo.ConstructorTrackingExample$TrackedMessage"
 *
 * Expected flow with constructor tracking:
 * allocate_return -> prepareForWrapping -> TrackedMessage.<init> -> TrackedMessage.<init>_return ->
 * processMessage -> validateMessage -> cleanup -> release
 *
 * Note: Methods returning ByteBuf show with '_return' suffix.
 * All steps visible in one continuous path!
 */
public class ConstructorTrackingExample {

    public static void main(String[] args) {
        System.out.println("=== Constructor Tracking Example ===\n");

        ConstructorTrackingExample example = new ConstructorTrackingExample();

        // Run the complete flow
        example.demonstrateContinuousFlow();

        // Print tracking results
        ByteBufFlowTracker tracker = ByteBufFlowTracker.getInstance();
        TrieRenderer renderer = new TrieRenderer(tracker.getTrie());

        System.out.println("\n=== ByteBuf Flow Summary ===");
        System.out.println(renderer.renderSummary());

        System.out.println("\n=== Flow Tree ===");
        System.out.println(renderer.renderIndentedTree());

        System.out.println("\n=== Flat Paths ===");
        System.out.println(renderer.renderForLLM());

        System.out.println("\n=== Analysis ===");
        String tree = renderer.renderIndentedTree();
        if (tree.contains("TrackedMessage") || tree.contains("<init>")) {
            System.out.println("✓ SUCCESS: Constructor is tracked!");
            System.out.println("✓ Continuous flow maintained from allocation to release");
        } else {
            System.out.println("✗ Constructor NOT tracked");
            System.out.println("Run with: trackConstructors=com.example.demo.ConstructorTrackingExample$TrackedMessage");
        }
    }

    /**
     * Demonstrates continuous flow tracking
     */
    public void demonstrateContinuousFlow() {
        // Step 1: Allocate ByteBuf
        ByteBuf buffer = Unpooled.buffer(256);
        buffer.writeBytes("Hello, Constructor Tracking!".getBytes());
        System.out.println("1. ByteBuf allocated");

        // Step 2: Prepare for wrapping
        prepareForWrapping(buffer);

        // Step 3: Wrap in TrackedMessage (constructor call)
        // With trackConstructors enabled, this will be automatically tracked
        TrackedMessage message = new TrackedMessage(buffer, "REQ-001");
        System.out.println("3. ByteBuf wrapped in TrackedMessage");

        // Step 4: Process the message
        processMessage(message);

        // Step 5: Validate the message
        validateMessage(message);

        // Step 6: Extract and cleanup
        ByteBuf extracted = message.getData();
        System.out.println("6. Extracting and releasing ByteBuf");
        extracted.release();
    }

    /**
     * Step 2: Prepare buffer before wrapping
     */
    public void prepareForWrapping(ByteBuf buffer) {
        System.out.println("2. Preparing buffer for wrapping");
        // This is tracked automatically (ByteBuf parameter)
    }

    /**
     * Step 4: Process the wrapped message
     * Without constructor tracking, this would be invisible
     */
    public void processMessage(TrackedMessage message) {
        System.out.println("4. Processing TrackedMessage: " + message.getMessageId());
        // The agent won't automatically track this because TrackedMessage is not a ByteBuf
        // BUT if we manually track the contained ByteBuf:
        ByteBuf buffer = message.getData();
        // Manual tracking for methods that receive wrapper objects
        if (buffer != null) {
            ByteBufFlowTracker.getInstance().recordMethodCall(
                buffer, "ConstructorTrackingExample", "processMessage", buffer.refCnt());
        }
    }

    /**
     * Step 5: Validate the message
     */
    public void validateMessage(TrackedMessage message) {
        System.out.println("5. Validating TrackedMessage");
        ByteBuf buffer = message.getData();
        if (buffer != null) {
            ByteBufFlowTracker.getInstance().recordMethodCall(
                buffer, "ConstructorTrackingExample", "validateMessage", buffer.refCnt());
        }
    }

    /**
     * Wrapper class for ByteBuf with metadata
     *
     * When constructor tracking is enabled via:
     *   trackConstructors=com.example.demo.ConstructorTrackingExample$TrackedMessage
     *
     * The constructor will be automatically instrumented and track ByteBuf parameters.
     */
    public static class TrackedMessage {
        private final ByteBuf data;
        private final String messageId;
        private final long timestamp;

        /**
         * Constructor that receives ByteBuf.
         *
         * WITHOUT constructor tracking:
         *   - This call is invisible to the tracker
         *   - Flow appears broken
         *
         * WITH constructor tracking:
         *   - Agent automatically tracks ByteBuf parameter
         *   - Appears as TrackedMessage.<init> (entry) and TrackedMessage.<init>_return (exit) in flow tree
         *   - Flow remains continuous
         */
        public TrackedMessage(ByteBuf data, String messageId) {
            this.data = data;
            this.messageId = messageId;
            this.timestamp = System.currentTimeMillis();

            // Note: No manual tracking needed when agent is configured properly!
            // The agent will automatically detect and track the ByteBuf parameter.
        }

        public ByteBuf getData() {
            return data;
        }

        public String getMessageId() {
            return messageId;
        }

        public long getTimestamp() {
            return timestamp;
        }
    }

    /**
     * Another example: HTTP Request wrapper
     */
    public static class HttpRequest {
        private final String method;
        private final String path;
        private final ByteBuf body;

        /**
         * With trackConstructors=com.example.demo.ConstructorTrackingExample$HttpRequest
         * this constructor would be automatically tracked.
         */
        public HttpRequest(String method, String path, ByteBuf body) {
            this.method = method;
            this.path = path;
            this.body = body;
        }

        public ByteBuf getBody() {
            return body;
        }

        public String getMethod() {
            return method;
        }

        public String getPath() {
            return path;
        }
    }

    /**
     * Example showing multiple wrappers
     */
    public void demonstrateMultipleWrappers() {
        ByteBuf buffer = Unpooled.buffer(256);
        buffer.writeBytes("Request body".getBytes());

        // First wrapper
        TrackedMessage message = new TrackedMessage(buffer, "MSG-001");

        // Second wrapper
        HttpRequest request = new HttpRequest("POST", "/api/data", buffer);

        // Both constructors would be tracked if configured:
        // trackConstructors=com.example.demo.ConstructorTrackingExample$TrackedMessage,com.example.demo.ConstructorTrackingExample$HttpRequest

        buffer.release();
    }

    /**
     * Example with wildcard pattern
     * trackConstructors=com.example.demo.ConstructorTrackingExample$*
     * Would track ALL inner classes
     */
}
