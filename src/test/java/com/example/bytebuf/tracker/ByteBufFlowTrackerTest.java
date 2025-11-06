package com.example.bytebuf.tracker;

import com.example.bytebuf.tracker.trie.FlowTrie;
import com.example.bytebuf.tracker.view.TrieRenderer;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Unit tests for ByteBuf Flow Tracker
 */
public class ByteBufFlowTrackerTest {

    private ByteBufFlowTracker tracker;
    private FlowTrie trie;
    private TrieRenderer renderer;

    @Before
    public void setUp() {
        tracker = ByteBufFlowTracker.getInstance();
        tracker.reset(); // Clear any previous tracking data
        trie = tracker.getTrie();
        renderer = new TrieRenderer(trie);
    }

    @Test
    public void testEmptyTrackerHasNoRoots() {
        assertEquals("Empty tracker should have no roots", 0, trie.getRootCount());
    }

    @Test
    public void testEmptyTrackerHasNoActiveFlows() {
        assertEquals("Empty tracker should have no active flows", 0, tracker.getActiveFlowCount());
    }

    @Test
    public void testEmptyTreeRenderIsEmpty() {
        String tree = renderer.renderIndentedTree();
        assertTrue("Empty tracker should produce empty tree output",
                   tree == null || tree.trim().isEmpty());
    }

    @Test
    public void testEmptyLLMRenderHasZeroMetadata() {
        String llmOutput = renderer.renderForLLM();
        assertNotNull("LLM output should not be null", llmOutput);
        assertTrue("LLM output should contain metadata", llmOutput.contains("METADATA:"));
        assertTrue("LLM output should show 0 roots", llmOutput.contains("total_roots=0"));
        assertTrue("LLM output should show 0 paths", llmOutput.contains("total_paths=0"));
        assertTrue("LLM output should show 0 leaks", llmOutput.contains("leak_paths=0"));
        assertTrue("LLM output should show no leaks", llmOutput.contains("LEAKS:\nnone"));
    }

    @Test
    public void testSingleFlowTracking() {
        // Create a mock object to track
        Object mockBuf = new Object();

        // Record a simple flow
        tracker.recordMethodCall(mockBuf, "TestClass", "testMethod", 1);

        // Verify tracking
        assertEquals("Should have 1 root", 1, trie.getRootCount());
        assertEquals("Should have 1 active flow", 1, tracker.getActiveFlowCount());

        // Complete the flow
        tracker.recordMethodCall(mockBuf, "TestClass", "release", 0);

        // Verify flow completed
        assertEquals("Should have no active flows after release", 0, tracker.getActiveFlowCount());
    }

    @Test
    public void testLeakDetection() {
        Object mockBuf = new Object();

        // Track a flow that doesn't release
        tracker.recordMethodCall(mockBuf, "LeakyClass", "allocate", 1);
        tracker.recordMethodCall(mockBuf, "LeakyClass", "process", 1);
        // No release - leak!

        String llmOutput = renderer.renderForLLM();
        assertTrue("LLM output should detect leak", llmOutput.contains("leak_paths=1"));
        assertTrue("LLM output should show leak in LEAKS section",
                   llmOutput.contains("LEAKS:") && llmOutput.contains("leak|"));
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

        assertEquals("Should track 3 active flows", 3, tracker.getActiveFlowCount());

        // Release first two
        tracker.recordMethodCall(buf1, "Handler", "release", 0);
        tracker.recordMethodCall(buf2, "Handler", "release", 0);

        assertEquals("Should have 1 active flow after partial release", 1, tracker.getActiveFlowCount());
    }

    @Test
    public void testResetClearsAllData() {
        Object mockBuf = new Object();

        // Track something
        tracker.recordMethodCall(mockBuf, "TestClass", "test", 1);
        assertTrue("Should have data before reset", tracker.getActiveFlowCount() > 0);

        // Reset
        tracker.reset();

        // Verify everything is cleared
        assertEquals("Active flows should be 0 after reset", 0, tracker.getActiveFlowCount());
        assertEquals("Roots should be 0 after reset", 0, trie.getRootCount());
    }

    @Test
    public void testSummaryContainsExpectedFields() {
        String summary = renderer.renderSummary();
        assertNotNull("Summary should not be null", summary);
        assertTrue("Summary should contain root count", summary.contains("Total Root Methods:"));
        assertTrue("Summary should contain traversals", summary.contains("Total Traversals:"));
        assertTrue("Summary should contain paths", summary.contains("Unique Paths:"));
        assertTrue("Summary should contain leaks", summary.contains("Leak Paths:"));
    }

    @Test
    public void testLLMFormatStructure() {
        String llmOutput = renderer.renderForLLM();

        // Verify all required sections exist
        assertTrue("LLM output should have METADATA section", llmOutput.contains("METADATA:"));
        assertTrue("LLM output should have LEAKS section", llmOutput.contains("LEAKS:"));
        assertTrue("LLM output should have FLOWS section", llmOutput.contains("FLOWS:"));

        // Verify metadata format
        assertTrue("Metadata should have total_roots field", llmOutput.contains("total_roots="));
        assertTrue("Metadata should have total_traversals field", llmOutput.contains("total_traversals="));
        assertTrue("Metadata should have total_paths field", llmOutput.contains("total_paths="));
        assertTrue("Metadata should have leak_paths field", llmOutput.contains("leak_paths="));
    }
}
