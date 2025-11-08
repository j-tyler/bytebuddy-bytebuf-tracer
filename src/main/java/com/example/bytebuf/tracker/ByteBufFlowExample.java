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

package com.example.bytebuf.tracker;

import com.example.bytebuf.tracker.view.TrieRenderer;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

/**
 * Example demonstrating ByteBuf flow tracking using real Netty ByteBuf.
 *
 * This example shows:
 * 1. Direct ByteBuf allocation and passing through static methods
 * 2. ByteBuf wrapped in objects passed through methods
 * 3. Various reference count scenarios (proper release, leaks)
 * 4. Output of tracking data in multiple formats
 *
 * NOTE: In production, tracking happens automatically via ByteBuddy agent.
 * This example manually calls the tracker for demonstration purposes.
 */
public class ByteBufFlowExample {

    /**
     * Wrapper object that holds a ByteBuf
     */
    static class MessageWrapper {
        private final ByteBuf buffer;
        private final String metadata;

        public MessageWrapper(ByteBuf buffer, String metadata) {
            this.buffer = buffer;
            this.metadata = metadata;
        }

        public ByteBuf getBuffer() {
            return buffer;
        }

        public String getMetadata() {
            return metadata;
        }
    }

    // ========================================================================
    // Scenario 1: Direct ByteBuf passing with proper cleanup
    // ========================================================================

    static void directFlow_ProperCleanup() {
        System.out.println("\n=== Scenario 1: Direct Flow with Proper Cleanup ===");

        ByteBuf buf = Unpooled.buffer(256);
        ByteBufFlowTracker tracker = ByteBufFlowTracker.getInstance();

        // Root: allocate
        tracker.recordMethodCall(buf, "DirectExample", "allocate", buf.refCnt());

        // Pass to decoder
        decode(buf);

        // Pass to handler
        handle(buf);

        // Pass to processor
        process(buf);

        // Properly release
        buf.release();
        tracker.recordMethodCall(buf, "DirectExample", "cleanup", buf.refCnt());

        System.out.println("✓ Buffer properly released");
    }

    static void decode(ByteBuf buf) {
        ByteBufFlowTracker.getInstance().recordMethodCall(buf, "Decoder", "decode", buf.refCnt());
    }

    static void handle(ByteBuf buf) {
        ByteBufFlowTracker.getInstance().recordMethodCall(buf, "Handler", "handle", buf.refCnt());
    }

    static void process(ByteBuf buf) {
        ByteBufFlowTracker.getInstance().recordMethodCall(buf, "Processor", "process", buf.refCnt());
    }

    // ========================================================================
    // Scenario 2: Direct ByteBuf with LEAK (forgot to release)
    // ========================================================================

    static void directFlow_WithLeak() {
        System.out.println("\n=== Scenario 2: Direct Flow with LEAK ===");

        ByteBuf buf = Unpooled.buffer(256);
        ByteBufFlowTracker tracker = ByteBufFlowTracker.getInstance();

        // Root: allocate
        tracker.recordMethodCall(buf, "LeakyExample", "allocate", buf.refCnt());

        // Pass through methods
        errorDecode(buf);
        errorHandle(buf);
        logError(buf);

        // OOPS - forgot to release!
        // buf.release(); // <-- Missing!

        System.out.println("⚠️  Buffer NOT released - LEAK!");
    }

    static void errorDecode(ByteBuf buf) {
        ByteBufFlowTracker.getInstance().recordMethodCall(buf, "ErrorDecoder", "decode", buf.refCnt());
    }

    static void errorHandle(ByteBuf buf) {
        ByteBufFlowTracker.getInstance().recordMethodCall(buf, "ErrorHandler", "handleError", buf.refCnt());
    }

    static void logError(ByteBuf buf) {
        ByteBufFlowTracker.getInstance().recordMethodCall(buf, "Logger", "logError", buf.refCnt());
    }

    // ========================================================================
    // Scenario 3: ByteBuf with retain/release (refCount changes)
    // ========================================================================

    static void directFlow_WithRetain() {
        System.out.println("\n=== Scenario 3: Direct Flow with Retain/Release ===");

        ByteBuf buf = Unpooled.buffer(256);
        ByteBufFlowTracker tracker = ByteBufFlowTracker.getInstance();

        // Root: allocate
        tracker.recordMethodCall(buf, "RetainExample", "allocate", buf.refCnt());

        // Validator retains for async processing
        validateWithRetain(buf);

        // Continue processing with refCnt=2
        asyncProcess(buf);

        // Original flow releases
        buf.release();
        tracker.recordMethodCall(buf, "RetainExample", "firstRelease", buf.refCnt());

        // Async flow completes and releases
        buf.release();
        tracker.recordMethodCall(buf, "RetainExample", "secondRelease", buf.refCnt());

        System.out.println("✓ Buffer properly released after retain");
    }

    static void validateWithRetain(ByteBuf buf) {
        ByteBufFlowTracker tracker = ByteBufFlowTracker.getInstance();
        tracker.recordMethodCall(buf, "Validator", "validate", buf.refCnt());

        // Retain for async processing
        buf.retain();
        tracker.recordMethodCall(buf, "Validator", "afterRetain", buf.refCnt());
    }

    static void asyncProcess(ByteBuf buf) {
        ByteBufFlowTracker.getInstance().recordMethodCall(buf, "AsyncProcessor", "processAsync", buf.refCnt());
    }

    // ========================================================================
    // Scenario 4: Wrapper object passed around
    // ========================================================================

    static void wrapperFlow_ProperCleanup() {
        System.out.println("\n=== Scenario 4: Wrapper Object with Proper Cleanup ===");

        ByteBuf buf = Unpooled.buffer(256);
        MessageWrapper wrapper = new MessageWrapper(buf, "important-message");
        ByteBufFlowTracker tracker = ByteBufFlowTracker.getInstance();

        // Root: receive
        tracker.recordMethodCall(buf, "MessageReceiver", "receive", buf.refCnt());

        // Pass wrapper to pipeline
        pipelineProcess(wrapper);

        // Extract and handle
        businessLogic(wrapper);

        // Store result
        storeResult(wrapper);

        // Properly cleanup
        buf.release();
        tracker.recordMethodCall(buf, "MessageReceiver", "cleanup", buf.refCnt());

        System.out.println("✓ Wrapper buffer properly released");
    }

    static void pipelineProcess(MessageWrapper wrapper) {
        ByteBufFlowTracker.getInstance().recordMethodCall(
            wrapper.getBuffer(), "Pipeline", "process", wrapper.getBuffer().refCnt());
    }

    static void businessLogic(MessageWrapper wrapper) {
        ByteBufFlowTracker.getInstance().recordMethodCall(
            wrapper.getBuffer(), "BusinessService", "execute", wrapper.getBuffer().refCnt());
    }

    static void storeResult(MessageWrapper wrapper) {
        ByteBufFlowTracker.getInstance().recordMethodCall(
            wrapper.getBuffer(), "DataStore", "save", wrapper.getBuffer().refCnt());
    }

    // ========================================================================
    // Scenario 5: Wrapper object with LEAK
    // ========================================================================

    static void wrapperFlow_WithLeak() {
        System.out.println("\n=== Scenario 5: Wrapper Object with LEAK ===");

        ByteBuf buf = Unpooled.buffer(256);
        MessageWrapper wrapper = new MessageWrapper(buf, "failed-message");
        ByteBufFlowTracker tracker = ByteBufFlowTracker.getInstance();

        // Root: receive
        tracker.recordMethodCall(buf, "FailedReceiver", "receive", buf.refCnt());

        // Start processing
        failedValidation(wrapper);

        // Error handling
        handleValidationError(wrapper);

        // OOPS - forgot to release in error path!

        System.out.println("⚠️  Wrapper buffer NOT released - LEAK!");
    }

    static void failedValidation(MessageWrapper wrapper) {
        ByteBufFlowTracker.getInstance().recordMethodCall(
            wrapper.getBuffer(), "Validator", "validateMessage", wrapper.getBuffer().refCnt());
    }

    static void handleValidationError(MessageWrapper wrapper) {
        ByteBufFlowTracker.getInstance().recordMethodCall(
            wrapper.getBuffer(), "ErrorHandler", "handleValidationError", wrapper.getBuffer().refCnt());
    }

    // ========================================================================
    // Scenario 6: Multiple paths (branching)
    // ========================================================================

    static void multiplePathsFlow() {
        System.out.println("\n=== Scenario 6: Multiple Execution Paths ===");

        ByteBufFlowTracker tracker = ByteBufFlowTracker.getInstance();

        // Simulate multiple requests taking different paths
        for (int i = 0; i < 100; i++) {
            ByteBuf buf = Unpooled.buffer(256);

            // Root: HTTP handler
            tracker.recordMethodCall(buf, "HttpHandler", "handleRequest", buf.refCnt());

            // Route to parser
            httpParse(buf);

            // Branch based on "validation result"
            if (i % 3 == 0) {
                // Path A: Success path
                httpValidate(buf, true);
                httpBuildResponse(buf);
                buf.release();
                tracker.recordMethodCall(buf, "HttpHandler", "complete", buf.refCnt());
            } else if (i % 3 == 1) {
                // Path B: Validation failure
                httpValidate(buf, false);
                httpBuildErrorResponse(buf);
                buf.release();
                tracker.recordMethodCall(buf, "HttpHandler", "complete", buf.refCnt());
            } else {
                // Path C: Exception path (simulated leak)
                httpValidate(buf, false);
                httpException(buf);
                // Oops, forgot to release in exception handler
            }
        }

        System.out.println("✓ Processed 100 requests with multiple paths");
    }

    static void httpParse(ByteBuf buf) {
        ByteBufFlowTracker.getInstance().recordMethodCall(buf, "RequestParser", "parse", buf.refCnt());
    }

    static void httpValidate(ByteBuf buf, boolean success) {
        // Simulate validation retaining buffer
        buf.retain();
        ByteBufFlowTracker.getInstance().recordMethodCall(buf, "Validator", "validate", buf.refCnt());
        buf.release(); // Validation done
    }

    static void httpBuildResponse(ByteBuf buf) {
        ByteBufFlowTracker.getInstance().recordMethodCall(buf, "ResponseBuilder", "build", buf.refCnt());
    }

    static void httpBuildErrorResponse(ByteBuf buf) {
        ByteBufFlowTracker.getInstance().recordMethodCall(buf, "ErrorResponseBuilder", "buildError", buf.refCnt());
    }

    static void httpException(ByteBuf buf) {
        ByteBufFlowTracker.getInstance().recordMethodCall(buf, "ExceptionHandler", "handleException", buf.refCnt());
    }

    // ========================================================================
    // Main: Run all scenarios and print results
    // ========================================================================

    public static void main(String[] args) {
        System.out.println("╔════════════════════════════════════════════════════════════╗");
        System.out.println("║         ByteBuf Flow Tracker - Example Usage              ║");
        System.out.println("╚════════════════════════════════════════════════════════════╝");

        // Run all scenarios
        directFlow_ProperCleanup();
        directFlow_WithLeak();
        directFlow_WithRetain();
        wrapperFlow_ProperCleanup();
        wrapperFlow_WithLeak();
        multiplePathsFlow();

        System.out.println("\n\n");
        System.out.println("╔════════════════════════════════════════════════════════════╗");
        System.out.println("║                    TRACKING RESULTS                        ║");
        System.out.println("╚════════════════════════════════════════════════════════════╝");

        ByteBufFlowTracker tracker = ByteBufFlowTracker.getInstance();
        TrieRenderer renderer = new TrieRenderer(tracker.getTrie());

        // 1. Human-Readable Format: Visual Tree
        System.out.println("\n" + "=".repeat(60));
        System.out.println("HUMAN-READABLE FORMAT: Visual Tree");
        System.out.println("=".repeat(60));
        System.out.println(renderer.renderSummary());
        System.out.println();
        System.out.println(renderer.renderIndentedTree());
        System.out.println("Look for:");
        System.out.println("  • Leaf nodes with ⚠️ LEAK indicator");
        System.out.println("  • High traversal counts indicate hot paths");
        System.out.println("  • Different refCounts for same method indicate branching logic");

        // 2. LLM-Optimized Format: Structured Text
        System.out.println("\n" + "=".repeat(60));
        System.out.println("LLM-OPTIMIZED FORMAT: Structured Analysis");
        System.out.println("=".repeat(60));
        System.out.println(renderer.renderForLLM());

        System.out.println("=".repeat(60));
        System.out.println("Active flows still tracked: " + tracker.getActiveFlowCount());
        System.out.println("=".repeat(60));
    }
}
