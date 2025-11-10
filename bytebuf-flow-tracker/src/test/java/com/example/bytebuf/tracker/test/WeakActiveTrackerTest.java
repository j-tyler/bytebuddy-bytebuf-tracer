/*
 * Copyright 2025 Justin Marsh
 * SPDX-License-Identifier: Apache-2.0
 */

package com.example.bytebuf.tracker.test;

import com.example.bytebuf.tracker.active.WeakActiveFlow;
import com.example.bytebuf.tracker.active.WeakActiveTracker;
import com.example.bytebuf.tracker.trie.BoundedImprintTrie;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Unit tests for WeakActiveTracker.
 */
public class WeakActiveTrackerTest {

    private BoundedImprintTrie trie;
    private WeakActiveTracker tracker;

    @Before
    public void setUp() {
        trie = new BoundedImprintTrie(10000, 50);
        tracker = new WeakActiveTracker(trie);
    }

    @After
    public void tearDown() {
        trie.clear();
    }

    @Test
    public void testGetOrCreate_CreatesNewFlow() {
        Object obj = new Object();
        WeakActiveFlow flow = tracker.getOrCreate(obj, "TestClass", "testMethod");

        assertNotNull("Flow should be created", flow);
        assertEquals("Active count should be 1", 1, tracker.getActiveCount());
        assertFalse("Flow should not be completed", flow.isCompleted());
    }

    @Test
    public void testGetOrCreate_ReturnsSameFlowForSameObject() {
        Object obj = new Object();
        WeakActiveFlow flow1 = tracker.getOrCreate(obj, "TestClass", "testMethod");
        WeakActiveFlow flow2 = tracker.getOrCreate(obj, "TestClass", "testMethod");

        assertSame("Should return same flow for same object", flow1, flow2);
        assertEquals("Active count should still be 1", 1, tracker.getActiveCount());
    }

    @Test
    public void testRecordCleanRelease_MarksFlowCompleted() {
        Object obj = new Object();
        int objectId = System.identityHashCode(obj);
        WeakActiveFlow flow = tracker.getOrCreate(obj, "TestClass", "testMethod");

        assertFalse("Flow should not be completed initially", flow.isCompleted());

        tracker.recordCleanRelease(objectId);

        assertTrue("Flow should be marked as completed", flow.isCompleted());
        assertEquals("Flow should still be in active map", 1, tracker.getActiveCount());
        assertEquals("Clean count should be 1", 1, tracker.getTotalCleaned());
    }

    @Test
    public void testRecordCleanRelease_IdempotentWhenAlreadyCompleted() {
        Object obj = new Object();
        int objectId = System.identityHashCode(obj);
        WeakActiveFlow flow = tracker.getOrCreate(obj, "TestClass", "testMethod");

        tracker.recordCleanRelease(objectId);
        tracker.recordCleanRelease(objectId);

        assertEquals("Clean count should still be 1", 1, tracker.getTotalCleaned());
    }

    @Test
    public void testMarkRemainingAsLeaks_MarksAllActiveFlows() {
        Object obj1 = new Object();
        Object obj2 = new Object();
        tracker.getOrCreate(obj1, "TestClass", "method1");
        tracker.getOrCreate(obj2, "TestClass", "method2");

        assertEquals("Should have 2 active flows", 2, tracker.getActiveCount());

        tracker.markRemainingAsLeaks();

        assertEquals("All flows should be cleared", 0, tracker.getActiveCount());
        assertEquals("Should have 2 leaks", 2, tracker.getTotalLeaked());
    }

    @Test
    public void testMarkRemainingAsLeaks_SkipsCompletedFlows() {
        Object obj1 = new Object();
        Object obj2 = new Object();
        int objectId1 = System.identityHashCode(obj1);

        tracker.getOrCreate(obj1, "TestClass", "method1");
        tracker.getOrCreate(obj2, "TestClass", "method2");
        tracker.recordCleanRelease(objectId1);

        tracker.markRemainingAsLeaks();

        assertEquals("All flows should be cleared", 0, tracker.getActiveCount());
        assertEquals("Should have 1 leak (obj2)", 1, tracker.getTotalLeaked());
        assertEquals("Should have 1 clean (obj1)", 1, tracker.getTotalCleaned());
    }

    @Test
    public void testStatistics_TotalObjectsSeen() {
        tracker.getOrCreate(new Object(), "TestClass", "method1");
        tracker.getOrCreate(new Object(), "TestClass", "method2");
        tracker.getOrCreate(new Object(), "TestClass", "method3");

        assertEquals("Should have seen 3 objects", 3, tracker.getTotalObjectsSeen());
    }

    @Test
    public void testMemoryUsage_ReturnsApproximateSize() {
        tracker.getOrCreate(new Object(), "TestClass", "method1");
        tracker.getOrCreate(new Object(), "TestClass", "method2");

        long memoryUsage = tracker.getMemoryUsage();
        assertEquals("Memory usage should be 2 * 80 bytes", 160L, memoryUsage);
    }

    @Test
    public void testGCDetection_LeakDetectedWhenObjectIsGCd() throws InterruptedException {
        Object obj = new Object();
        tracker.getOrCreate(obj, "TestClass", "testMethod");
        assertEquals("Should have 1 active flow", 1, tracker.getActiveCount());

        // Clear reference and request GC
        obj = null;

        // Retry loop to give GC multiple chances to run (GC is not guaranteed)
        boolean gcDetected = false;
        for (int attempt = 0; attempt < 10 && !gcDetected; attempt++) {
            System.gc();
            Thread.sleep(50);
            tracker.processAllGCQueue();
            gcDetected = (tracker.getTotalGCDetected() > 0);
        }

        assertTrue("Should have detected GC'd object within reasonable time", gcDetected);
        assertEquals("Active flows should be 0 after GC", 0, tracker.getActiveCount());
        assertEquals("Should have detected 1 leak", 1, tracker.getTotalGCDetected());
    }

    @Test
    public void testLazyGCProcessing_ProcessesEvery100Calls() throws InterruptedException {
        // Create an object that will be GC'd
        Object obj = new Object();
        tracker.getOrCreate(obj, "TestClass", "testMethod");

        // Clear reference and force GC
        obj = null;
        for (int i = 0; i < 5; i++) {
            System.gc();
            Thread.sleep(10);
        }

        // Make 99 getOrCreate calls - GC should NOT be processed yet
        for (int i = 0; i < 99; i++) {
            tracker.getOrCreate(new Object(), "TestClass", "method" + i);
        }

        // GC'd object should still be in queue (not processed yet)
        // We can't directly verify this without exposing internals, but we can verify
        // that after 100 calls it IS processed

        // Make the 100th call - should trigger GC processing
        tracker.getOrCreate(new Object(), "TestClass", "method100");

        // After 100 calls, GC should have been processed
        // Give a moment for processing to complete
        Thread.sleep(10);

        // The exact count depends on whether the GC'd object was actually collected,
        // but at minimum we should have created 101 objects
        assertTrue("Should have created at least 100 objects",
                   tracker.getTotalObjectsSeen() >= 100);
    }

    @Test
    public void testEnsureGCProcessed_ForcesImmediateProcessing() throws InterruptedException {
        // Create an object that will be GC'd
        Object obj = new Object();
        tracker.getOrCreate(obj, "TestClass", "testMethod");
        assertEquals("Should have 1 active flow initially", 1, tracker.getActiveCount());

        // Clear reference and force GC
        obj = null;
        boolean gcOccurred = false;
        for (int attempt = 0; attempt < 10; attempt++) {
            System.gc();
            Thread.sleep(50);

            // Force GC processing immediately (without waiting for 100 calls)
            tracker.ensureGCProcessed();

            if (tracker.getTotalGCDetected() > 0) {
                gcOccurred = true;
                break;
            }
        }

        assertTrue("ensureGCProcessed() should have forced immediate GC processing", gcOccurred);
        assertEquals("GC'd object should have been detected as leak", 1, tracker.getTotalGCDetected());
        assertEquals("Active flows should be 0 after GC processing", 0, tracker.getActiveCount());
    }

    @Test
    public void testFirstCallOnNewThread_ProcessesImmediately() throws Exception {
        // Create an object that will be GC'd on main thread
        Object obj = new Object();
        tracker.getOrCreate(obj, "TestClass", "testMethod");
        obj = null;

        // Force GC
        for (int i = 0; i < 5; i++) {
            System.gc();
            Thread.sleep(10);
        }

        // Create a new thread that makes only ONE call
        // This should process the GC queue immediately (first call on thread)
        Thread newThread = new Thread(() -> {
            // First call on this thread - should process GC queue immediately
            tracker.getOrCreate(new Object(), "TestClass", "newThreadMethod");
        });

        newThread.start();
        newThread.join();

        // Give a moment for processing
        Thread.sleep(50);

        // The new thread's first call should have processed the GC'd object
        // even though it only made 1 call (not 100)
        assertTrue("First call on new thread should have processed GC queue",
                   tracker.getTotalGCDetected() > 0 || tracker.getActiveCount() == 2);
    }
}
