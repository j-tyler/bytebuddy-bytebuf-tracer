/*
 * Copyright 2025 Justin Marsh
 * SPDX-License-Identifier: Apache-2.0
 */

package com.example.bytebuf.tracker.test;

import com.example.bytebuf.tracker.ByteBufFlowTracker;
import com.example.bytebuf.tracker.trie.BoundedImprintTrie;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Tests that verify memory bounds are maintained under stress.
 */
public class BoundsVerificationTest {

    private ByteBufFlowTracker tracker;

    @Before
    public void setUp() {
        tracker = ByteBufFlowTracker.getInstance();
        tracker.reset();
    }

    @After
    public void tearDown() {
        tracker.reset();
    }

    @Test
    public void testNodeCountNeverExceedsMax() {
        BoundedImprintTrie trie = tracker.getTrie();
        int maxNodes = trie.getMaxNodes();

        // Create many ByteBufs with diverse paths
        for (int i = 0; i < maxNodes * 2; i++) {
            ByteBuf buf = Unpooled.buffer(10);
            tracker.recordMethodCall(buf, "Class" + (i % 100), "method" + (i % 50), buf.refCnt());
            tracker.recordMethodCall(buf, "ProcessorClass", "process", "ProcessorClass.process", buf.refCnt());
            buf.release();
        }

        // Verify bounds
        int nodeCount = trie.getNodeCount();
        assertTrue("Node count (" + nodeCount + ") should not exceed max (" + maxNodes + ")",
                   nodeCount <= maxNodes);
    }

    @Test
    public void testDepthNeverExceedsMax() {
        BoundedImprintTrie trie = tracker.getTrie();
        int maxDepth = trie.getMaxDepth();

        ByteBuf buf = Unpooled.buffer(10);

        // Create a deep path
        for (int depth = 0; depth < maxDepth * 2; depth++) {
            tracker.recordMethodCall(buf, "Class" + depth, "method" + depth, buf.refCnt());
        }

        buf.release();

        // The depth limiting should have prevented explosion
        // We can't directly test depth, but node count should be reasonable
        int nodeCount = trie.getNodeCount();
        assertTrue("Node count should be bounded by depth limit", nodeCount <= maxDepth + 10);
    }

    @Test
    public void testHighConcurrentLoad_NoUnboundedGrowth() throws InterruptedException {
        final int threadCount = 10;
        final int operationsPerThread = 500;
        Thread[] threads = new Thread[threadCount];

        for (int i = 0; i < threadCount; i++) {
            threads[i] = new Thread(() -> {
                for (int j = 0; j < operationsPerThread; j++) {
                    ByteBuf buf = Unpooled.buffer(10);
                    tracker.recordMethodCall(buf, "TestClass", "method", "TestClass.method", buf.refCnt());
                    tracker.recordMethodCall(buf, "ProcessorClass", "process", "ProcessorClass.process", buf.refCnt());
                    buf.release();
                }
            });
            threads[i].start();
        }

        for (Thread thread : threads) {
            thread.join();
        }

        // Verify bounds held under concurrent load
        BoundedImprintTrie trie = tracker.getTrie();
        assertTrue("Node count should not exceed max", trie.getNodeCount() <= trie.getMaxNodes());
        assertTrue("Root count should be reasonable", trie.getRootCount() < 100);
    }

    @Test
    public void testChildLimitEnforcement() {
        BoundedImprintTrie trie = tracker.getTrie();

        ByteBuf buf = Unpooled.buffer(10);
        tracker.recordMethodCall(buf, "RootClass", "root", "RootClass.root", buf.refCnt());

        // Create many different paths from same root
        for (int i = 0; i < 150; i++) {
            tracker.recordMethodCall(buf, "ChildClass" + i, "child" + i, buf.refCnt());
        }

        buf.release();

        // The per-node child limit should have prevented explosion
        // Total nodes should be reasonable (root + ~100 children due to limit)
        int nodeCount = trie.getNodeCount();
        assertTrue("Node count should be limited by per-node child limit", nodeCount < 150);
    }

}
