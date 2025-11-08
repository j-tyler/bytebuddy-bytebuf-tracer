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
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.buffer.Unpooled;
import io.netty.buffer.UnpooledByteBufAllocator;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Integration test for ByteBuf construction tracking.
 * Verifies that allocation sites (Unpooled.buffer, allocator.directBuffer, etc.)
 * become the root nodes in flow trees.
 *
 * This test demonstrates all major allocation types:
 * 1. Heap buffer (Unpooled.buffer)
 * 2. Direct buffer (Unpooled.directBuffer)
 * 3. Wrapped buffer (Unpooled.wrappedBuffer)
 * 4. Composite buffer (Unpooled.compositeBuffer)
 * 5. Allocator-based buffers (PooledByteBufAllocator, UnpooledByteBufAllocator)
 */
public class AllocationRootTrackingTest {

    private ByteBufFlowTracker tracker;

    @Before
    public void setup() {
        tracker = ByteBufFlowTracker.getInstance();
        tracker.reset();
    }

    @Test
    public void testAllAllocationTypes() {
        System.out.println("\n=== Testing All ByteBuf Allocation Types ===\n");

        // Test 1: Heap buffer allocation
        testHeapBufferAllocation();

        // Test 2: Direct buffer allocation
        testDirectBufferAllocation();

        // Test 3: Wrapped buffer allocation
        testWrappedBufferAllocation();

        // Test 4: Composite buffer allocation
        testCompositeBufferAllocation();

        // Test 5: Pooled allocator
        testPooledAllocator();

        // Test 6: Unpooled allocator
        testUnpooledAllocator();

        // Generate final report showing all allocation types
        TrieRenderer renderer = new TrieRenderer(tracker.getTrie());
        String tree = renderer.renderIndentedTree();
        String summary = renderer.renderSummary();

        System.out.println("\n=== Summary ===");
        System.out.println(summary);
        System.out.println("\n=== Complete Flow Tree ===");
        System.out.println(tree);

        // Verify that allocation methods appear as roots
        assertTrue("Should have Unpooled.buffer as root", tree.contains("ROOT: Unpooled.buffer"));
        assertTrue("Should have Unpooled.directBuffer as root", tree.contains("ROOT: Unpooled.directBuffer"));
        assertTrue("Should have Unpooled.wrappedBuffer as root", tree.contains("ROOT: Unpooled.wrappedBuffer"));
        assertTrue("Should have Unpooled.compositeBuffer as root", tree.contains("ROOT: Unpooled.compositeBuffer"));

        // Verify we tracked all the flows
        assertTrue("Should have multiple roots", summary.contains("Total Root Methods:"));

        System.out.println("\n✓ All allocation types successfully tracked as roots!");
    }

    private void testHeapBufferAllocation() {
        System.out.println("Test 1: Heap Buffer (Unpooled.buffer)");

        ByteBuf heapBuffer = Unpooled.buffer(256);
        tracker.recordMethodCall(heapBuffer, "Unpooled", "buffer", heapBuffer.refCnt());

        // Simulate application code using the buffer
        processBuffer(heapBuffer, "HeapProcessor");

        // Release
        heapBuffer.release();
        tracker.recordMethodCall(heapBuffer, "HeapProcessor", "cleanup", heapBuffer.refCnt());

        System.out.println("  ✓ Heap buffer tracked\n");
    }

    private void testDirectBufferAllocation() {
        System.out.println("Test 2: Direct Buffer (Unpooled.directBuffer)");

        ByteBuf directBuffer = Unpooled.directBuffer(256);
        tracker.recordMethodCall(directBuffer, "Unpooled", "directBuffer", directBuffer.refCnt());

        // Simulate application code using the buffer
        processBuffer(directBuffer, "DirectProcessor");

        // Release
        directBuffer.release();
        tracker.recordMethodCall(directBuffer, "DirectProcessor", "cleanup", directBuffer.refCnt());

        System.out.println("  ✓ Direct buffer tracked\n");
    }

    private void testWrappedBufferAllocation() {
        System.out.println("Test 3: Wrapped Buffer (Unpooled.wrappedBuffer)");

        byte[] data = "test data".getBytes();
        ByteBuf wrappedBuffer = Unpooled.wrappedBuffer(data);
        tracker.recordMethodCall(wrappedBuffer, "Unpooled", "wrappedBuffer", wrappedBuffer.refCnt());

        // Simulate application code using the buffer
        processBuffer(wrappedBuffer, "WrapperProcessor");

        // Release
        wrappedBuffer.release();
        tracker.recordMethodCall(wrappedBuffer, "WrapperProcessor", "cleanup", wrappedBuffer.refCnt());

        System.out.println("  ✓ Wrapped buffer tracked\n");
    }

    private void testCompositeBufferAllocation() {
        System.out.println("Test 4: Composite Buffer (Unpooled.compositeBuffer)");

        ByteBuf part1 = Unpooled.buffer(128);
        ByteBuf part2 = Unpooled.buffer(128);
        ByteBuf compositeBuffer = Unpooled.compositeBuffer()
            .addComponent(true, part1)
            .addComponent(true, part2);

        tracker.recordMethodCall(compositeBuffer, "Unpooled", "compositeBuffer", compositeBuffer.refCnt());

        // Simulate application code using the buffer
        processBuffer(compositeBuffer, "CompositeProcessor");

        // Release composite (this also releases components)
        compositeBuffer.release();
        tracker.recordMethodCall(compositeBuffer, "CompositeProcessor", "cleanup", compositeBuffer.refCnt());

        System.out.println("  ✓ Composite buffer tracked\n");
    }

    private void testPooledAllocator() {
        System.out.println("Test 5: Pooled Allocator (PooledByteBufAllocator)");

        ByteBufAllocator allocator = PooledByteBufAllocator.DEFAULT;
        ByteBuf pooledBuffer = allocator.buffer(256);
        tracker.recordMethodCall(pooledBuffer, "PooledByteBufAllocator", "buffer", pooledBuffer.refCnt());

        // Simulate application code using the buffer
        processBuffer(pooledBuffer, "PooledProcessor");

        // Release (returns to pool)
        pooledBuffer.release();
        tracker.recordMethodCall(pooledBuffer, "PooledProcessor", "cleanup", pooledBuffer.refCnt());

        System.out.println("  ✓ Pooled buffer tracked\n");
    }

    private void testUnpooledAllocator() {
        System.out.println("Test 6: Unpooled Allocator (UnpooledByteBufAllocator)");

        ByteBufAllocator allocator = UnpooledByteBufAllocator.DEFAULT;
        ByteBuf unpooledBuffer = allocator.buffer(256);
        tracker.recordMethodCall(unpooledBuffer, "UnpooledByteBufAllocator", "buffer", unpooledBuffer.refCnt());

        // Simulate application code using the buffer
        processBuffer(unpooledBuffer, "UnpooledProcessor");

        // Release
        unpooledBuffer.release();
        tracker.recordMethodCall(unpooledBuffer, "UnpooledProcessor", "cleanup", unpooledBuffer.refCnt());

        System.out.println("  ✓ Unpooled allocator buffer tracked\n");
    }

    /**
     * Helper method to simulate processing a ByteBuf through application code
     */
    private void processBuffer(ByteBuf buffer, String processorName) {
        // Simulate multiple processing steps
        tracker.recordMethodCall(buffer, processorName, "validate", buffer.refCnt());
        tracker.recordMethodCall(buffer, processorName, "process", buffer.refCnt());
    }

    @Test
    public void testAllocationAsRootImprovedLeakDetection() {
        System.out.println("\n=== Testing Improved Leak Detection with Allocation Roots ===\n");

        // Create a leak with a direct buffer (more serious than heap)
        ByteBuf leakyDirectBuffer = Unpooled.directBuffer(1024);
        tracker.recordMethodCall(leakyDirectBuffer, "Unpooled", "directBuffer", leakyDirectBuffer.refCnt());
        tracker.recordMethodCall(leakyDirectBuffer, "NetworkHandler", "handleRequest", leakyDirectBuffer.refCnt());
        tracker.recordMethodCall(leakyDirectBuffer, "NetworkHandler", "processData", leakyDirectBuffer.refCnt());
        // Oops! Forgot to release - this is a leak

        // Create a properly released heap buffer
        ByteBuf goodHeapBuffer = Unpooled.buffer(512);
        tracker.recordMethodCall(goodHeapBuffer, "Unpooled", "buffer", goodHeapBuffer.refCnt());
        tracker.recordMethodCall(goodHeapBuffer, "DataProcessor", "handle", goodHeapBuffer.refCnt());
        goodHeapBuffer.release();
        tracker.recordMethodCall(goodHeapBuffer, "DataProcessor", "handle", goodHeapBuffer.refCnt());

        // Generate report
        TrieRenderer renderer = new TrieRenderer(tracker.getTrie());
        String tree = renderer.renderIndentedTree();
        String llmView = renderer.renderForLLM();

        System.out.println("=== Tree View ===");
        System.out.println(tree);
        System.out.println("\n=== LLM View ===");
        System.out.println(llmView);

        // Verify leak detection
        assertTrue("Should show directBuffer root", tree.contains("ROOT: Unpooled.directBuffer"));
        assertTrue("Should show buffer root", tree.contains("ROOT: Unpooled.buffer"));
        assertTrue("Should detect leak", llmView.contains("LEAKS:"));
        assertTrue("Leak should be from directBuffer", llmView.contains("Unpooled.directBuffer"));

        System.out.println("\n✓ Allocation roots improve leak diagnosis!");
        System.out.println("  - We can immediately see the leak is a DIRECT buffer (off-heap)");
        System.out.println("  - This is more serious than a heap buffer leak");
    }

    @Test
    public void testDeterministicRoots() {
        System.out.println("\n=== Testing Deterministic Roots ===\n");

        // Create multiple buffers using the same allocation method
        for (int i = 0; i < 5; i++) {
            ByteBuf buffer = Unpooled.buffer(128);
            tracker.recordMethodCall(buffer, "Unpooled", "buffer", buffer.refCnt());

            // Different application paths
            if (i % 2 == 0) {
                tracker.recordMethodCall(buffer, "PathA", "process", buffer.refCnt());
            } else {
                tracker.recordMethodCall(buffer, "PathB", "process", buffer.refCnt());
            }

            buffer.release();
            tracker.recordMethodCall(buffer, i % 2 == 0 ? "PathA" : "PathB", "cleanup", buffer.refCnt());
        }

        TrieRenderer renderer = new TrieRenderer(tracker.getTrie());
        String tree = renderer.renderIndentedTree();

        System.out.println(tree);

        // All buffers should share the same root: Unpooled.buffer
        assertTrue("Should have single Unpooled.buffer root", tree.contains("ROOT: Unpooled.buffer [count=5]"));

        System.out.println("\n✓ Roots are deterministic!");
        System.out.println("  - All buffers from Unpooled.buffer share the same root");
        System.out.println("  - Count shows 5 buffers went through this allocation path");
    }
}
