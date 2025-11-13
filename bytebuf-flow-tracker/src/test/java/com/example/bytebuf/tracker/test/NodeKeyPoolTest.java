/*
 * Copyright 2025 Justin Marsh
 * SPDX-License-Identifier: Apache-2.0
 */

package com.example.bytebuf.tracker.test;

import com.example.bytebuf.tracker.trie.ImprintNode;
import com.example.bytebuf.tracker.trie.NodeKeyPool;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for NodeKeyPool functionality.
 */
public class NodeKeyPoolTest {

    @Test
    public void testBasicAcquireAndRelease() {
        // Acquire a pooled NodeKey
        NodeKeyPool.PooledNodeKey pooled = NodeKeyPool.acquire("TestClass.testMethod", (byte) 1);
        assertNotNull(pooled, "Pooled NodeKey should not be null");
        assertNotNull(pooled.getNodeKey(), "NodeKey should not be null");

        // Verify values
        ImprintNode.NodeKey key = pooled.getNodeKey();
        assertEquals("TestClass.testMethod", key.methodSignature);
        assertEquals(1, key.refCountBucket);

        // Release back to pool
        pooled.release();  // Should not throw
    }

    @Test
    public void testPoolReusesInstancesWithReset() {
        // Acquire, use, and release a NodeKey
        NodeKeyPool.PooledNodeKey first = NodeKeyPool.acquire("Test.method1", (byte) 1);
        ImprintNode.NodeKey firstKey = first.getNodeKey();
        int firstHashCode = firstKey.hashCode();
        first.release();

        // Acquire again with different values - pool should reset and reuse
        NodeKeyPool.PooledNodeKey second = NodeKeyPool.acquire("Test.method2", (byte) 2);
        ImprintNode.NodeKey secondKey = second.getNodeKey();

        // Verify the values are correct (reset should have been called by pool)
        assertEquals("Test.method2", secondKey.methodSignature);
        assertEquals(2, secondKey.refCountBucket);
        // Hash code should be different since values are different
        assertNotEquals(firstHashCode, secondKey.hashCode(),
            "Hash code should change with different values");

        second.release();
    }

    @Test
    public void testPoolReuse() {
        // Acquire and release a NodeKey
        NodeKeyPool.PooledNodeKey first = NodeKeyPool.acquire("Test.method1", (byte) 1);
        ImprintNode.NodeKey firstKey = first.getNodeKey();
        first.release();

        // Acquire again - should get a pooled instance (possibly the same one)
        NodeKeyPool.PooledNodeKey second = NodeKeyPool.acquire("Test.method2", (byte) 2);
        ImprintNode.NodeKey secondKey = second.getNodeKey();

        // Verify the values are correct (reset should have been called)
        assertEquals("Test.method2", secondKey.methodSignature);
        assertEquals(2, secondKey.refCountBucket);

        second.release();
    }

    @Test
    public void testAllocateForStorage() {
        // Allocate a NodeKey for permanent storage
        ImprintNode.NodeKey stored = NodeKeyPool.allocateForStorage("Stored.method", (byte) 3);

        assertNotNull(stored, "Stored NodeKey should not be null");
        assertEquals("Stored.method", stored.methodSignature);
        assertEquals(3, stored.refCountBucket);

        // This key should NOT be released to the pool
        // (no PooledNodeKey wrapper, just raw NodeKey)
    }

    @Test
    public void testConcurrentAcquireRelease() throws InterruptedException {
        final int threadCount = 20;
        final int iterationsPerThread = 100;
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);
        AtomicInteger successCount = new AtomicInteger(0);

        Thread[] threads = new Thread[threadCount];
        for (int i = 0; i < threadCount; i++) {
            final int threadId = i;
            threads[i] = new Thread(() -> {
                try {
                    startLatch.await();  // Wait for all threads to be ready
                    for (int j = 0; j < iterationsPerThread; j++) {
                        NodeKeyPool.PooledNodeKey pooled = NodeKeyPool.acquire(
                            "Thread" + threadId + ".method" + j,
                            (byte) (j % 4)
                        );

                        // Simulate some work
                        ImprintNode.NodeKey key = pooled.getNodeKey();
                        assertNotNull(key.methodSignature);
                        assertTrue(key.refCountBucket >= 0 && key.refCountBucket <= 3);

                        pooled.release();
                        successCount.incrementAndGet();
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                } finally {
                    doneLatch.countDown();
                }
            });
            threads[i].start();
        }

        startLatch.countDown();  // Start all threads
        doneLatch.await();  // Wait for all to complete

        int expectedCount = threadCount * iterationsPerThread;
        assertEquals(expectedCount, successCount.get(), "All operations should succeed");
    }

    @Test
    public void testPoolGracefulDegradation() {
        // Acquire many NodeKeys without releasing to exhaust the pool
        Set<NodeKeyPool.PooledNodeKey> held = new HashSet<>();

        // Pool size is 512, so acquire more than that
        for (int i = 0; i < 600; i++) {
            NodeKeyPool.PooledNodeKey pooled = NodeKeyPool.acquire(
                "Method" + i,
                (byte) (i % 4)
            );
            assertNotNull(pooled, "Should still get NodeKey even when pool exhausted");
            held.add(pooled);
        }

        // Release all
        for (NodeKeyPool.PooledNodeKey pooled : held) {
            pooled.release();
        }
    }

    @Test
    public void testHashCodeConsistency() {
        // Test that hash codes are consistent for same values
        NodeKeyPool.PooledNodeKey pooled1 = NodeKeyPool.acquire("Test.method1", (byte) 1);
        int hash1 = pooled1.getNodeKey().hashCode();
        pooled1.release();

        // Acquire with different values
        NodeKeyPool.PooledNodeKey pooled2 = NodeKeyPool.acquire("Test.method2", (byte) 2);
        int hash2 = pooled2.getNodeKey().hashCode();
        pooled2.release();

        assertNotEquals(hash1, hash2, "Hash code should differ for different values");

        // Acquire again with same values as first
        NodeKeyPool.PooledNodeKey pooled3 = NodeKeyPool.acquire("Test.method1", (byte) 1);
        int hash3 = pooled3.getNodeKey().hashCode();
        pooled3.release();

        assertEquals(hash1, hash3, "Hash code should be same for same values");
    }
}
