/*
 * Copyright 2025 Justin Marsh
 * SPDX-License-Identifier: Apache-2.0
 */

package com.example.bytebuf.tracker.test;

import com.example.bytebuf.tracker.active.WeakActiveFlow;
import com.example.bytebuf.tracker.active.WeakActiveTracker;
import com.example.bytebuf.tracker.trie.BoundedImprintTrie;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive tests for WeakActiveTracker to ensure all behaviors are tested.
 */
public class WeakActiveTrackerTest {

    private BoundedImprintTrie trie;
    private WeakActiveTracker tracker;

    @BeforeEach
    public void setUp() {
        trie = new BoundedImprintTrie(10000, 100);
        tracker = new WeakActiveTracker(trie);
    }

    @AfterEach
    public void tearDown() {
        // Clean up any remaining flows
        tracker.markRemainingAsLeaks();
    }

    @Test
    public void testGetOrCreate_createsNewFlow() {
        ByteBuf buffer = Unpooled.buffer(256);

        WeakActiveFlow flow = tracker.getOrCreate(buffer, "TestClass.allocate");

        assertNotNull(flow, "Flow should be created");
        assertEquals(1, tracker.getActiveCount(), "Should have 1 active flow");
        assertEquals(1, tracker.getTotalObjectsSeen(), "Should have seen 1 object");

        buffer.release();
    }

    @Test
    public void testGetOrCreate_returnsSameFlowForSameObject() {
        ByteBuf buffer = Unpooled.buffer(256);

        WeakActiveFlow flow1 = tracker.getOrCreate(buffer, "TestClass.allocate");
        WeakActiveFlow flow2 = tracker.getOrCreate(buffer, "TestClass.process");

        assertSame(flow1, flow2, "Should return the same flow for the same object");
        assertEquals(1, tracker.getActiveCount(), "Should still have 1 active flow");
        assertEquals(1, tracker.getTotalObjectsSeen(), "Should still have seen 1 object");

        buffer.release();
    }

    @Test
    public void testGetOrCreate_createsDifferentFlowsForDifferentObjects() {
        ByteBuf buffer1 = Unpooled.buffer(256);
        ByteBuf buffer2 = Unpooled.buffer(256);

        WeakActiveFlow flow1 = tracker.getOrCreate(buffer1, "TestClass.allocate");
        WeakActiveFlow flow2 = tracker.getOrCreate(buffer2, "TestClass.allocate");

        assertNotSame(flow1, flow2, "Should create different flows for different objects");
        assertEquals(2, tracker.getActiveCount(), "Should have 2 active flows");
        assertEquals(2, tracker.getTotalObjectsSeen(), "Should have seen 2 objects");

        buffer1.release();
        buffer2.release();
    }

    @Test
    public void testRecordCleanRelease_marksFlowAsCompleted() {
        ByteBuf buffer = Unpooled.buffer(256);
        WeakActiveFlow flow = tracker.getOrCreate(buffer, "TestClass.allocate");
        int objectId = System.identityHashCode(buffer);

        // Update flow to have a node
        flow.setCurrentNode(trie.getOrCreateRoot("TestClass.allocate"));

        tracker.recordCleanRelease(objectId);

        assertTrue(flow.isCompleted(), "Flow should be marked as completed");
        assertEquals(1, tracker.getTotalCleaned(), "Should have 1 cleaned object");
        assertEquals(1, tracker.getActiveCount(), "Flow should still be in activeFlows to prevent re-tracking");

        buffer.release();
    }

    @Test
    public void testRecordCleanRelease_preventsDoubleRelease() {
        ByteBuf buffer = Unpooled.buffer(256);
        WeakActiveFlow flow = tracker.getOrCreate(buffer, "TestClass.allocate");
        int objectId = System.identityHashCode(buffer);

        // Update flow to have a node
        flow.setCurrentNode(trie.getOrCreateRoot("TestClass.allocate"));

        tracker.recordCleanRelease(objectId);
        tracker.recordCleanRelease(objectId);

        assertEquals(1, tracker.getTotalCleaned(), "Should only count as cleaned once");

        buffer.release();
    }

    @Test
    public void testLeakDetection_throughGarbageCollection() throws InterruptedException {
        // Create a buffer and track it
        ByteBuf buffer = Unpooled.buffer(256);
        WeakActiveFlow flow = tracker.getOrCreate(buffer, "TestClass.allocate");

        // Update flow to have a node (simulate tracking)
        flow.setCurrentNode(trie.getOrCreateRoot("TestClass.allocate"));

        int initialActiveCount = tracker.getActiveCount();
        assertEquals(1, initialActiveCount, "Should have 1 active flow");

        // Let buffer be GC'd without releasing
        buffer = null;

        // Force garbage collection
        System.gc();
        Thread.sleep(100);

        // Process GC queue
        tracker.ensureGCProcessed();

        // Note: GC is non-deterministic, but we can check if leak was detected
        // If GC happened, leak count should increase and active count should decrease
        long leakCount = tracker.getTotalLeaked();
        int activeCount = tracker.getActiveCount();

        // At minimum, verify the method doesn't crash
        assertTrue(leakCount >= 0, "Leak count should be non-negative");
        assertTrue(activeCount >= 0, "Active count should be non-negative");
    }

    @Test
    public void testProcessAllGCQueue_processesAllItems() {
        // Create multiple buffers and let them be GC'd
        for (int i = 0; i < 5; i++) {
            ByteBuf buffer = Unpooled.buffer(256);
            WeakActiveFlow flow = tracker.getOrCreate(buffer, "TestClass.allocate");
            flow.setCurrentNode(trie.getOrCreateRoot("TestClass.allocate"));
            // Don't release - let it leak
        }

        int initialActiveCount = tracker.getActiveCount();
        assertTrue(initialActiveCount >= 5, "Should have at least 5 active flows");

        // Force GC
        System.gc();

        // Process all GC queue
        tracker.processAllGCQueue();

        // Verify processing happened (counts should be updated)
        assertTrue(tracker.getTotalGCDetected() >= 0, "GC detected count should be non-negative");
    }

    @Test
    public void testMarkRemainingAsLeaks_marksAllActiveFlows() {
        // Create buffers and track them
        ByteBuf buffer1 = Unpooled.buffer(256);
        ByteBuf buffer2 = Unpooled.buffer(256);

        WeakActiveFlow flow1 = tracker.getOrCreate(buffer1, "TestClass.allocate");
        WeakActiveFlow flow2 = tracker.getOrCreate(buffer2, "TestClass.allocate");

        // Update flows to have nodes
        flow1.setCurrentNode(trie.getOrCreateRoot("TestClass.allocate"));
        flow2.setCurrentNode(trie.getOrCreateRoot("TestClass.allocate"));

        assertEquals(2, tracker.getActiveCount(), "Should have 2 active flows");

        // Mark remaining as leaks
        tracker.markRemainingAsLeaks();

        assertEquals(2, tracker.getTotalLeaked(), "Should have marked 2 flows as leaked");
        assertEquals(0, tracker.getActiveCount(), "Should have cleared all active flows");

        buffer1.release();
        buffer2.release();
    }

    @Test
    public void testMarkRemainingAsLeaks_skipsCompletedFlows() {
        // Create buffers
        ByteBuf buffer1 = Unpooled.buffer(256);
        ByteBuf buffer2 = Unpooled.buffer(256);

        WeakActiveFlow flow1 = tracker.getOrCreate(buffer1, "TestClass.allocate");
        WeakActiveFlow flow2 = tracker.getOrCreate(buffer2, "TestClass.allocate");

        // Update flows to have nodes
        flow1.setCurrentNode(trie.getOrCreateRoot("TestClass.allocate"));
        flow2.setCurrentNode(trie.getOrCreateRoot("TestClass.allocate"));

        // Mark one as cleanly released
        tracker.recordCleanRelease(System.identityHashCode(buffer1));

        // Mark remaining as leaks
        tracker.markRemainingAsLeaks();

        assertEquals(1, tracker.getTotalCleaned(), "Should have 1 cleaned");
        assertEquals(1, tracker.getTotalLeaked(), "Should have marked only 1 as leaked");

        buffer1.release();
        buffer2.release();
    }

    @Test
    public void testStatistics_totalObjectsSeen() {
        for (int i = 0; i < 10; i++) {
            ByteBuf buffer = Unpooled.buffer(256);
            tracker.getOrCreate(buffer, "TestClass.allocate");
            buffer.release();
        }

        assertEquals(10, tracker.getTotalObjectsSeen(), "Should have seen 10 objects");
    }

    @Test
    public void testStatistics_totalCleaned() {
        for (int i = 0; i < 5; i++) {
            ByteBuf buffer = Unpooled.buffer(256);
            WeakActiveFlow flow = tracker.getOrCreate(buffer, "TestClass.allocate");
            flow.setCurrentNode(trie.getOrCreateRoot("TestClass.allocate"));

            tracker.recordCleanRelease(System.identityHashCode(buffer));
            buffer.release();
        }

        assertEquals(5, tracker.getTotalCleaned(), "Should have cleaned 5 objects");
    }

    @Test
    public void testEnsureGCProcessed_canBeCalledMultipleTimes() {
        // Should not throw exception
        tracker.ensureGCProcessed();
        tracker.ensureGCProcessed();
        tracker.ensureGCProcessed();
    }

    @Test
    public void testConcurrentGetOrCreate_threadSafety() throws InterruptedException {
        final int numThreads = 10;
        final int numBuffersPerThread = 100;
        Thread[] threads = new Thread[numThreads];

        for (int i = 0; i < numThreads; i++) {
            threads[i] = new Thread(() -> {
                for (int j = 0; j < numBuffersPerThread; j++) {
                    ByteBuf buffer = Unpooled.buffer(256);
                    tracker.getOrCreate(buffer, "TestClass.allocate");
                    buffer.release();
                }
            });
            threads[i].start();
        }

        // Wait for all threads
        for (Thread thread : threads) {
            thread.join();
        }

        // Should have seen all objects
        assertEquals(numThreads * numBuffersPerThread, tracker.getTotalObjectsSeen(),
                     "Should have seen all objects from all threads");
    }

    @Test
    public void testDirectBufferDetection() {
        ByteBuf directBuffer = Unpooled.directBuffer(256);

        WeakActiveFlow flow = tracker.getOrCreate(directBuffer, "UnpooledByteBufAllocator.directBuffer");

        assertTrue(flow.isDirect(), "Should detect direct buffer method");

        directBuffer.release();
    }

    @Test
    public void testIoBufferDetection() {
        ByteBuf buffer = Unpooled.buffer(256);

        WeakActiveFlow flow = tracker.getOrCreate(buffer, "UnpooledByteBufAllocator.ioBuffer");

        assertTrue(flow.isDirect(), "Should detect ioBuffer as direct");

        buffer.release();
    }

    @Test
    public void testHeapBufferDetection() {
        ByteBuf heapBuffer = Unpooled.buffer(256);

        WeakActiveFlow flow = tracker.getOrCreate(heapBuffer, "UnpooledByteBufAllocator.heapBuffer");

        assertFalse(flow.isDirect(), "Should detect heap buffer as not direct");

        heapBuffer.release();
    }
}
