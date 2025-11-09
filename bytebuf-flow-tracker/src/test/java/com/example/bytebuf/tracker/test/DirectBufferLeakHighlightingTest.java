/*
 * Copyright 2025 Justin Marsh
 * SPDX-License-Identifier: Apache-2.0
 */

package com.example.bytebuf.tracker.test;

import com.example.bytebuf.tracker.ByteBufFlowTracker;
import com.example.bytebuf.tracker.view.TrieRenderer;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.UnpooledByteBufAllocator;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Test that demonstrates direct buffer leak highlighting with 🚨 emoji.
 *
 * This test shows how critical direct buffer leaks (which are never GC'd)
 * are visually distinguished from heap buffer leaks (which will eventually GC).
 */
public class DirectBufferLeakHighlightingTest {

    private ByteBufFlowTracker tracker;
    private ByteBufAllocator allocator;

    @Before
    public void setUp() {
        tracker = ByteBufFlowTracker.getInstance();
        tracker.reset();
        allocator = UnpooledByteBufAllocator.DEFAULT;
    }

    @Test
    public void testHeapBufferLeakShowsWarningEmoji() {
        // Simulate heap buffer allocation and leak
        ByteBuf heapBuf = allocator.heapBuffer(256);
        tracker.recordMethodCall(heapBuf, "UnpooledByteBufAllocator", "heapBuffer", heapBuf.refCnt());
        tracker.recordMethodCall(heapBuf, "TestService", "processData", heapBuf.refCnt());
        // Intentionally NOT releasing - heap buffer leak

        TrieRenderer renderer = new TrieRenderer(tracker.getTrie());
        String tree = renderer.renderIndentedTree();

        System.out.println("=== Heap Buffer Leak Output ===");
        System.out.println(tree);

        // Should show ⚠️ LEAK (moderate - will GC)
        assertTrue("Should show ⚠️ for heap buffer leak", tree.contains("⚠️ LEAK"));
        assertFalse("Should NOT show 🚨 for heap buffer leak", tree.contains("🚨 LEAK"));

        heapBuf.release(); // Cleanup
    }

    @Test
    public void testDirectBufferLeakShowsCriticalEmoji() {
        // Simulate direct buffer allocation and leak
        ByteBuf directBuf = allocator.directBuffer(256);
        tracker.recordMethodCall(directBuf, "UnpooledByteBufAllocator", "directBuffer", directBuf.refCnt());
        tracker.recordMethodCall(directBuf, "NetworkService", "sendData", directBuf.refCnt());
        // Intentionally NOT releasing - CRITICAL direct buffer leak

        TrieRenderer renderer = new TrieRenderer(tracker.getTrie());
        String tree = renderer.renderIndentedTree();

        System.out.println("=== Direct Buffer Leak Output ===");
        System.out.println(tree);

        // Should show 🚨 LEAK (critical - never GC'd!)
        assertTrue("Should show 🚨 for direct buffer leak", tree.contains("🚨 LEAK"));
        assertFalse("Should NOT show only ⚠️ for direct buffer leak",
                    tree.contains("⚠️ LEAK") && !tree.contains("🚨 LEAK"));

        directBuf.release(); // Cleanup
    }

    @Test
    public void testMixedLeaksShowDifferentEmojis() {
        // Create both heap and direct buffer leaks
        ByteBuf heapBuf = allocator.heapBuffer(128);
        tracker.recordMethodCall(heapBuf, "UnpooledByteBufAllocator", "heapBuffer", heapBuf.refCnt());
        tracker.recordMethodCall(heapBuf, "Parser", "parse", heapBuf.refCnt());

        ByteBuf directBuf = allocator.directBuffer(1024);
        tracker.recordMethodCall(directBuf, "UnpooledByteBufAllocator", "directBuffer", directBuf.refCnt());
        tracker.recordMethodCall(directBuf, "IOHandler", "write", directBuf.refCnt());

        TrieRenderer renderer = new TrieRenderer(tracker.getTrie());
        String tree = renderer.renderIndentedTree();

        System.out.println("=== Mixed Leaks Output ===");
        System.out.println(tree);

        // Should show BOTH emoji types
        assertTrue("Should show ⚠️ for heap buffer leak", tree.contains("⚠️ LEAK"));
        assertTrue("Should show 🚨 for direct buffer leak", tree.contains("🚨 LEAK"));

        // Verify different roots have different emojis
        String[] lines = tree.split("\n");
        boolean foundHeapLeak = false;
        boolean foundDirectLeak = false;

        for (String line : lines) {
            if (line.contains("heapBuffer") && line.contains("ROOT:")) {
                // Next lines should show ⚠️ somewhere in the tree
                foundHeapLeak = true;
            }
            if (line.contains("directBuffer") && line.contains("ROOT:")) {
                // Next lines should show 🚨 somewhere in the tree
                foundDirectLeak = true;
            }
        }

        assertTrue("Should have heap buffer root", foundHeapLeak);
        assertTrue("Should have direct buffer root", foundDirectLeak);

        heapBuf.release();
        directBuf.release();
    }

    @Test
    public void testLLMFormatShowsCriticalLeakLabel() {
        // Create direct buffer leak
        ByteBuf directBuf = allocator.directBuffer(512);
        tracker.recordMethodCall(directBuf, "UnpooledByteBufAllocator", "directBuffer", directBuf.refCnt());
        tracker.recordMethodCall(directBuf, "CriticalService", "handleRequest", directBuf.refCnt());

        TrieRenderer renderer = new TrieRenderer(tracker.getTrie());
        String llmFormat = renderer.renderForLLM();

        System.out.println("=== LLM Format Output ===");
        System.out.println(llmFormat);

        // LLM format should show CRITICAL_LEAK prefix
        assertTrue("Should show CRITICAL_LEAK in LLM format", llmFormat.contains("CRITICAL_LEAK"));
        assertTrue("Should reference directBuffer root", llmFormat.contains("directBuffer"));

        directBuf.release();
    }

    @Test
    public void testIOBufferAlsoMarkedAsCritical() {
        // ioBuffer typically allocates direct buffers
        ByteBuf ioBuf = allocator.ioBuffer(1024);

        // Simulate tracking through ioBuffer method
        tracker.recordMethodCall(ioBuf, "UnpooledByteBufAllocator", "ioBuffer", ioBuf.refCnt());
        tracker.recordMethodCall(ioBuf, "SocketHandler", "read", ioBuf.refCnt());

        TrieRenderer renderer = new TrieRenderer(tracker.getTrie());
        String tree = renderer.renderIndentedTree();

        System.out.println("=== IO Buffer Leak Output ===");
        System.out.println(tree);

        // ioBuffer should also be marked as critical (🚨)
        assertTrue("Should show 🚨 for ioBuffer leak", tree.contains("🚨 LEAK"));

        ioBuf.release();
    }

    @Test
    public void testProperlyReleasedBuffersShowNoLeakEmoji() {
        // Create and properly release both buffer types
        ByteBuf heapBuf = allocator.heapBuffer(128);
        tracker.recordMethodCall(heapBuf, "UnpooledByteBufAllocator", "heapBuffer", heapBuf.refCnt());
        tracker.recordMethodCall(heapBuf, "GoodService", "process", heapBuf.refCnt());
        heapBuf.release();
        tracker.recordMethodCall(heapBuf, "UnpooledHeapByteBuf", "release", 0);

        ByteBuf directBuf = allocator.directBuffer(256);
        tracker.recordMethodCall(directBuf, "UnpooledByteBufAllocator", "directBuffer", directBuf.refCnt());
        tracker.recordMethodCall(directBuf, "GoodService", "send", directBuf.refCnt());
        directBuf.release();
        tracker.recordMethodCall(directBuf, "UnpooledDirectByteBuf", "release", 0);

        TrieRenderer renderer = new TrieRenderer(tracker.getTrie());
        String tree = renderer.renderIndentedTree();

        System.out.println("=== Properly Released Buffers Output ===");
        System.out.println(tree);

        // Should NOT show any leak emojis
        assertFalse("Should not show ⚠️ when properly released", tree.contains("⚠️ LEAK"));
        assertFalse("Should not show 🚨 when properly released", tree.contains("🚨 LEAK"));
    }

    /**
     * Demonstrates real-world triage scenario
     */
    @Test
    public void testRealWorldTriageScenario() {
        System.out.println("\n=== Real-World Triage Scenario ===\n");

        // Scenario 1: Critical - Direct buffer for network I/O (LEAKED)
        ByteBuf networkBuf = allocator.directBuffer(8192);
        tracker.recordMethodCall(networkBuf, "UnpooledByteBufAllocator", "directBuffer", networkBuf.refCnt());
        tracker.recordMethodCall(networkBuf, "NetworkService", "handleRequest", networkBuf.refCnt());
        tracker.recordMethodCall(networkBuf, "RequestHandler", "processRequest", networkBuf.refCnt());
        // LEAKED! Never released

        // Scenario 2: Moderate - Heap buffer for parsing (LEAKED)
        ByteBuf parseBuf = allocator.heapBuffer(1024);
        tracker.recordMethodCall(parseBuf, "UnpooledByteBufAllocator", "heapBuffer", parseBuf.refCnt());
        tracker.recordMethodCall(parseBuf, "JsonParser", "parse", parseBuf.refCnt());
        // LEAKED! Never released

        // Scenario 3: Good - Direct buffer properly released
        ByteBuf goodDirectBuf = allocator.directBuffer(4096);
        tracker.recordMethodCall(goodDirectBuf, "UnpooledByteBufAllocator", "directBuffer", goodDirectBuf.refCnt());
        tracker.recordMethodCall(goodDirectBuf, "FileWriter", "write", goodDirectBuf.refCnt());
        goodDirectBuf.release();
        tracker.recordMethodCall(goodDirectBuf, "UnpooledDirectByteBuf", "release", 0);

        TrieRenderer renderer = new TrieRenderer(tracker.getTrie());
        String tree = renderer.renderIndentedTree();
        String summary = renderer.renderSummary();

        System.out.println(summary);
        System.out.println(tree);

        System.out.println("\n=== Triage Priority ===");
        System.out.println("🚨 CRITICAL: Fix direct buffer leaks first (NetworkService.handleRequest)");
        System.out.println("⚠️  MODERATE: Fix heap buffer leaks second (JsonParser.parse)");
        System.out.println("✅ CLEAN: FileWriter.write properly released");

        // Verify the output
        assertTrue("Should detect NetworkService leak as CRITICAL", tree.contains("🚨 LEAK"));
        assertTrue("Should detect JsonParser leak as moderate", tree.contains("⚠️ LEAK"));
        assertTrue("Should show FileWriter as clean (ref=0)", tree.contains("release [ref=0"));

        networkBuf.release(); // Cleanup
        parseBuf.release();
    }
}
