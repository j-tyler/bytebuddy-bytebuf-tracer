package com.example.bytebuf.tracker;

import com.example.bytebuf.tracker.view.TrieRenderer;

/**
 * Example demonstrating ByteBuf flow tracking.
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
     * Simple mock ByteBuf for demonstration
     * In production, this would be io.netty.buffer.ByteBuf
     */
    static class MockByteBuf {
        private int refCnt;
        private final int id;
        private static int nextId = 1;

        public MockByteBuf() {
            this.refCnt = 1;
            this.id = nextId++;
        }

        public int refCnt() {
            return refCnt;
        }

        public MockByteBuf retain() {
            refCnt++;
            return this;
        }

        public boolean release() {
            if (refCnt > 0) {
                refCnt--;
            }
            return refCnt == 0;
        }

        @Override
        public String toString() {
            return "ByteBuf#" + id + "[refCnt=" + refCnt + "]";
        }
    }

    /**
     * Wrapper object that holds a ByteBuf
     */
    static class MessageWrapper {
        private final MockByteBuf buffer;
        private final String metadata;

        public MessageWrapper(MockByteBuf buffer, String metadata) {
            this.buffer = buffer;
            this.metadata = metadata;
        }

        public MockByteBuf getBuffer() {
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

        MockByteBuf buf = new MockByteBuf();
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

    static void decode(MockByteBuf buf) {
        ByteBufFlowTracker.getInstance().recordMethodCall(buf, "Decoder", "decode", buf.refCnt());
    }

    static void handle(MockByteBuf buf) {
        ByteBufFlowTracker.getInstance().recordMethodCall(buf, "Handler", "handle", buf.refCnt());
    }

    static void process(MockByteBuf buf) {
        ByteBufFlowTracker.getInstance().recordMethodCall(buf, "Processor", "process", buf.refCnt());
    }

    // ========================================================================
    // Scenario 2: Direct ByteBuf with LEAK (forgot to release)
    // ========================================================================

    static void directFlow_WithLeak() {
        System.out.println("\n=== Scenario 2: Direct Flow with LEAK ===");

        MockByteBuf buf = new MockByteBuf();
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

    static void errorDecode(MockByteBuf buf) {
        ByteBufFlowTracker.getInstance().recordMethodCall(buf, "ErrorDecoder", "decode", buf.refCnt());
    }

    static void errorHandle(MockByteBuf buf) {
        ByteBufFlowTracker.getInstance().recordMethodCall(buf, "ErrorHandler", "handleError", buf.refCnt());
    }

    static void logError(MockByteBuf buf) {
        ByteBufFlowTracker.getInstance().recordMethodCall(buf, "Logger", "logError", buf.refCnt());
    }

    // ========================================================================
    // Scenario 3: ByteBuf with retain/release (refCount changes)
    // ========================================================================

    static void directFlow_WithRetain() {
        System.out.println("\n=== Scenario 3: Direct Flow with Retain/Release ===");

        MockByteBuf buf = new MockByteBuf();
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

    static void validateWithRetain(MockByteBuf buf) {
        ByteBufFlowTracker tracker = ByteBufFlowTracker.getInstance();
        tracker.recordMethodCall(buf, "Validator", "validate", buf.refCnt());

        // Retain for async processing
        buf.retain();
        tracker.recordMethodCall(buf, "Validator", "afterRetain", buf.refCnt());
    }

    static void asyncProcess(MockByteBuf buf) {
        ByteBufFlowTracker.getInstance().recordMethodCall(buf, "AsyncProcessor", "processAsync", buf.refCnt());
    }

    // ========================================================================
    // Scenario 4: Wrapper object passed around
    // ========================================================================

    static void wrapperFlow_ProperCleanup() {
        System.out.println("\n=== Scenario 4: Wrapper Object with Proper Cleanup ===");

        MockByteBuf buf = new MockByteBuf();
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

        MockByteBuf buf = new MockByteBuf();
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
            MockByteBuf buf = new MockByteBuf();

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

    static void httpParse(MockByteBuf buf) {
        ByteBufFlowTracker.getInstance().recordMethodCall(buf, "RequestParser", "parse", buf.refCnt());
    }

    static void httpValidate(MockByteBuf buf, boolean success) {
        // Simulate validation retaining buffer
        buf.retain();
        ByteBufFlowTracker.getInstance().recordMethodCall(buf, "Validator", "validate", buf.refCnt());
        buf.release(); // Validation done
    }

    static void httpBuildResponse(MockByteBuf buf) {
        ByteBufFlowTracker.getInstance().recordMethodCall(buf, "ResponseBuilder", "build", buf.refCnt());
    }

    static void httpBuildErrorResponse(MockByteBuf buf) {
        ByteBufFlowTracker.getInstance().recordMethodCall(buf, "ErrorResponseBuilder", "buildError", buf.refCnt());
    }

    static void httpException(MockByteBuf buf) {
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

        // 1. Summary
        System.out.println("\n" + "=".repeat(60));
        System.out.println("SUMMARY");
        System.out.println("=".repeat(60));
        System.out.println(renderer.renderSummary());

        // 2. Tree View (human-readable)
        System.out.println("\n" + "=".repeat(60));
        System.out.println("TREE VIEW (Human-Readable)");
        System.out.println("=".repeat(60));
        System.out.println(renderer.renderIndentedTree());

        // 3. Flat Paths View
        System.out.println("\n" + "=".repeat(60));
        System.out.println("FLAT PATHS VIEW");
        System.out.println("=".repeat(60));
        System.out.println(renderer.renderFlatPaths());

        // 4. CSV View
        System.out.println("\n" + "=".repeat(60));
        System.out.println("CSV VIEW (For Spreadsheet Analysis)");
        System.out.println("=".repeat(60));
        System.out.println(renderer.renderCsv());

        // 5. JSON View (truncated for brevity)
        System.out.println("\n" + "=".repeat(60));
        System.out.println("JSON VIEW (First 50 lines)");
        System.out.println("=".repeat(60));
        String json = renderer.renderJson();
        String[] jsonLines = json.split("\n");
        for (int i = 0; i < Math.min(50, jsonLines.length); i++) {
            System.out.println(jsonLines[i]);
        }
        if (jsonLines.length > 50) {
            System.out.println("... (truncated, " + (jsonLines.length - 50) + " more lines)");
        }

        // 6. Analysis Notes
        System.out.println("\n\n" + "=".repeat(60));
        System.out.println("ANALYSIS NOTES");
        System.out.println("=".repeat(60));
        System.out.println("Look for:");
        System.out.println("  • Leaf nodes with ⚠️ LEAK indicator");
        System.out.println("  • [LEAK:ref=N] in flat view");
        System.out.println("  • High traversal counts indicate hot paths");
        System.out.println("  • Different refCounts for same method indicate branching logic");
        System.out.println("\nExpected leaks in this example:");
        System.out.println("  • LeakyExample flow (Scenario 2)");
        System.out.println("  • FailedReceiver flow (Scenario 5)");
        System.out.println("  • Some HttpHandler exception paths (Scenario 6)");

        System.out.println("\n" + "=".repeat(60));
        System.out.println("Active flows still tracked: " + tracker.getActiveFlowCount());
        System.out.println("=".repeat(60));
    }
}
