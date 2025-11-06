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
        
        // Get the flat view to see leaks
        TrieRenderer renderer = new TrieRenderer(tracker.getTrie());
        String flatView = renderer.renderFlatPaths();
        
        System.out.println("\nLeak Detection - Flat Paths:");
        System.out.println(flatView);
        
        // The leaky path should show ref=1 at the end
        assertTrue(flatView.contains("ErrorLogger.log[1]"));
        // The good path should show ref=0 at the end
        assertTrue(flatView.contains("ResponseWriter.write[0]"));
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
    public void testCsvExport() {
        // Create some test data
        ByteBuf buffer = Unpooled.buffer(256);
        
        tracker.recordMethodCall(buffer, "CsvTest", "method1", buffer.refCnt());
        tracker.recordMethodCall(buffer, "CsvTest", "method2", buffer.refCnt());
        buffer.release();
        tracker.recordMethodCall(buffer, "CsvTest", "method3", buffer.refCnt());
        
        TrieRenderer renderer = new TrieRenderer(tracker.getTrie());
        String csv = renderer.renderCsv();
        
        System.out.println("\nCSV Export:");
        System.out.println(csv);
        
        assertTrue(csv.startsWith("root,path,final_ref_count,traversal_count,is_leak"));
        assertTrue(csv.contains("CsvTest.method1"));
        assertTrue(csv.contains("false")); // not a leak
    }
    
    @Test
    public void testStaticMethodTracking() {
        // Create a ByteBuf
        ByteBuf buffer = Unpooled.buffer(256);

        // Pass to instance method
        StaticMethodExample.processWithInstance(buffer);

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

        // Check if constructor was tracked
        boolean constructorTracked = tree.contains("WrappedMessage") || tree.contains("<init>");
        System.out.println("Constructor tracked: " + constructorTracked);

        // Document the current behavior
        assertFalse("Constructors are currently NOT tracked (this is expected to fail)", constructorTracked);
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
        String flatPaths = renderer.renderFlatPaths();

        System.out.println("\n=== Flow Tree ===");
        System.out.println(tree);

        System.out.println("\n=== Flat Paths ===");
        System.out.println(flatPaths);

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
        String flatPaths = renderer.renderFlatPaths();

        System.out.println("\n=== Flow Tree (Continuous) ===");
        System.out.println(tree);

        System.out.println("\n=== Flat Paths ===");
        System.out.println(flatPaths);

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

    @Test
    public void testPrivateInnerClassTracking() {
        System.out.println("\n=== Private Inner Class Tracking Test ===");

        // Private inner classes CAN be matched, but only public/protected members are tracked
        ByteBuf buffer = Unpooled.buffer(256);
        tracker.recordMethodCall(buffer, "TestClient", "allocate", buffer.refCnt());

        // Create instance using public constructor
        PrivateWrapperWithPublicConstructor wrapper1 = new PrivateWrapperWithPublicConstructor(buffer);

        // Process
        wrapper1.process();

        // Release
        buffer.release();
        tracker.recordMethodCall(buffer, "TestClient", "cleanup", buffer.refCnt());

        TrieRenderer renderer = new TrieRenderer(tracker.getTrie());
        String tree = renderer.renderIndentedTree();

        System.out.println("\n=== Flow Tree ===");
        System.out.println(tree);

        // Public constructor should be tracked (if trackConstructors configured)
        System.out.println("\nNote: Private inner class CAN be instrumented");
        System.out.println("But only public/protected constructors and methods are tracked");

        assertTrue("Public process() method should be tracked", tree.contains("process"));
    }

    /**
     * Private inner class with public constructor
     * This CAN be instrumented - class visibility doesn't matter,
     * but only public/protected members are tracked
     */
    private static class PrivateWrapperWithPublicConstructor {
        private final ByteBuf data;

        // Public constructor - CAN be tracked with trackConstructors
        public PrivateWrapperWithPublicConstructor(ByteBuf data) {
            this.data = data;
            ByteBufFlowTracker.getInstance().recordMethodCall(
                data, "PrivateWrapperWithPublicConstructor", "<init>", data.refCnt());
        }

        // Private constructor - NOT tracked
        private PrivateWrapperWithPublicConstructor() {
            this.data = null;
        }

        // Public method - CAN be tracked
        public void process() {
            if (data != null) {
                ByteBufFlowTracker.getInstance().recordMethodCall(
                    data, "PrivateWrapperWithPublicConstructor", "process", data.refCnt());
            }
        }

        // Private method - NOT tracked
        private void internalProcess() {
            // This would not be instrumented
        }
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
}
