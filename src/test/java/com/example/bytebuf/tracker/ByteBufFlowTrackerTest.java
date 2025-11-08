/*
 * Copyright 2025 Justin Marsh
 * SPDX-License-Identifier: Apache-2.0
 */

package com.example.bytebuf.tracker;

import com.example.bytebuf.tracker.trie.FlowTrie;
import com.example.bytebuf.tracker.view.TrieRenderer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for ByteBuf Flow Tracker using JUnit 5
 */
public class ByteBufFlowTrackerTest {

    private ByteBufFlowTracker tracker;
    private FlowTrie trie;
    private TrieRenderer renderer;

    @BeforeEach
    public void setUp() {
        tracker = ByteBufFlowTracker.getInstance();
        tracker.reset(); // Clear any previous tracking data
        trie = tracker.getTrie();
        renderer = new TrieRenderer(trie);
    }

    @Test
    public void testEmptyTrackerHasNoRoots() {
        assertEquals(0, trie.getRootCount(), "Empty tracker should have no roots");
    }

    @Test
    public void testEmptyTrackerHasNoActiveFlows() {
        assertEquals(0, tracker.getActiveFlowCount(), "Empty tracker should have no active flows");
    }

    @Test
    public void testEmptyTreeRenderIsEmpty() {
        String tree = renderer.renderIndentedTree();
        assertTrue(tree == null || tree.trim().isEmpty(),
                   "Empty tracker should produce empty tree output");
    }

    @Test
    public void testEmptyLLMRenderHasZeroMetadata() {
        String llmOutput = renderer.renderForLLM();
        assertNotNull(llmOutput, "LLM output should not be null");
        assertTrue(llmOutput.contains("METADATA:"), "LLM output should contain metadata");
        assertTrue(llmOutput.contains("total_roots=0"), "LLM output should show 0 roots");
        assertTrue(llmOutput.contains("total_paths=0"), "LLM output should show 0 paths");
        assertTrue(llmOutput.contains("leak_paths=0"), "LLM output should show 0 leaks");
        assertTrue(llmOutput.contains("LEAKS:\nnone"), "LLM output should show no leaks");
    }

    @Test
    public void testSingleFlowTracking() {
        // Create a mock object to track
        Object mockBuf = new Object();

        // Record a simple flow
        tracker.recordMethodCall(mockBuf, "TestClass", "testMethod", 1);

        // Verify tracking
        assertEquals(1, trie.getRootCount(), "Should have 1 root");
        assertEquals(1, tracker.getActiveFlowCount(), "Should have 1 active flow");

        // Complete the flow
        tracker.recordMethodCall(mockBuf, "TestClass", "release", 0);

        // Verify flow completed
        assertEquals(0, tracker.getActiveFlowCount(), "Should have no active flows after release");
    }

    @Test
    public void testLeakDetection() {
        Object mockBuf = new Object();

        // Track a flow that doesn't release
        tracker.recordMethodCall(mockBuf, "LeakyClass", "allocate", 1);
        tracker.recordMethodCall(mockBuf, "LeakyClass", "process", 1);
        // No release - leak!

        String llmOutput = renderer.renderForLLM();
        assertTrue(llmOutput.contains("leak_paths=1"), "LLM output should detect leak");
        assertTrue(llmOutput.contains("LEAKS:") && llmOutput.contains("leak|"),
                   "LLM output should show leak in LEAKS section");
    }

    @Test
    public void testMultipleFlowTracking() {
        Object buf1 = new Object();
        Object buf2 = new Object();
        Object buf3 = new Object();

        // Track multiple flows
        tracker.recordMethodCall(buf1, "Handler", "handle", 1);
        tracker.recordMethodCall(buf2, "Handler", "handle", 1);
        tracker.recordMethodCall(buf3, "Processor", "process", 1);

        assertEquals(3, tracker.getActiveFlowCount(), "Should track 3 active flows");

        // Release first two
        tracker.recordMethodCall(buf1, "Handler", "release", 0);
        tracker.recordMethodCall(buf2, "Handler", "release", 0);

        assertEquals(1, tracker.getActiveFlowCount(), "Should have 1 active flow after partial release");
    }

    @Test
    public void testResetClearsAllData() {
        Object mockBuf = new Object();

        // Track something
        tracker.recordMethodCall(mockBuf, "TestClass", "test", 1);
        assertTrue(tracker.getActiveFlowCount() > 0, "Should have data before reset");

        // Reset
        tracker.reset();

        // Verify everything is cleared
        assertEquals(0, tracker.getActiveFlowCount(), "Active flows should be 0 after reset");
        assertEquals(0, trie.getRootCount(), "Roots should be 0 after reset");
    }

    @Test
    public void testSummaryContainsExpectedFields() {
        String summary = renderer.renderSummary();
        assertNotNull(summary, "Summary should not be null");
        assertTrue(summary.contains("Total Root Methods:"), "Summary should contain root count");
        assertTrue(summary.contains("Total Traversals:"), "Summary should contain traversals");
        assertTrue(summary.contains("Unique Paths:"), "Summary should contain paths");
        assertTrue(summary.contains("Leak Paths:"), "Summary should contain leaks");
    }

    @Test
    public void testLLMFormatStructure() {
        String llmOutput = renderer.renderForLLM();

        // Verify all required sections exist
        assertTrue(llmOutput.contains("METADATA:"), "LLM output should have METADATA section");
        assertTrue(llmOutput.contains("LEAKS:"), "LLM output should have LEAKS section");
        assertTrue(llmOutput.contains("FLOWS:"), "LLM output should have FLOWS section");

        // Verify metadata format
        assertTrue(llmOutput.contains("total_roots="), "Metadata should have total_roots field");
        assertTrue(llmOutput.contains("total_traversals="), "Metadata should have total_traversals field");
        assertTrue(llmOutput.contains("total_paths="), "Metadata should have total_paths field");
        assertTrue(llmOutput.contains("leak_paths="), "Metadata should have leak_paths field");
    }

    // ========================================================================
    // Scenario-based tests with full output verification
    // ========================================================================

    /**
     * Test: ByteBuf allocated -> passed to method -> released
     * Verify complete trace output shows proper flow
     */
    @Test
    public void testDirectByteBufFlowWithRelease() {
        // Simulate: ByteBuf buf = Unpooled.buffer(256);
        Object buf = new Object();

        // Allocate
        tracker.recordMethodCall(buf, "Allocator", "allocate", 1);

        // Pass to method
        tracker.recordMethodCall(buf, "DataHandler", "processData", 1);

        // Release
        tracker.recordMethodCall(buf, "DataHandler", "release", 0);

        // Get and verify tree output
        String tree = renderer.renderIndentedTree();
        assertNotNull(tree, "Tree output should not be null");
        assertTrue(tree.contains("ROOT: Allocator.allocate"), "Tree should show allocate as root");
        assertTrue(tree.contains("DataHandler.processData"), "Tree should show processData method");
        assertTrue(tree.contains("DataHandler.release"), "Tree should show release method");
        assertTrue(tree.contains("ref=0"), "Tree should show final ref count of 0");

        // Get and verify LLM output
        String llmOutput = renderer.renderForLLM();
        assertTrue(llmOutput.contains("total_roots=1"), "Should have 1 root");
        assertTrue(llmOutput.contains("leak_paths=0"), "Should have no leaks");
        assertTrue(llmOutput.contains("LEAKS:\nnone"), "Leaks section should show none");
        assertTrue(llmOutput.contains("final_ref=0"), "Final ref should be 0");
        assertTrue(llmOutput.contains("is_leak=false"), "Should not be marked as leak");
        assertTrue(llmOutput.contains("Allocator.allocate"), "Flow should include allocate");
        assertTrue(llmOutput.contains("DataHandler.processData"), "Flow should include processData");
        assertTrue(llmOutput.contains("DataHandler.release"), "Flow should include release");

        // Verify no active flows remaining
        assertEquals(0, tracker.getActiveFlowCount(), "Should have no active flows after release");
    }

    /**
     * Test: ByteBuf allocated -> passed to wrapper constructor -> wrapper passed to method -> released
     * Verify complete trace output shows wrapper flow
     */
    @Test
    public void testWrapperByteBufFlowWithRelease() {
        // Simulate: ByteBuf buf = Unpooled.buffer(256);
        Object buf = new Object();

        // Allocate
        tracker.recordMethodCall(buf, "Allocator", "allocate", 1);

        // Pass to wrapper constructor (wrapper holds the ByteBuf)
        tracker.recordMethodCall(buf, "MessageWrapper", "<init>", 1);

        // Wrapper passed to method (still tracking same ByteBuf)
        tracker.recordMethodCall(buf, "MessageProcessor", "processMessage", 1);

        // Another method call
        tracker.recordMethodCall(buf, "MessageSerializer", "serialize", 1);

        // Release from wrapper
        tracker.recordMethodCall(buf, "MessageWrapper", "release", 0);

        // Get and verify tree output
        String tree = renderer.renderIndentedTree();
        assertNotNull(tree, "Tree output should not be null");
        assertTrue(tree.contains("ROOT: Allocator.allocate"), "Tree should show allocate as root");
        assertTrue(tree.contains("MessageWrapper.<init>"), "Tree should show wrapper constructor");
        assertTrue(tree.contains("MessageProcessor.processMessage"), "Tree should show processMessage");
        assertTrue(tree.contains("MessageSerializer.serialize"), "Tree should show serialize");
        assertTrue(tree.contains("MessageWrapper.release"), "Tree should show wrapper release");
        assertTrue(tree.contains("ref=0"), "Tree should show final ref count of 0");

        // Get and verify LLM output
        String llmOutput = renderer.renderForLLM();
        assertTrue(llmOutput.contains("total_roots=1"), "Should have 1 root");
        assertTrue(llmOutput.contains("leak_paths=0"), "Should have no leaks");
        assertTrue(llmOutput.contains("LEAKS:\nnone"), "Leaks section should show none");
        assertTrue(llmOutput.contains("final_ref=0"), "Final ref should be 0");
        assertTrue(llmOutput.contains("is_leak=false"), "Should not be marked as leak");
        assertTrue(llmOutput.contains("MessageWrapper.<init>"), "Flow should include wrapper init");
        assertTrue(llmOutput.contains("MessageProcessor.processMessage"), "Flow should include processMessage");
        assertTrue(llmOutput.contains("MessageSerializer.serialize"), "Flow should include serialize");
        assertTrue(llmOutput.contains("MessageWrapper.release"), "Flow should include wrapper release");

        // Verify no active flows remaining
        assertEquals(0, tracker.getActiveFlowCount(), "Should have no active flows after release");
    }

    /**
     * Test: ByteBuf allocated -> passed to method -> NOT released (LEAK)
     * Verify trace output correctly identifies the leak
     */
    @Test
    public void testDirectByteBufFlowWithLeak() {
        // Simulate: ByteBuf buf = Unpooled.buffer(256);
        Object buf = new Object();

        // Allocate
        tracker.recordMethodCall(buf, "Allocator", "allocate", 1);

        // Pass to method
        tracker.recordMethodCall(buf, "LeakyHandler", "processData", 1);

        // INTENTIONALLY DO NOT RELEASE - This is a leak!

        // Get and verify tree output
        String tree = renderer.renderIndentedTree();
        assertNotNull(tree, "Tree output should not be null");
        assertTrue(tree.contains("ROOT: Allocator.allocate"), "Tree should show allocate as root");
        assertTrue(tree.contains("LeakyHandler.processData"), "Tree should show processData method");
        assertTrue(tree.contains("⚠️ LEAK"), "Tree should show LEAK indicator");
        assertTrue(tree.contains("ref=1"), "Tree should show non-zero ref count");

        // Get and verify LLM output
        String llmOutput = renderer.renderForLLM();
        assertTrue(llmOutput.contains("total_roots=1"), "Should have 1 root");
        assertTrue(llmOutput.contains("leak_paths=1"), "Should have 1 leak");
        assertFalse(llmOutput.contains("LEAKS:\nnone"), "Leaks section should not show none");
        assertTrue(llmOutput.contains("leak|"), "Should have leak entry");
        assertTrue(llmOutput.contains("final_ref=1"), "Final ref should be 1 (not released)");
        assertTrue(llmOutput.contains("is_leak=true"), "Should be marked as leak");
        assertTrue(llmOutput.contains("Allocator.allocate"), "Flow should include allocate");
        assertTrue(llmOutput.contains("LeakyHandler.processData"), "Flow should include processData");

        // Verify active flow still tracked (not released)
        assertEquals(1, tracker.getActiveFlowCount(), "Should have 1 active flow (leak)");
    }

    /**
     * Test: ByteBuf allocated -> wrapper constructor -> wrapper to method -> NOT released (LEAK)
     * Verify trace output correctly identifies the wrapper leak
     */
    @Test
    public void testWrapperByteBufFlowWithLeak() {
        // Simulate: ByteBuf buf = Unpooled.buffer(256);
        Object buf = new Object();

        // Allocate
        tracker.recordMethodCall(buf, "Allocator", "allocate", 1);

        // Pass to wrapper constructor
        tracker.recordMethodCall(buf, "RequestWrapper", "<init>", 1);

        // Wrapper passed to method
        tracker.recordMethodCall(buf, "RequestHandler", "handleRequest", 1);

        // Another method in the flow
        tracker.recordMethodCall(buf, "ErrorLogger", "logError", 1);

        // INTENTIONALLY DO NOT RELEASE - This is a leak!

        // Get and verify tree output
        String tree = renderer.renderIndentedTree();
        assertNotNull(tree, "Tree output should not be null");
        assertTrue(tree.contains("ROOT: Allocator.allocate"), "Tree should show allocate as root");
        assertTrue(tree.contains("RequestWrapper.<init>"), "Tree should show wrapper constructor");
        assertTrue(tree.contains("RequestHandler.handleRequest"), "Tree should show handleRequest");
        assertTrue(tree.contains("ErrorLogger.logError"), "Tree should show logError");
        assertTrue(tree.contains("⚠️ LEAK"), "Tree should show LEAK indicator");
        assertTrue(tree.contains("ref=1"), "Tree should show non-zero ref count");

        // Get and verify LLM output
        String llmOutput = renderer.renderForLLM();
        assertTrue(llmOutput.contains("total_roots=1"), "Should have 1 root");
        assertTrue(llmOutput.contains("leak_paths=1"), "Should have 1 leak");
        assertFalse(llmOutput.contains("LEAKS:\nnone"), "Leaks section should not show none");
        assertTrue(llmOutput.contains("leak|"), "Should have leak entry");
        assertTrue(llmOutput.contains("final_ref=1"), "Final ref should be 1 (not released)");
        assertTrue(llmOutput.contains("is_leak=true"), "Should be marked as leak");
        assertTrue(llmOutput.contains("RequestWrapper.<init>"), "Flow should include wrapper init");
        assertTrue(llmOutput.contains("RequestHandler.handleRequest"), "Flow should include handleRequest");
        assertTrue(llmOutput.contains("ErrorLogger.logError"), "Flow should include logError");

        // Verify active flow still tracked (not released)
        assertEquals(1, tracker.getActiveFlowCount(), "Should have 1 active flow (leak)");
    }
}
