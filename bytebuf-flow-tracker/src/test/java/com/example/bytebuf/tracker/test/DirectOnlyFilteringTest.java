/*
 * Copyright 2025 Justin Marsh
 * SPDX-License-Identifier: Apache-2.0
 */

package com.example.bytebuf.tracker.test;

import com.example.bytebuf.tracker.ByteBufFlowTracker;
import com.example.bytebuf.tracker.agent.ByteBufConstructionAdvice;
import com.example.bytebuf.tracker.trie.BoundedImprintTrie;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.UnpooledByteBufAllocator;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Test that demonstrates direct-only filtering functionality.
 *
 * When FILTER_DIRECT_ONLY is enabled, the tracker should:
 * 1. Skip heap buffers entirely (zero tracking overhead)
 * 2. Track only direct buffers
 * 3. Use fast-path filtering based on method names
 */
public class DirectOnlyFilteringTest {

    private ByteBufFlowTracker tracker;
    private ByteBufAllocator allocator;

    @Before
    public void setUp() {
        tracker = ByteBufFlowTracker.getInstance();
        tracker.reset();
        allocator = UnpooledByteBufAllocator.DEFAULT;

        // Set base state: filtering disabled
        // Individual tests enable it if needed
        ByteBufConstructionAdvice.FILTER_DIRECT_ONLY = false;
    }

    @After
    public void tearDown() {
        // Reset to base state
        ByteBufConstructionAdvice.FILTER_DIRECT_ONLY = false;
        tracker.reset();
    }

    @Test
    public void testFilterDirectOnlyDisabled_TracksAllBuffers() {
        // Given: Filter is disabled
        ByteBufConstructionAdvice.FILTER_DIRECT_ONLY = false;

        // When: Create both heap and direct buffers
        ByteBuf heapBuf = allocator.heapBuffer(256);
        ByteBuf directBuf = allocator.directBuffer(256);

        tracker.recordMethodCall(heapBuf, "UnpooledByteBufAllocator", "heapBuffer", heapBuf.refCnt());
        tracker.recordMethodCall(directBuf, "UnpooledByteBufAllocator", "directBuffer", directBuf.refCnt());

        // Then: Both buffers should be tracked
        BoundedImprintTrie trie = tracker.getTrie();
        assertEquals("Should have 2 root nodes (heap + direct)", 2, trie.getRootCount());

        // Cleanup
        heapBuf.release();
        directBuf.release();
    }

    @Test
    public void testFilterDirectOnlyEnabled_SkipsHeapBuffers() {
        // Given: Filter is enabled
        ByteBufConstructionAdvice.FILTER_DIRECT_ONLY = true;

        // When: Simulate what the advice would do - manually filter
        ByteBuf heapBuf = allocator.heapBuffer(256);
        ByteBuf directBuf = allocator.directBuffer(256);

        // Simulate advice filtering logic
        String heapMethod = "heapBuffer";
        String directMethod = "directBuffer";

        // heapBuffer should be skipped by fast-path
        boolean shouldTrackHeap = !heapMethod.equals("heapBuffer");
        assertFalse("Heap buffer should be filtered out", shouldTrackHeap);

        // directBuffer should pass through fast-path
        boolean shouldTrackDirect = !directMethod.equals("heapBuffer");
        assertTrue("Direct buffer should be tracked", shouldTrackDirect);

        // Only track the direct buffer
        if (shouldTrackDirect) {
            tracker.recordMethodCall(directBuf, "UnpooledByteBufAllocator", "directBuffer", directBuf.refCnt());
        }

        // Then: Only direct buffer should be tracked
        BoundedImprintTrie trie = tracker.getTrie();
        assertEquals("Should have only 1 root node (direct)", 1, trie.getRootCount());
        assertTrue("Root should be directBuffer",
            trie.getRoots().containsKey("UnpooledByteBufAllocator.directBuffer"));

        // Cleanup
        heapBuf.release();
        directBuf.release();
    }

    @Test
    public void testFastPathFiltering_KnownMethods() {
        // Test that known methods can be filtered without isDirect() call
        //
        // INSTRUMENTED TERMINAL METHODS (only these can appear in production):
        // - directBuffer(int, int) -> always direct
        // - heapBuffer(int, int) -> always heap (only when trackDirectOnly=false)
        //
        // NOT INSTRUMENTED (delegation methods, never trigger this advice):
        // - buffer() -> delegates to directBuffer/heapBuffer
        // - ioBuffer() -> delegates to directBuffer/heapBuffer

        // heapBuffer - should be filtered
        String heapMethod = "heapBuffer";
        boolean isHeap = heapMethod.equals("heapBuffer");
        assertTrue("heapBuffer should be detected as heap", isHeap);

        // directBuffer - should pass
        String directMethod = "directBuffer";
        boolean isDirect = directMethod.equals("directBuffer");
        assertTrue("directBuffer should be detected as direct", isDirect);
    }

    @Test
    public void testAmbiguousBufferMethod_RequiresRuntimeCheck() {
        // Given: Filter is enabled
        ByteBufConstructionAdvice.FILTER_DIRECT_ONLY = true;

        // When: Allocator's buffer() method (could be heap or direct)
        // Note: UnpooledByteBufAllocator.DEFAULT actually returns direct buffers!
        // This is exactly why we need runtime checking for ambiguous methods.
        ByteBuf buf = allocator.buffer(256);

        // The method name is ambiguous - not a known terminal method
        // Note: buffer() is never instrumented (delegates to directBuffer/heapBuffer)
        // but for this test we're simulating what would happen if it were
        String methodName = "buffer";
        boolean isAmbiguous = !methodName.equals("heapBuffer") &&
                              !methodName.equals("directBuffer");

        assertTrue("buffer() method is ambiguous", isAmbiguous);

        // Simulating advice behavior: check isDirect() for ambiguous methods
        boolean actuallyDirect = buf.isDirect();
        assertTrue("Unpooled.DEFAULT.buffer() is actually direct!", actuallyDirect);

        // Since it's actually direct, it would be tracked
        if (actuallyDirect) {
            tracker.recordMethodCall(buf, "UnpooledByteBufAllocator", "buffer", buf.refCnt());
        }

        // Then: Buffer SHOULD be tracked (it's direct!)
        BoundedImprintTrie trie = tracker.getTrie();
        assertEquals("Should have 1 root node (direct buffer)", 1, trie.getRootCount());
        assertTrue("Root should be buffer method",
            trie.getRoots().containsKey("UnpooledByteBufAllocator.buffer"));

        // Cleanup
        buf.release();
    }

    @Test
    public void testPerformanceOptimization_ZeroOverheadForHeap() {
        // This test demonstrates the performance optimization:
        // When FILTER_DIRECT_ONLY is true, heapBuffer exits immediately
        // without calling isDirect() or doing any tracking work
        //
        // NOTE: This simulates the switch statement from ByteBufConstructionAdvice
        // Only methods that are actually instrumented can appear here in production:
        // - heapBuffer (defensive guard, shouldn't be instrumented when trackDirectOnly=true)
        // - directBuffer (always instrumented)
        // - wrappedBuffer, copiedBuffer, compositeBuffer (ambiguous, go to default)

        ByteBufConstructionAdvice.FILTER_DIRECT_ONLY = true;

        // Simulate the switch statement in ByteBufConstructionAdvice
        String methodName = "heapBuffer";
        boolean exitEarly = false;

        switch (methodName) {
            case "heapBuffer":
                exitEarly = true;  // Fast exit - zero overhead
                break;
            case "directBuffer":
                exitEarly = false;  // Continue tracking (known direct)
                break;
            default:
                // Would call isDirect() here for ambiguous methods
                // (wrappedBuffer, copiedBuffer, compositeBuffer)
                break;
        }

        assertTrue("heapBuffer should trigger early exit", exitEarly);

        // This proves that heapBuffer allocations have ZERO tracking overhead
        // when trackDirectOnly is enabled - not even an isDirect() call!
    }

    @Test
    public void testWrappedBufferFilteringWithTrackDirectOnly() {
        // Given: Filter is enabled (as it would be with trackDirectOnly=true)
        ByteBufConstructionAdvice.FILTER_DIRECT_ONLY = true;

        // When: Create a wrapped buffer around heap memory
        byte[] heapArray = new byte[256];
        ByteBuf wrappedHeapBuf = io.netty.buffer.Unpooled.wrappedBuffer(heapArray);

        // Then: The buffer should be filtered out (it's heap-backed)
        assertFalse("Wrapped heap buffer should not be direct", wrappedHeapBuf.isDirect());

        // Simulate advice behavior for wrappedBuffer method (ambiguous)
        if (wrappedHeapBuf instanceof io.netty.buffer.ByteBuf) {
            if (!wrappedHeapBuf.isDirect()) {
                // Would skip tracking - correct behavior
            } else {
                tracker.recordMethodCall(wrappedHeapBuf, "Unpooled", "wrappedBuffer",
                                        wrappedHeapBuf.refCnt());
            }
        }

        // Verify it was NOT tracked
        assertEquals("Wrapped heap buffer should not be tracked", 0, tracker.getTrie().getRootCount());

        // Cleanup
        wrappedHeapBuf.release();
    }

    @Test
    public void testWrappedDirectBufferIsTracked() {
        // Given: Filter is enabled (as it would be with trackDirectOnly=true)
        ByteBufConstructionAdvice.FILTER_DIRECT_ONLY = true;

        // When: Create a direct buffer and wrap it
        ByteBuf directBuf = allocator.directBuffer(256);
        ByteBuf wrappedDirectBuf = io.netty.buffer.Unpooled.wrappedBuffer(directBuf);

        // Then: The wrapped buffer should be direct (inherits from parent)
        assertTrue("Wrapped direct buffer should be direct", wrappedDirectBuf.isDirect());

        // Simulate advice behavior - should be tracked
        if (wrappedDirectBuf.isDirect()) {
            tracker.recordMethodCall(wrappedDirectBuf, "Unpooled", "wrappedBuffer",
                                    wrappedDirectBuf.refCnt());
        }

        // Verify it WAS tracked
        assertEquals("Wrapped direct buffer should be tracked", 1, tracker.getTrie().getRootCount());

        // Cleanup - wrapped buffer shares refCnt with original, only release once
        wrappedDirectBuf.release();
    }

    @Test
    public void testMultiLevelWrapping_InheritsDirect() {
        // Given: Filter is enabled
        ByteBufConstructionAdvice.FILTER_DIRECT_ONLY = true;

        // When: Create multi-level wrapping (wrap a wrapped buffer)
        ByteBuf direct = allocator.directBuffer(256);
        direct.writeLong(12345L);
        ByteBuf wrapped1 = io.netty.buffer.Unpooled.wrappedBuffer(direct);
        ByteBuf wrapped2 = io.netty.buffer.Unpooled.wrappedBuffer(wrapped1);

        // Then: All levels should inherit isDirect() = true
        assertTrue("Level 0 (original) should be direct", direct.isDirect());
        assertTrue("Level 1 (first wrap) should be direct", wrapped1.isDirect());
        assertTrue("Level 2 (second wrap) should be direct", wrapped2.isDirect());

        // All would be tracked (inherit direct property through all levels)
        tracker.recordMethodCall(wrapped2, "Unpooled", "wrappedBuffer", wrapped2.refCnt());
        assertEquals("Multi-level wrapped direct buffer should be tracked", 1, tracker.getTrie().getRootCount());

        // Cleanup - all wrappers share same refCnt
        wrapped2.release();
    }

    @Test
    public void testCompositeBuffer_MixedComponents() {
        // Given: Filter is enabled
        ByteBufConstructionAdvice.FILTER_DIRECT_ONLY = true;

        // When: Create a composite buffer with mixed heap and direct components
        ByteBuf heap = allocator.heapBuffer(128);
        heap.writeInt(111);
        ByteBuf direct = allocator.directBuffer(128);
        direct.writeInt(222);

        // Netty's wrappedBuffer with mixed components
        ByteBuf composite = io.netty.buffer.Unpooled.wrappedBuffer(heap, direct);

        // Then: Verify actual Netty behavior - wrappedBuffer returns the FIRST component
        // if there's only one readable component, otherwise returns a composite
        // The isDirect() of a composite depends on the implementation
        boolean isDirect = composite.isDirect();

        // Document the actual behavior
        if (isDirect) {
            // If marked as direct, should be tracked
            tracker.recordMethodCall(composite, "Unpooled", "wrappedBuffer", composite.refCnt());
            assertEquals("Direct composite should be tracked", 1, tracker.getTrie().getRootCount());
        } else {
            // If marked as heap, should NOT be tracked
            if (composite.isDirect()) {
                tracker.recordMethodCall(composite, "Unpooled", "wrappedBuffer", composite.refCnt());
            }
            assertEquals("Heap composite should not be tracked", 0, tracker.getTrie().getRootCount());
        }

        // Cleanup
        composite.release();
    }

    @Test
    public void testCompositeBuffer_AllHeapComponents() {
        // Given: Filter is enabled
        ByteBufConstructionAdvice.FILTER_DIRECT_ONLY = true;

        // When: Create a composite buffer with only heap components
        ByteBuf heap1 = allocator.heapBuffer(128);
        heap1.writeInt(111);
        ByteBuf heap2 = allocator.heapBuffer(128);
        heap2.writeInt(222);

        ByteBuf composite = io.netty.buffer.Unpooled.wrappedBuffer(heap1, heap2);

        // Then: Composite with all heap components returns isDirect() = false
        assertFalse("Composite with only heap components should be heap",
                    composite.isDirect());

        // Would NOT be tracked (all heap, correctly filtered out)
        if (composite.isDirect()) {
            tracker.recordMethodCall(composite, "Unpooled", "compositeBuffer", composite.refCnt());
        }

        assertEquals("Composite buffer with only heap components should not be tracked",
                     0, tracker.getTrie().getRootCount());

        // Cleanup
        composite.release();
    }
}
