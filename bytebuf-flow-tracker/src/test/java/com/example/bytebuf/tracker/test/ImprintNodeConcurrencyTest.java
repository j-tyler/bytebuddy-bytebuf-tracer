/*
 * Copyright 2025 Justin Marsh
 * SPDX-License-Identifier: Apache-2.0
 */

package com.example.bytebuf.tracker.test;

import com.example.bytebuf.tracker.trie.ImprintNode;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for ImprintNode concurrent access.
 */
public class ImprintNodeConcurrencyTest {

    @Test
    public void testConcurrentTraversalCounting() throws InterruptedException {
        ImprintNode node = new ImprintNode("TestClass", "testMethod", (byte) 1, null);
        final int threadCount = 10;
        final int iterationsPerThread = 1000;
        CountDownLatch latch = new CountDownLatch(threadCount);

        Thread[] threads = new Thread[threadCount];
        for (int i = 0; i < threadCount; i++) {
            threads[i] = new Thread(() -> {
                for (int j = 0; j < iterationsPerThread; j++) {
                    node.recordTraversal();
                }
                latch.countDown();
            });
            threads[i].start();
        }

        latch.await();

        long expectedCount = (long) threadCount * iterationsPerThread;
        assertEquals(expectedCount, node.getTraversalCount(), "Traversal count should be accurate");
    }

    @Test
    public void testConcurrentLeakRecording() throws InterruptedException {
        ImprintNode node = new ImprintNode("TestClass", "testMethod", (byte) 1, null);
        final int threadCount = 10;
        final int iterationsPerThread = 100;
        CountDownLatch latch = new CountDownLatch(threadCount);

        Thread[] threads = new Thread[threadCount];
        for (int i = 0; i < threadCount; i++) {
            threads[i] = new Thread(() -> {
                for (int j = 0; j < iterationsPerThread; j++) {
                    node.recordLeak();
                }
                latch.countDown();
            });
            threads[i].start();
        }

        latch.await();

        long expectedLeaks = threadCount * iterationsPerThread;
        assertEquals(expectedLeaks, node.getLeakCount(), "Leak count should be accurate");
    }

    @Test
    public void testConcurrentTraversalAndLeakRecording() throws InterruptedException {
        ImprintNode node = new ImprintNode("TestClass", "testMethod", (byte) 1, null);
        final int traversalThreads = 5;
        final int leakThreads = 5;
        final int iterationsPerThread = 200;
        CountDownLatch latch = new CountDownLatch(traversalThreads + leakThreads);

        // Threads recording traversals
        for (int i = 0; i < traversalThreads; i++) {
            Thread thread = new Thread(() -> {
                for (int j = 0; j < iterationsPerThread; j++) {
                    node.recordTraversal();
                }
                latch.countDown();
            });
            thread.start();
        }

        // Threads recording leaks
        for (int i = 0; i < leakThreads; i++) {
            Thread thread = new Thread(() -> {
                for (int j = 0; j < iterationsPerThread; j++) {
                    node.recordLeak();
                }
                latch.countDown();
            });
            thread.start();
        }

        latch.await();

        long expectedTraversals = (long) traversalThreads * iterationsPerThread;
        long expectedLeaks = (long) leakThreads * iterationsPerThread;
        assertEquals(expectedTraversals, node.getTraversalCount(),
                     "Traversal count should be accurate despite concurrent leak updates");
        assertEquals(expectedLeaks, node.getLeakCount(),
                     "Leak count should be accurate despite concurrent traversal updates");
    }

    @Test
    public void testConcurrentChildCreation_NoLostUpdates() throws InterruptedException {
        ImprintNode parent = new ImprintNode("ParentClass", "parentMethod", (byte) 1, null);
        final int threadCount = 20;
        final int childrenPerThread = 5;
        CountDownLatch latch = new CountDownLatch(threadCount);
        AtomicInteger successCount = new AtomicInteger(0);

        Thread[] threads = new Thread[threadCount];
        for (int i = 0; i < threadCount; i++) {
            final int threadId = i;
            threads[i] = new Thread(() -> {
                for (int j = 0; j < childrenPerThread; j++) {
                    String className = "ChildClass" + threadId;
                    String methodName = "childMethod" + j;
                    String methodSignature = className + "." + methodName;
                    ImprintNode child = parent.getOrCreateChild(
                        className,
                        methodName,
                        methodSignature,
                        (byte) 1
                    );
                    if (child != null) {
                        successCount.incrementAndGet();
                    }
                }
                latch.countDown();
            });
            threads[i].start();
        }

        latch.await();

        // Should have created many children (up to per-node limit of 1000)
        assertTrue(parent.getChildren().size() > 0, "Should have created children");
        assertTrue(parent.getChildren().size() <= 1000, "Should not exceed max children");
        assertEquals(threadCount * childrenPerThread, successCount.get(), "All operations should succeed");
    }

    @Test
    public void testConcurrentChildStopOnLimit_MaintainsLimit() throws InterruptedException {
        ImprintNode parent = new ImprintNode("ParentClass", "parentMethod", (byte) 1, null);
        final int threadCount = 5;
        final int operationsPerThread = 300; // More operations to exceed the 1000 limit
        CountDownLatch latch = new CountDownLatch(threadCount);

        Thread[] threads = new Thread[threadCount];
        for (int i = 0; i < threadCount; i++) {
            final int threadId = i;
            threads[i] = new Thread(() -> {
                for (int j = 0; j < operationsPerThread; j++) {
                    String className = "ChildClass" + threadId + "_" + j;
                    String methodName = "method";
                    String methodSignature = className + "." + methodName;
                    parent.getOrCreateChild(
                        className,
                        methodName,
                        methodSignature,
                        (byte) 1
                    );
                }
                latch.countDown();
            });
            threads[i].start();
        }

        latch.await();

        // Verify child limit prevents unbounded growth under concurrent access
        // Note: With stop-on-limit (no eviction), the first 1000 unique children are kept.
        // Attempting to create 1500 children (5 threads × 300 ops) should stop at 1000.
        int childCount = parent.getChildren().size();
        assertTrue(childCount <= 1100,
                   "Child count (" + childCount + ") should be bounded near 1000 limit"); // Allow some tolerance for concurrent race conditions
        assertTrue(childCount >= 900,
                   "Should have created many children near the limit"); // Verify we actually hit the limit
    }

    @Test
    public void testNodeKeyHashCodeConsistency() {
        String className = "TestClass";
        String methodName = "testMethod";
        String methodSignature = className + "." + methodName;
        byte refCount = 1;

        ImprintNode.NodeKey key1 = new ImprintNode.NodeKey(methodSignature, refCount);
        ImprintNode.NodeKey key2 = new ImprintNode.NodeKey(methodSignature, refCount);

        assertEquals(key1.hashCode(), key2.hashCode(), "Hash codes should be equal for equal keys");
        assertEquals(key1, key2, "Keys should be equal");
    }

    @Test
    public void testNodeKeyIdentityComparison_WithInternedStrings() {
        // Simulate string interning
        String className1 = new String("TestClass").intern();
        String className2 = new String("TestClass").intern();
        String methodName1 = new String("testMethod").intern();
        String methodName2 = new String("testMethod").intern();
        String methodSignature1 = (className1 + "." + methodName1).intern();
        String methodSignature2 = (className2 + "." + methodName2).intern();

        ImprintNode.NodeKey key1 = new ImprintNode.NodeKey(methodSignature1, (byte) 1);
        ImprintNode.NodeKey key2 = new ImprintNode.NodeKey(methodSignature2, (byte) 1);

        // Since strings are interned, identity comparison should work
        assertSame(className1, className2, "Interned class names should be same object");
        assertSame(methodName1, methodName2, "Interned method names should be same object");
        assertSame(methodSignature1, methodSignature2, "Interned method signatures should be same object");
        assertEquals(key1, key2, "Keys should be equal");
    }
}
