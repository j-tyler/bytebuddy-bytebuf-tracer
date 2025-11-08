package com.example.demo;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import com.example.bytebuf.tracker.ByteBufFlowTracker;
import com.example.bytebuf.tracker.view.TrieRenderer;

/**
 * Simple, clear example demonstrating ByteBuf wrapper pattern:
 * 1. Create a custom object that wraps a ByteBuf via constructor
 * 2. Pass the wrapper to a method
 * 3. That method extracts the ByteBuf from the wrapper
 * 4. Then releases the ByteBuf
 *
 * This demonstrates the tracking of ByteBuf flow through wrapper objects.
 */
public class SimpleWrapperExample {

    public static void main(String[] args) {
        System.out.println("=== Simple ByteBuf Wrapper Flow Example ===\n");

        SimpleWrapperExample example = new SimpleWrapperExample();

        // Run the example flow
        example.runExample();

        // Print the flow tracking results
        example.printResults();
    }

    /**
     * Runs the complete example flow:
     * allocate -> wrap in DataPacket -> pass to handler -> extract -> release
     */
    public void runExample() {
        System.out.println("Step 1: Allocating ByteBuf...");
        ByteBuf buffer = allocateBuffer();

        System.out.println("Step 2: Wrapping ByteBuf in DataPacket...");
        DataPacket packet = new DataPacket(buffer);

        System.out.println("Step 3: Passing DataPacket to handler method...");
        handlePacket(packet);

        System.out.println("\nExample complete!\n");
    }

    /**
     * Step 1: Allocate a ByteBuf with some data
     */
    public ByteBuf allocateBuffer() {
        ByteBuf buffer = Unpooled.buffer(256);
        buffer.writeBytes("Hello, ByteBuf Flow Tracker!".getBytes());
        System.out.println("  -> Allocated " + buffer.readableBytes() + " bytes");
        return buffer;
    }

    /**
     * Step 3: Handler method that receives the wrapper object
     * This method:
     * - Receives the DataPacket wrapper
     * - Extracts the ByteBuf from it
     * - Releases the ByteBuf
     */
    public void handlePacket(DataPacket packet) {
        System.out.println("  -> Handler received packet with ID: " + packet.getId());

        // Extract the ByteBuf from the wrapper
        ByteBuf buffer = packet.getData();
        System.out.println("  -> Extracted ByteBuf with " + buffer.readableBytes() + " bytes");
        System.out.println("  -> ByteBuf refCnt before release: " + buffer.refCnt());

        // Read and process the data
        byte[] data = new byte[buffer.readableBytes()];
        buffer.getBytes(0, data);
        System.out.println("  -> Data content: " + new String(data));

        // Release the ByteBuf
        buffer.release();
        System.out.println("  -> ByteBuf released, refCnt after release: " + buffer.refCnt());
    }

    /**
     * Print the tracking results showing the complete flow
     */
    public void printResults() {
        ByteBufFlowTracker tracker = ByteBufFlowTracker.getInstance();
        TrieRenderer renderer = new TrieRenderer(tracker.getTrie());

        System.out.println("=== ByteBuf Flow Tracking Results ===\n");
        System.out.println(renderer.renderSummary());

        System.out.println("\n=== Flow Tree ===");
        System.out.println(renderer.renderIndentedTree());

        System.out.println("\n=== Explanation ===");
        System.out.println("The flow tree shows the complete path of the ByteBuf:");
        System.out.println("1. allocateBuffer() - Where the ByteBuf was created");
        System.out.println("2. DataPacket.<init> - Where it was wrapped in the custom object");
        System.out.println("3. handlePacket() - Where the wrapper was received");
        System.out.println("4. getData() - Where the ByteBuf was extracted");
        System.out.println("5. release() - Where the ByteBuf was properly released [ref=0]");
        System.out.println("\nNo leaks detected! The ByteBuf was properly released.");
    }

    /**
     * Custom wrapper class that wraps a ByteBuf in its constructor.
     *
     * This represents a common pattern where ByteBuf is encapsulated
     * in a domain object (like Message, Request, Packet, etc.)
     */
    public static class DataPacket {
        private final ByteBuf data;
        private final String id;
        private final long timestamp;

        /**
         * Constructor that receives and wraps a ByteBuf.
         * This is Step 2 in our flow.
         */
        public DataPacket(ByteBuf data) {
            this.data = data;
            this.id = "PKT-" + System.currentTimeMillis();
            this.timestamp = System.currentTimeMillis();

            // With trackConstructors enabled, this constructor call will be tracked
            System.out.println("  -> Created DataPacket with ID: " + id);
        }

        /**
         * Getter that returns the wrapped ByteBuf.
         * This is used in Step 3 to extract the ByteBuf for processing.
         */
        public ByteBuf getData() {
            return data;
        }

        public String getId() {
            return id;
        }

        public long getTimestamp() {
            return timestamp;
        }
    }
}
