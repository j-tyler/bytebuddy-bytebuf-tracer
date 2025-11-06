package com.example.demo;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import com.example.bytebuf.tracker.ByteBufFlowTracker;
import com.example.bytebuf.tracker.view.TrieRenderer;

/**
 * Example demonstrating ByteBuf tracking through static methods.
 *
 * Prior to the fix, static methods were excluded from instrumentation,
 * making it impossible to track ByteBuf flow through static utility methods.
 *
 * After the fix, both instance and static methods are tracked, providing
 * complete visibility into ByteBuf lifecycle.
 */
public class StaticMethodExample {

    public static void main(String[] args) {
        System.out.println("=== Static Method Tracking Example ===\n");

        // Example 1: ByteBuf passed to static utility method
        ByteBuf buffer1 = Unpooled.buffer(256);
        buffer1.writeBytes("Hello Static World".getBytes());
        processWithStaticMethod(buffer1);
        buffer1.release();

        // Example 2: ByteBuf processed by mix of instance and static methods
        StaticMethodExample example = new StaticMethodExample();
        ByteBuf buffer2 = Unpooled.buffer(256);
        buffer2.writeBytes("Mixed Processing".getBytes());
        example.processWithInstanceMethod(buffer2);
        processWithStaticMethod(buffer2);
        buffer2.release();

        // Example 3: Static factory pattern
        ByteBuf buffer3 = createAndInitialize("Factory Pattern");
        processData(buffer3);
        buffer3.release();

        // Print tracking results
        ByteBufFlowTracker tracker = ByteBufFlowTracker.getInstance();
        TrieRenderer renderer = new TrieRenderer(tracker.getTrie());

        System.out.println("\n=== ByteBuf Flow Summary ===");
        System.out.println(renderer.renderSummary());

        System.out.println("\n=== Flow Tree ===");
        System.out.println(renderer.renderIndentedTree());

        System.out.println("\n=== Flat Paths (shows leaks) ===");
        System.out.println(renderer.renderFlatPaths());
    }

    /**
     * Static utility method that processes ByteBuf.
     * This would NOT be tracked before the fix.
     */
    public static void processWithStaticMethod(ByteBuf buffer) {
        // Static method processing
        int readableBytes = buffer.readableBytes();
        System.out.println("Static method processing " + readableBytes + " bytes");
    }

    /**
     * Instance method for comparison.
     * This was always tracked.
     */
    public void processWithInstanceMethod(ByteBuf buffer) {
        int capacity = buffer.capacity();
        System.out.println("Instance method processing buffer with capacity " + capacity);
    }

    /**
     * Static factory method - common pattern in ByteBuf usage.
     * Would NOT be tracked before the fix.
     */
    public static ByteBuf createAndInitialize(String content) {
        ByteBuf buffer = Unpooled.buffer(256);
        buffer.writeBytes(content.getBytes());
        return buffer;
    }

    /**
     * Static helper method for data processing.
     * Would NOT be tracked before the fix.
     */
    public static void processData(ByteBuf buffer) {
        byte[] data = new byte[buffer.readableBytes()];
        buffer.getBytes(0, data);
        System.out.println("Processed data: " + new String(data));
    }

    /**
     * Example of static wrapper detection.
     * Many ByteBuf implementations are wrapped, and static methods
     * need tracking too.
     */
    public static ByteBuf wrapBuffer(byte[] data) {
        return Unpooled.wrappedBuffer(data);
    }

    /**
     * Static cleanup pattern - also needs tracking.
     */
    public static void releaseQuietly(ByteBuf buffer) {
        if (buffer != null && buffer.refCnt() > 0) {
            buffer.release();
        }
    }
}
