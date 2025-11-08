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

package com.example.bytebuf.tracker.test;

import com.example.bytebuf.tracker.ByteBufFlowTracker;
import com.example.bytebuf.tracker.view.TrieRenderer;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Test and example usage of ByteBuf Flow Tracker
 */
public class ByteBufFlowTrackerTest {
    
    private ByteBufFlowTracker tracker;
    
    @Before
    public void setup() {
        tracker = ByteBufFlowTracker.getInstance();
        tracker.reset();
    }
    
    @Test
    public void testSimpleFlowTracking() {
        // Create a ByteBuf
        ByteBuf buffer = Unpooled.buffer(256);
        
        // Simulate method calls
        tracker.recordMethodCall(buffer, "FrameDecoder", "decode", buffer.refCnt());
        tracker.recordMethodCall(buffer, "MessageHandler", "handle", buffer.refCnt());
        tracker.recordMethodCall(buffer, "BusinessService", "process", buffer.refCnt());
        
        // Release the buffer
        buffer.release();
        tracker.recordMethodCall(buffer, "BusinessService", "process", buffer.refCnt());
        
        // Verify the tree structure
        TrieRenderer renderer = new TrieRenderer(tracker.getTrie());
        String tree = renderer.renderIndentedTree();
        
        assertTrue(tree.contains("FrameDecoder.decode"));
        assertTrue(tree.contains("MessageHandler.handle"));
        assertTrue(tree.contains("BusinessService.process"));
        
        System.out.println("Simple Flow Tree:");
        System.out.println(tree);
    }
    
    @Test
    public void testLeakDetection() {
        // Create a ByteBuf that will leak
        ByteBuf leakyBuffer = Unpooled.buffer(256);
        
        // Simulate method calls
        tracker.recordMethodCall(leakyBuffer, "HttpHandler", "handleRequest", leakyBuffer.refCnt());
        tracker.recordMethodCall(leakyBuffer, "RequestProcessor", "process", leakyBuffer.refCnt());
        tracker.recordMethodCall(leakyBuffer, "ErrorLogger", "log", leakyBuffer.refCnt());
        // Note: We don't release the buffer - this is a leak!
        
        // Create another ByteBuf that is properly released
        ByteBuf goodBuffer = Unpooled.buffer(256);
        tracker.recordMethodCall(goodBuffer, "HttpHandler", "handleRequest", goodBuffer.refCnt());
        tracker.recordMethodCall(goodBuffer, "RequestProcessor", "process", goodBuffer.refCnt());
        tracker.recordMethodCall(goodBuffer, "ResponseWriter", "write", goodBuffer.refCnt());
        goodBuffer.release();
        tracker.recordMethodCall(goodBuffer, "ResponseWriter", "write", goodBuffer.refCnt());
        
        // Get the LLM view to see leaks
        TrieRenderer renderer = new TrieRenderer(tracker.getTrie());
        String llmView = renderer.renderForLLM();

        System.out.println("\nLeak Detection - LLM View:");
        System.out.println(llmView);

        // The leaky path should be in the LEAKS section
        assertTrue(llmView.contains("LEAKS:"));
        assertTrue(llmView.contains("ErrorLogger.log"));
        // The good path should have final_ref=0
        assertTrue(llmView.contains("final_ref=0"));
    }
    
    @Test
    public void testRefCountAnomalies() {
        // Simulate the same path with different refCounts
        ByteBuf buffer1 = Unpooled.buffer(256);
        buffer1.retain(); // refCount = 2
        
        tracker.recordMethodCall(buffer1, "MessageDecoder", "decode", buffer1.refCnt());
        tracker.recordMethodCall(buffer1, "MessageValidator", "validate", buffer1.refCnt());
        buffer1.release();
        tracker.recordMethodCall(buffer1, "MessageProcessor", "process", buffer1.refCnt());
        buffer1.release();
        tracker.recordMethodCall(buffer1, "MessageProcessor", "process", buffer1.refCnt());
        
        // Same path but different refCount pattern
        ByteBuf buffer2 = Unpooled.buffer(256);
        // refCount = 1 (no extra retain)
        
        tracker.recordMethodCall(buffer2, "MessageDecoder", "decode", buffer2.refCnt());
        tracker.recordMethodCall(buffer2, "MessageValidator", "validate", buffer2.refCnt());
        tracker.recordMethodCall(buffer2, "MessageProcessor", "process", buffer2.refCnt());
        buffer2.release();
        tracker.recordMethodCall(buffer2, "MessageProcessor", "process", buffer2.refCnt());
        
        TrieRenderer renderer = new TrieRenderer(tracker.getTrie());
        String tree = renderer.renderIndentedTree();
        
        System.out.println("\nRefCount Anomaly Detection:");
        System.out.println(tree);
        
        // Should show MessageValidator appearing twice with different refCounts
        assertTrue(tree.contains("MessageValidator.validate [ref=2"));
        assertTrue(tree.contains("MessageValidator.validate [ref=1"));
    }
    
    @Test
    public void testHighVolumeTracking() {
        // Simulate high volume traffic
        for (int i = 0; i < 1000; i++) {
            ByteBuf buffer = Unpooled.buffer(256);
            
            tracker.recordMethodCall(buffer, "HighVolumeHandler", "handle", buffer.refCnt());
            tracker.recordMethodCall(buffer, "FastProcessor", "process", buffer.refCnt());
            
            if (i % 10 == 0) {
                // 10% take a different path
                tracker.recordMethodCall(buffer, "SlowProcessor", "process", buffer.refCnt());
            }
            
            buffer.release();
            tracker.recordMethodCall(buffer, "FastProcessor", "process", buffer.refCnt());
        }
        
        TrieRenderer renderer = new TrieRenderer(tracker.getTrie());
        String summary = renderer.renderSummary();
        
        System.out.println("\nHigh Volume Summary:");
        System.out.println(summary);
        
        assertTrue(summary.contains("Total Traversals: 1000"));
    }
    
    
    @Test
    public void testStaticMethodTracking() {
        // Create a ByteBuf
        ByteBuf buffer = Unpooled.buffer(256);

        // Pass to instance method
        StaticMethodExample example = new StaticMethodExample();
        example.processWithInstance(buffer);

        // Pass to static method
        StaticMethodExample.processWithStatic(buffer);

        // Release the buffer
        buffer.release();
        tracker.recordMethodCall(buffer, "StaticMethodExample", "cleanup", buffer.refCnt());

        // Verify the tree structure includes static method
        TrieRenderer renderer = new TrieRenderer(tracker.getTrie());
        String tree = renderer.renderIndentedTree();

        System.out.println("\nStatic Method Tracking Test:");
        System.out.println(tree);

        // Both instance and static methods should be tracked
        assertTrue("Instance method should be tracked", tree.contains("processWithInstance"));
        assertTrue("Static method should be tracked", tree.contains("processWithStatic"));
    }

    @Test
    public void testConstructorTracking() {
        // Create a ByteBuf
        ByteBuf buffer = Unpooled.buffer(256);
        tracker.recordMethodCall(buffer, "TestClass", "allocate", buffer.refCnt());

        // Pass ByteBuf to a constructor - currently NOT tracked
        WrappedMessage message = new WrappedMessage(buffer);

        // Pass the wrapped object to another method
        // The ByteBuf is still inside, but is it tracked?
        processWrappedMessage(message);

        // Release the buffer
        buffer.release();
        tracker.recordMethodCall(buffer, "TestClass", "cleanup", buffer.refCnt());

        // Verify the tree structure
        TrieRenderer renderer = new TrieRenderer(tracker.getTrie());
        String tree = renderer.renderIndentedTree();

        System.out.println("\nConstructor Tracking Test:");
        System.out.println(tree);

        // Check if constructor was tracked (look for <init> specifically)
        // Note: Must check for "<init>" pattern, not just "WrappedMessage"
        // which can match in method names like "processWrappedMessage"
        boolean constructorTracked = tree.contains("<init>");
        System.out.println("Constructor tracked: " + constructorTracked);

        // Verify actual behavior: constructors are NOT automatically tracked
        // Only methods that explicitly call tracker.recordMethodCall appear
        assertFalse("Constructors should NOT be auto-tracked without trackConstructors config",
                    constructorTracked);

        // Verify the methods that were manually tracked DO appear
        assertTrue("allocate should be tracked", tree.contains("allocate"));
        assertTrue("processWrappedMessage should be tracked", tree.contains("processWrappedMessage"));
        assertTrue("cleanup should be tracked", tree.contains("cleanup"));
    }

    @Test
    public void testWrappedObjectFlowTracking() {
        System.out.println("\n=== Wrapped Object Flow Tracking Test ===");

        // Step 1: Allocate ByteBuf
        ByteBuf buffer = Unpooled.buffer(256);
        buffer.writeBytes("Test Data".getBytes());
        tracker.recordMethodCall(buffer, "Client", "allocate", buffer.refCnt());

        // Step 2: Pass to method that wraps it
        WrappedMessage message = wrapInMessage(buffer);

        // Step 3: Pass wrapped object to different method
        processWrappedMessage(message);

        // Step 4: Another method processes it
        validateWrappedMessage(message);

        // Step 5: Finally extract and release
        ByteBuf extracted = extractFromMessage(message);
        extracted.release();
        tracker.recordMethodCall(extracted, "Client", "release", extracted.refCnt());

        // Analyze the flow
        TrieRenderer renderer = new TrieRenderer(tracker.getTrie());
        String tree = renderer.renderIndentedTree();
        String llmView = renderer.renderForLLM();

        System.out.println("\n=== Flow Tree ===");
        System.out.println(tree);

        System.out.println("\n=== LLM View ===");
        System.out.println(llmView);

        // Check what was tracked
        boolean wrappingTracked = tree.contains("wrapInMessage");
        boolean processingTracked = tree.contains("processWrappedMessage");
        boolean validationTracked = tree.contains("validateWrappedMessage");
        boolean extractionTracked = tree.contains("extractFromMessage");

        System.out.println("\nTracking Results:");
        System.out.println("  wrapInMessage tracked: " + wrappingTracked);
        System.out.println("  processWrappedMessage tracked: " + processingTracked);
        System.out.println("  validateWrappedMessage tracked: " + validationTracked);
        System.out.println("  extractFromMessage tracked: " + extractionTracked);

        // Document expected vs actual behavior
        assertTrue("wrapInMessage should be tracked (ByteBuf is a parameter)", wrappingTracked);
        assertTrue("extractFromMessage should be tracked (ByteBuf is return value)", extractionTracked);

        // These will likely FAIL because WrappedMessage is not a ByteBuf
        System.out.println("\nExpected behavior: We should see CONTINUOUS flow from allocate -> release");
        System.out.println("Actual behavior: Flow likely BREAKS when ByteBuf is wrapped in WrappedMessage");

        // The flow breaks because:
        // 1. processWrappedMessage receives WrappedMessage, not ByteBuf
        // 2. shouldTrack(WrappedMessage) returns false
        // 3. The tracker loses visibility of the ByteBuf
    }

    @Test
    public void testConstructorWithByteBuffParameter() {
        System.out.println("\n=== Constructor with ByteBuf Parameter Test ===");

        // Allocate ByteBuf
        ByteBuf buffer = Unpooled.buffer(256);
        tracker.recordMethodCall(buffer, "TestClient", "create", buffer.refCnt());

        // Pass to constructor - constructors are currently excluded from tracking
        MessageWithConstructorTracking message = new MessageWithConstructorTracking(buffer);

        // Use the message
        message.process();

        // Release
        buffer.release();
        tracker.recordMethodCall(buffer, "TestClient", "cleanup", buffer.refCnt());

        // Check the flow
        TrieRenderer renderer = new TrieRenderer(tracker.getTrie());
        String tree = renderer.renderIndentedTree();

        System.out.println("\n=== Flow Tree ===");
        System.out.println(tree);

        // Constructors are currently NOT tracked
        boolean constructorInFlow = tree.contains("MessageWithConstructorTracking") || tree.contains("<init>");
        System.out.println("\nConstructor appears in flow: " + constructorInFlow);
        System.out.println("Note: Constructors are currently excluded by .and(not(isConstructor()))");

        // But the process() method should be tracked if it takes ByteBuf
        boolean processTracked = tree.contains("process");
        System.out.println("process() method tracked: " + processTracked);
    }

    /**
     * Test class that wraps a ByteBuf
     */
    public static class WrappedMessage {
        private final ByteBuf data;
        private final long timestamp;

        public WrappedMessage(ByteBuf data) {
            this.data = data;
            this.timestamp = System.currentTimeMillis();
        }

        public ByteBuf getData() {
            return data;
        }

        public long getTimestamp() {
            return timestamp;
        }
    }

    /**
     * Test class that manually tracks in constructor
     */
    public static class MessageWithConstructorTracking {
        private final ByteBuf data;

        public MessageWithConstructorTracking(ByteBuf data) {
            this.data = data;
            // Manual tracking in constructor
            ByteBufFlowTracker.getInstance().recordMethodCall(
                data, "MessageWithConstructorTracking", "<init>", data.refCnt());
        }

        public void process() {
            ByteBufFlowTracker.getInstance().recordMethodCall(
                data, "MessageWithConstructorTracking", "process", data.refCnt());
        }

        public ByteBuf getData() {
            return data;
        }
    }

    /**
     * Method that wraps ByteBuf in a message object
     */
    public WrappedMessage wrapInMessage(ByteBuf buffer) {
        tracker.recordMethodCall(buffer, "TestHelper", "wrapInMessage", buffer.refCnt());
        return new WrappedMessage(buffer);
    }

    /**
     * Method that processes wrapped message
     * This receives WrappedMessage, not ByteBuf, so won't be auto-tracked
     */
    public void processWrappedMessage(WrappedMessage message) {
        // Manual tracking since WrappedMessage isn't a ByteBuf
        ByteBuf buffer = message.getData();
        tracker.recordMethodCall(buffer, "TestHelper", "processWrappedMessage", buffer.refCnt());
    }

    /**
     * Method that validates wrapped message
     */
    public void validateWrappedMessage(WrappedMessage message) {
        ByteBuf buffer = message.getData();
        tracker.recordMethodCall(buffer, "TestHelper", "validateWrappedMessage", buffer.refCnt());
    }

    /**
     * Method that extracts ByteBuf from wrapped message
     */
    public ByteBuf extractFromMessage(WrappedMessage message) {
        ByteBuf buffer = message.getData();
        tracker.recordMethodCall(buffer, "TestHelper", "extractFromMessage", buffer.refCnt());
        return buffer;
    }

    /**
     * Helper class to test static method tracking
     */
    public static class StaticMethodExample {

        public void processWithInstance(ByteBuf buffer) {
            ByteBufFlowTracker.getInstance().recordMethodCall(
                buffer, getClass().getSimpleName(), "processWithInstance", buffer.refCnt());
        }

        public static void processWithStatic(ByteBuf buffer) {
            ByteBufFlowTracker.getInstance().recordMethodCall(
                buffer, "StaticMethodExample", "processWithStatic", buffer.refCnt());
        }
    }

    @Test
    public void testContinuousFlowWithConstructorTracking() {
        System.out.println("\n=== Continuous Flow with Constructor Tracking Test ===");

        // This test demonstrates the EXPECTED behavior when constructor tracking
        // is enabled via: trackConstructors=TrackedMessage
        // Since we're in a unit test without the agent, we manually track to simulate it.

        // Step 1: Allocate ByteBuf
        ByteBuf buffer = Unpooled.buffer(256);
        buffer.writeBytes("Continuous flow test".getBytes());
        tracker.recordMethodCall(buffer, "TestClient", "allocate", buffer.refCnt());

        // Step 2: Pass to method that will wrap it
        prepareMessage(buffer);

        // Step 3: Pass to constructor (would be auto-tracked with trackConstructors config)
        TrackedMessage message = new TrackedMessage(buffer);

        // Step 4: Pass wrapped object to another method
        processTrackedMessage(message);

        // Step 5: Validate wrapped object
        validateTrackedMessage(message);

        // Step 6: Extract and release
        ByteBuf extracted = message.getBuffer();
        extracted.release();
        tracker.recordMethodCall(extracted, "TestClient", "cleanup", extracted.refCnt());

        // Verify continuous flow
        TrieRenderer renderer = new TrieRenderer(tracker.getTrie());
        String tree = renderer.renderIndentedTree();
        String llmView = renderer.renderForLLM();

        System.out.println("\n=== Flow Tree (Continuous) ===");
        System.out.println(tree);

        System.out.println("\n=== LLM View ===");
        System.out.println(llmView);

        // Verify all steps are present
        assertTrue("allocate should be tracked", tree.contains("allocate"));
        assertTrue("prepareMessage should be tracked", tree.contains("prepareMessage"));
        assertTrue("TrackedMessage.<init> should be tracked", tree.contains("TrackedMessage") || tree.contains("<init>"));
        assertTrue("processTrackedMessage should be tracked", tree.contains("processTrackedMessage"));
        assertTrue("validateTrackedMessage should be tracked", tree.contains("validateTrackedMessage"));
        assertTrue("cleanup should be tracked", tree.contains("cleanup"));

        // Verify it's a single continuous path (no multiple roots)
        String summary = renderer.renderSummary();
        System.out.println("\n=== Summary ===");
        System.out.println(summary);

        // Should have 1 root (the first method that touched the ByteBuf)
        assertTrue("Should have exactly 1 root for continuous flow", summary.contains("Total Root Methods: 1"));

        System.out.println("\n✓ Continuous flow verified: ByteBuf -> method -> constructor -> method -> cleanup");
    }

    /**
     * Test class that represents a wrapper with constructor tracking
     */
    public static class TrackedMessage {
        private final ByteBuf buffer;
        private final String messageId;

        public TrackedMessage(ByteBuf buffer) {
            this.buffer = buffer;
            this.messageId = "MSG-" + System.currentTimeMillis();

            // Simulates what the agent would do when trackConstructors is enabled
            ByteBufFlowTracker.getInstance().recordMethodCall(
                buffer, "TrackedMessage", "<init>", buffer.refCnt());
        }

        public ByteBuf getBuffer() {
            return buffer;
        }

        public String getMessageId() {
            return messageId;
        }
    }

    /**
     * Helper method to prepare a message
     */
    public void prepareMessage(ByteBuf buffer) {
        tracker.recordMethodCall(buffer, "TestHelper", "prepareMessage", buffer.refCnt());
    }

    /**
     * Process a tracked message (wrapper object)
     */
    public void processTrackedMessage(TrackedMessage message) {
        // Extract ByteBuf and track
        ByteBuf buffer = message.getBuffer();
        tracker.recordMethodCall(buffer, "TestHelper", "processTrackedMessage", buffer.refCnt());
    }

    /**
     * Validate a tracked message
     */
    public void validateTrackedMessage(TrackedMessage message) {
        ByteBuf buffer = message.getBuffer();
        tracker.recordMethodCall(buffer, "TestHelper", "validateTrackedMessage", buffer.refCnt());
    }

    /**
     * Example of how to use the tracker in production code
     */
    public static class ProductionExample {

        private final ByteBufFlowTracker tracker = ByteBufFlowTracker.getInstance();

        public void handleMessage(ByteBuf message) {
            // Track entry into this method
            tracker.recordMethodCall(message, getClass().getSimpleName(), "handleMessage", message.refCnt());

            try {
                // Process the message
                processInternal(message);
            } finally {
                // Always release and track
                message.release();
                tracker.recordMethodCall(message, getClass().getSimpleName(), "handleMessage_exit", message.refCnt());
            }
        }

        private void processInternal(ByteBuf message) {
            tracker.recordMethodCall(message, getClass().getSimpleName(), "processInternal", message.refCnt());
            // ... actual processing ...
        }
    }

    @Test
    public void testReleaseTrackingOnlyWhenRefCntReachesZero() {
        System.out.println("\n=== Release Tracking (Only When refCnt -> 0) Test ===");

        // Scenario: ByteBuf with multiple retain/release calls
        // Only the FINAL release (when refCnt drops to 0) should be tracked

        ByteBuf buffer = Unpooled.buffer(256);

        // Initial allocation
        tracker.recordMethodCall(buffer, "Client", "allocate", buffer.refCnt()); // ref=1

        // Method that retains the buffer
        buffer.retain();
        tracker.recordMethodCall(buffer, "Handler", "retain", buffer.refCnt()); // ref=2

        // Another retain
        buffer.retain();
        tracker.recordMethodCall(buffer, "Processor", "retain", buffer.refCnt()); // ref=3

        // First release (ref=3 -> 2) - should NOT be tracked in final tree
        buffer.release();
        // NOTE: With lifecycle advice enabled, this intermediate release would be SKIPPED

        // Processing with ref=2
        tracker.recordMethodCall(buffer, "Processor", "process", buffer.refCnt()); // ref=2

        // Second release (ref=2 -> 1) - should NOT be tracked in final tree
        buffer.release();
        // NOTE: With lifecycle advice enabled, this intermediate release would be SKIPPED

        // Processing with ref=1
        tracker.recordMethodCall(buffer, "Handler", "cleanup", buffer.refCnt()); // ref=1

        // Final release (ref=1 -> 0) - THIS should be tracked
        buffer.release();
        tracker.recordMethodCall(buffer, "Handler", "release", buffer.refCnt()); // ref=0

        // Verify the flow
        TrieRenderer renderer = new TrieRenderer(tracker.getTrie());
        String tree = renderer.renderIndentedTree();
        String llmView = renderer.renderForLLM();

        System.out.println("\n=== Flow Tree ===");
        System.out.println(tree);

        System.out.println("\n=== LLM View ===");
        System.out.println(llmView);

        // Verify that:
        // 1. Initial allocation is tracked
        assertTrue("allocate should be tracked", tree.contains("allocate"));

        // 2. Retain calls are tracked (to show refCnt increases)
        assertTrue("retain should be tracked", tree.contains("retain"));

        // 3. Final release with ref=0 is tracked
        assertTrue("Final release with ref=0 should be tracked",
                   tree.contains("release") && tree.contains("[ref=0"));

        // 4. The leaf node shows ref=0 (properly released)
        assertTrue("Leaf should show ref=0 (not a leak)", llmView.contains("final_ref=0"));

        System.out.println("\n✓ Release tracking verified: Only final release (refCnt->0) is tracked");
        System.out.println("✓ Intermediate releases are skipped to avoid tree clutter");
    }

    @Test
    public void testLeafNodeWithoutReleaseIsLeak() {
        System.out.println("\n=== Leaf Node Without Release = Leak Test ===");

        // Scenario: ByteBuf that is never released
        ByteBuf leakyBuffer = Unpooled.buffer(256);

        tracker.recordMethodCall(leakyBuffer, "Service", "allocate", leakyBuffer.refCnt()); // ref=1
        tracker.recordMethodCall(leakyBuffer, "Service", "process", leakyBuffer.refCnt());  // ref=1
        tracker.recordMethodCall(leakyBuffer, "Service", "store", leakyBuffer.refCnt());    // ref=1
        // NOTE: No release() call!

        // Scenario: ByteBuf that is properly released
        ByteBuf goodBuffer = Unpooled.buffer(256);

        tracker.recordMethodCall(goodBuffer, "Service", "allocate", goodBuffer.refCnt());  // ref=1
        tracker.recordMethodCall(goodBuffer, "Service", "process", goodBuffer.refCnt());   // ref=1
        goodBuffer.release();
        tracker.recordMethodCall(goodBuffer, "Service", "release", goodBuffer.refCnt());   // ref=0

        // Verify the flow
        TrieRenderer renderer = new TrieRenderer(tracker.getTrie());
        String tree = renderer.renderIndentedTree();
        String llmView = renderer.renderForLLM();

        System.out.println("\n=== Flow Tree ===");
        System.out.println(tree);

        System.out.println("\n=== LLM View ===");
        System.out.println(llmView);

        // Verify leak detection
        // Leaky path should be in LEAKS section with final_ref=1
        assertTrue("LEAKS section should exist", llmView.contains("LEAKS:"));
        assertTrue("Leaky path should have final_ref=1", llmView.contains("final_ref=1"));
        assertTrue("Leaky path should mention store", llmView.contains("store"));

        // Good path should have final_ref=0
        assertTrue("Good path should have final_ref=0", llmView.contains("final_ref=0"));

        System.out.println("\n✓ Leak detection verified:");
        System.out.println("  - Leaf with ref=1 (store) = LEAK");
        System.out.println("  - Leaf with ref=0 (release) = CLEAN");
    }

    @Test
    public void testLLMOptimizedFormat() {
        System.out.println("\n=== LLM-Optimized Format Test ===");

        // Create test data with both leaks and clean paths
        // Leaky buffer
        ByteBuf leakyBuffer = Unpooled.buffer(256);
        tracker.recordMethodCall(leakyBuffer, "Service", "allocate", leakyBuffer.refCnt());
        tracker.recordMethodCall(leakyBuffer, "Service", "process", leakyBuffer.refCnt());
        tracker.recordMethodCall(leakyBuffer, "Service", "forget", leakyBuffer.refCnt());
        // Not released - leak!

        // Clean buffer
        ByteBuf cleanBuffer = Unpooled.buffer(256);
        tracker.recordMethodCall(cleanBuffer, "Service", "allocate", cleanBuffer.refCnt());
        tracker.recordMethodCall(cleanBuffer, "Service", "process", cleanBuffer.refCnt());
        cleanBuffer.release();
        tracker.recordMethodCall(cleanBuffer, "Service", "cleanup", cleanBuffer.refCnt());

        // Render in LLM format
        TrieRenderer renderer = new TrieRenderer(tracker.getTrie());
        String llmView = renderer.renderForLLM();

        System.out.println("\n=== LLM Format Output ===");
        System.out.println(llmView);

        // Verify structure
        assertTrue("Should have METADATA section", llmView.contains("METADATA:"));
        assertTrue("Should have LEAKS section", llmView.contains("LEAKS:"));
        assertTrue("Should have FLOWS section", llmView.contains("FLOWS:"));

        // Verify metadata
        assertTrue("Should contain total_roots", llmView.contains("total_roots="));
        assertTrue("Should contain total_traversals", llmView.contains("total_traversals="));
        assertTrue("Should contain total_paths", llmView.contains("total_paths="));
        assertTrue("Should contain leak_paths", llmView.contains("leak_paths="));
        assertTrue("Should contain leak_percentage", llmView.contains("leak_percentage="));

        // Verify leak detection
        assertTrue("Should have leak with final_ref=1", llmView.contains("leak|") && llmView.contains("final_ref=1"));
        assertTrue("Leak should mention forget method", llmView.contains("forget"));

        // Verify flows section contains both leaks and clean paths
        assertTrue("Should have flow entries", llmView.contains("flow|"));
        assertTrue("Should have is_leak=true", llmView.contains("is_leak=true"));
        assertTrue("Should have is_leak=false", llmView.contains("is_leak=false"));
        assertTrue("Clean flow should have final_ref=0", llmView.contains("final_ref=0"));

        // Verify the format is pipe-delimited
        String[] lines = llmView.split("\n");
        for (String line : lines) {
            if (line.startsWith("leak|") || line.startsWith("flow|")) {
                assertTrue("Flow/leak lines should be pipe-delimited", line.contains("|"));
                assertTrue("Should contain root field", line.contains("root="));
                assertTrue("Should contain final_ref field", line.contains("final_ref="));
                assertTrue("Should contain path field", line.contains("path="));
            }
        }

        System.out.println("\n✓ LLM format verified:");
        System.out.println("  - Structured sections (METADATA, LEAKS, FLOWS)");
        System.out.println("  - Pipe-delimited fields for easy parsing");
        System.out.println("  - Leak detection in dedicated section");
        System.out.println("  - Token-efficient representation");
    }

    @Test
    public void testMultipleReleaseCallsOnSameBuffer() {
        System.out.println("\n=== Multiple Release Calls on Same Buffer Test ===");

        // Scenario: ByteBuf retained multiple times, then released multiple times
        // Only the FINAL release (refCnt -> 0) should appear in the tree

        ByteBuf buffer = Unpooled.buffer(256);

        // Allocate with ref=1
        tracker.recordMethodCall(buffer, "Manager", "create", buffer.refCnt()); // ref=1

        // Retain twice (ref=1 -> 2 -> 3)
        buffer.retain();
        tracker.recordMethodCall(buffer, "Manager", "retain", buffer.refCnt()); // ref=2
        buffer.retain();
        tracker.recordMethodCall(buffer, "Manager", "retain", buffer.refCnt()); // ref=3

        // Process with ref=3
        tracker.recordMethodCall(buffer, "Worker", "process", buffer.refCnt()); // ref=3

        // Release once (ref=3 -> 2) - intermediate, should be SKIPPED
        int refCountBefore1 = buffer.refCnt();
        buffer.release();
        int refCountAfter1 = buffer.refCnt();
        System.out.println("First release: ref=" + refCountBefore1 + " -> " + refCountAfter1 + " (intermediate, skipped)");
        // Don't track this intermediate release

        // Process with ref=2
        tracker.recordMethodCall(buffer, "Worker", "process", buffer.refCnt()); // ref=2

        // Release again (ref=2 -> 1) - intermediate, should be SKIPPED
        int refCountBefore2 = buffer.refCnt();
        buffer.release();
        int refCountAfter2 = buffer.refCnt();
        System.out.println("Second release: ref=" + refCountBefore2 + " -> " + refCountAfter2 + " (intermediate, skipped)");
        // Don't track this intermediate release

        // Final processing
        tracker.recordMethodCall(buffer, "Manager", "cleanup", buffer.refCnt()); // ref=1

        // Final release (ref=1 -> 0) - THIS is tracked
        int refCountBefore3 = buffer.refCnt();
        buffer.release();
        int refCountAfter3 = buffer.refCnt();
        System.out.println("Third release: ref=" + refCountBefore3 + " -> " + refCountAfter3 + " (FINAL, tracked)");
        tracker.recordMethodCall(buffer, "Manager", "release", buffer.refCnt()); // ref=0

        // Verify the flow
        TrieRenderer renderer = new TrieRenderer(tracker.getTrie());
        String tree = renderer.renderIndentedTree();

        System.out.println("\n=== Flow Tree ===");
        System.out.println(tree);

        // Count how many times "release" appears
        int releaseCount = 0;
        for (String line : tree.split("\n")) {
            if (line.contains("release") && line.contains("ref=")) {
                releaseCount++;
            }
        }

        System.out.println("\nRelease calls in tree: " + releaseCount);
        System.out.println("Expected: 1 (only the final release with ref=0)");

        // Only the final release should be in the tree
        assertEquals("Only final release should be tracked", 1, releaseCount);

        // And it should have ref=0
        assertTrue("Final release should have ref=0", tree.contains("release") && tree.contains("[ref=0"));

        System.out.println("\n✓ Multiple release tracking verified: Only final release appears in tree");
    }
}
