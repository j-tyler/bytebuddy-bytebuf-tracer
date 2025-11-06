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
}
