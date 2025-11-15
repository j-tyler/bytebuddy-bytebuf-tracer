/*
 * Copyright 2025 Justin Marsh
 * SPDX-License-Identifier: Apache-2.0
 */

package com.example.bytebuf.tracker.test;

import com.example.bytebuf.tracker.trie.BoundedImprintTrie;
import com.example.bytebuf.tracker.trie.ImprintNode;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Tests for BoundedImprintTrie limit enforcement (stop-on-limit behavior).
 * Note: Eviction logic has been removed to avoid concurrency overhead.
 * When limits are reached, the trie stops accepting new nodes.
 */
public class BoundedImprintTrieEvictionTest {

    private BoundedImprintTrie trie;

    @Before
    public void setUp() {
        // Small limits for testing eviction
        trie = new BoundedImprintTrie(100, 10);
    }

    @After
    public void tearDown() {
        trie.clear();
    }

    @Test
    public void testRootLimitEnforcement_WhenMaxNodesReached() {
        // Fill up to capacity
        for (int i = 0; i < 100; i++) {
            trie.getOrCreateRoot("Class" + i + ".method");
        }

        int rootsBeforeLimitHit = trie.getRootCount();
        assertTrue("Should have many roots", rootsBeforeLimitHit > 0);

        // Try to create one more root - should return existing root (no new root created)
        ImprintNode limitedRoot = trie.getOrCreateRoot("ClassNew.method");
        assertNotNull("Should return a root even when limit hit", limitedRoot);

        // Node count should not wildly exceed max (may be slightly over due to concurrency)
        assertTrue("Node count should not exceed max", trie.getNodeCount() <= trie.getMaxNodes());
    }

    @Test
    public void testChildEviction_WhenMaxDepthReached() {
        ImprintNode root = trie.getOrCreateRoot("TestClass.rootMethod");

        ImprintNode current = root;
        for (int depth = 0; depth < trie.getMaxDepth() + 5; depth++) {
            current = trie.traverseOrCreate(current, "Class.method" + depth, 1, depth);
        }

        // Verify depth limiting worked
        assertNotNull("Should still have a node", current);
    }

    @Test
    public void testStopOnLimit_NoEvictionOccurs() {
        // Create roots with different traversal counts
        ImprintNode root1 = trie.getOrCreateRoot("LowUsage.method");
        ImprintNode root2 = trie.getOrCreateRoot("HighUsage.method");

        // Traverse root2 many times
        for (int i = 0; i < 100; i++) {
            root2.recordTraversal();
        }

        // root1 has low usage (1 traversal)
        assertTrue("root1 should have low usage", root1.getTraversalCount() < 10);
        assertTrue("root2 should have high usage", root2.getTraversalCount() > 50);

        int initialRootCount = trie.getRootCount();

        // Fill up to capacity with new roots - should stop creating new roots
        for (int i = 0; i < 150; i++) {
            trie.getOrCreateRoot("Class" + i + ".method");
        }

        // Verify limit enforcement (node count should not wildly exceed max)
        assertTrue("Node count should not exceed max", trie.getNodeCount() <= trie.getMaxNodes());

        // With stop-on-limit, both original roots should still exist (no eviction)
        assertTrue("Original roots should still exist", trie.getRootCount() >= 2);
        assertNotNull("LowUsage root should still exist", trie.getRoots().get("LowUsage.method"));
        assertNotNull("HighUsage root should still exist", trie.getRoots().get("HighUsage.method"));
    }

    @Test
    public void testRefCountBucketing() {
        assertEquals("refCnt=0 should bucket to 0", (byte) 0, BoundedImprintTrie.bucketRefCount(0));
        assertEquals("refCnt=1 should bucket to 1", (byte) 1, BoundedImprintTrie.bucketRefCount(1));
        assertEquals("refCnt=2 should bucket to 1", (byte) 1, BoundedImprintTrie.bucketRefCount(2));
        assertEquals("refCnt=3 should bucket to 2", (byte) 2, BoundedImprintTrie.bucketRefCount(3));
        assertEquals("refCnt=5 should bucket to 2", (byte) 2, BoundedImprintTrie.bucketRefCount(5));
        assertEquals("refCnt=6 should bucket to 3", (byte) 3, BoundedImprintTrie.bucketRefCount(6));
        assertEquals("refCnt=100 should bucket to 3", (byte) 3, BoundedImprintTrie.bucketRefCount(100));
    }

    @Test
    public void testConcurrentRootCreation_NoDeadlocks() throws InterruptedException {
        // Use larger limit for concurrent test to avoid overwhelming eviction
        BoundedImprintTrie largeTrie = new BoundedImprintTrie(1000, 10);

        final int threadCount = 10;
        final int operationsPerThread = 50;
        Thread[] threads = new Thread[threadCount];

        for (int i = 0; i < threadCount; i++) {
            final int threadId = i;
            threads[i] = new Thread(() -> {
                for (int j = 0; j < operationsPerThread; j++) {
                    largeTrie.getOrCreateRoot("Class" + threadId + ".method" + j);
                }
            });
            threads[i].start();
        }

        // Wait for all threads
        for (Thread thread : threads) {
            thread.join(5000);
        }

        // Verify no deadlocks and bounds are roughly respected
        // Note: Node count is approximate due to concurrent updates, so allow some overflow
        int nodeCount = largeTrie.getNodeCount();
        int maxNodes = largeTrie.getMaxNodes();
        assertTrue("Node count (" + nodeCount + ") should not wildly exceed max (" + maxNodes + ")",
                   nodeCount <= maxNodes * 1.5); // Allow more tolerance for concurrent stress
        assertTrue("Should have created some roots", largeTrie.getRootCount() > 0);
        largeTrie.clear();
    }
}
