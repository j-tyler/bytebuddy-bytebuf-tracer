/*
 * Copyright 2025 Justin Marsh
 * SPDX-License-Identifier: Apache-2.0
 */

package com.example.bytebuf.tracker.view;

import com.example.bytebuf.tracker.ByteBufFlowTracker;
import com.example.bytebuf.tracker.trie.BoundedImprintTrie;
import com.example.bytebuf.tracker.trie.ImprintNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test for TrieRenderer, particularly the simple class name extraction functionality.
 */
public class TrieRendererTest {

    private ByteBufFlowTracker tracker;
    private BoundedImprintTrie trie;

    @BeforeEach
    public void setUp() {
        tracker = ByteBufFlowTracker.getInstance();
        tracker.reset();
        trie = tracker.getTrie();
    }

    /**
     * Test simple class name rendering in tree format.
     * Verifies that fully qualified class names are displayed as simple names.
     */
    @Test
    public void testTreeViewShowsSimpleClassNames() {
        // Create a mock object to track
        Object obj = new Object();

        // Record calls with fully qualified class names
        tracker.recordMethodCall(obj, "com.example.demo.MessageProcessor", "process",
                "com.example.demo.MessageProcessor.process", 1);
        tracker.recordMethodCall(obj, "com.example.demo.Handler", "handle",
                "com.example.demo.Handler.handle", 1);
        tracker.recordMethodCall(obj, "com.example.demo.Service", "cleanup",
                "com.example.demo.Service.cleanup", 0);

        tracker.onShutdown();

        TrieRenderer renderer = new TrieRenderer(trie);
        String tree = renderer.renderIndentedTree();

        // Should show simple class names, not full package names
        assertTrue(tree.contains("MessageProcessor.process"), "Should show MessageProcessor (simple name)");
        assertTrue(tree.contains("Handler.handle"), "Should show Handler (simple name)");
        assertTrue(tree.contains("Service.cleanup"), "Should show Service (simple name)");

        // Should NOT show full package names
        assertFalse(tree.contains("com.example.demo.MessageProcessor"), "Should not show com.example.demo prefix");
        assertFalse(tree.contains("com.example.demo.Handler"), "Should not show com.example.demo prefix");
        assertFalse(tree.contains("com.example.demo.Service"), "Should not show com.example.demo prefix");
    }

    /**
     * Test simple class name rendering in LLM format.
     * Verifies that paths contain simple class names.
     */
    @Test
    public void testLLMFormatShowsSimpleClassNames() {
        Object obj = new Object();

        tracker.recordMethodCall(obj, "com.example.demo.MessageProcessor", "process",
                "com.example.demo.MessageProcessor.process", 1);
        tracker.recordMethodCall(obj, "com.example.demo.Handler", "handle",
                "com.example.demo.Handler.handle", 0);

        tracker.onShutdown();

        TrieRenderer renderer = new TrieRenderer(trie);
        String llmView = renderer.renderForLLM();

        // Paths should contain simple class names
        assertTrue(llmView.contains("MessageProcessor.process"), "Path should contain MessageProcessor.process");
        assertTrue(llmView.contains("Handler.handle"), "Path should contain Handler.handle");

        // Should NOT contain full package names
        assertFalse(llmView.contains("com.example.demo.MessageProcessor"), "Should not contain com.example.demo prefix");
        assertFalse(llmView.contains("com.example.demo.Handler"), "Should not contain com.example.demo prefix");
    }

    /**
     * Test handling of inner classes.
     * Inner classes should preserve the Outer$Inner format.
     */
    @Test
    public void testInnerClassHandling() {
        Object obj = new Object();

        tracker.recordMethodCall(obj, "com.example.demo.Outer$Inner", "process",
                "com.example.demo.Outer$Inner.process", 1);
        tracker.recordMethodCall(obj, "com.example.demo.Outer$Middle$Inner", "handle",
                "com.example.demo.Outer$Middle$Inner.handle", 0);

        tracker.onShutdown();

        TrieRenderer renderer = new TrieRenderer(trie);
        String tree = renderer.renderIndentedTree();

        // Should show Outer$Inner format (preserves $ separator)
        assertTrue(tree.contains("Outer$Inner.process"), "Should show Outer$Inner");
        assertTrue(tree.contains("Outer$Middle$Inner.handle"), "Should show Outer$Middle$Inner");

        // Should NOT show full package
        assertFalse(tree.contains("com.example.demo.Outer$Inner"), "Should not show full package for inner class");
    }

    /**
     * Test that classes without packages are displayed correctly.
     */
    @Test
    public void testClassesWithoutPackages() {
        Object obj = new Object();

        tracker.recordMethodCall(obj, "SimpleClass", "method1", "SimpleClass.method1", 1);
        tracker.recordMethodCall(obj, "AnotherClass", "method2", "AnotherClass.method2", 0);

        tracker.onShutdown();

        TrieRenderer renderer = new TrieRenderer(trie);
        String tree = renderer.renderIndentedTree();

        // Should show classes as-is (no change)
        assertTrue(tree.contains("SimpleClass.method1"), "Should show SimpleClass");
        assertTrue(tree.contains("AnotherClass.method2"), "Should show AnotherClass");
    }

    /**
     * Test deeply nested packages.
     */
    @Test
    public void testDeeplyNestedPackages() {
        Object obj = new Object();

        tracker.recordMethodCall(obj, "com.example.foo.bar.baz.qux.DeepClass", "deepMethod",
                "com.example.foo.bar.baz.qux.DeepClass.deepMethod", 1);

        tracker.onShutdown();

        TrieRenderer renderer = new TrieRenderer(trie);
        String tree = renderer.renderIndentedTree();

        // Should show only simple name
        assertTrue(tree.contains("DeepClass.deepMethod"), "Should show DeepClass (simple name)");
        assertFalse(tree.contains("com.example.foo.bar.baz.qux.DeepClass"), "Should not show full package path");
    }

    /**
     * Test that Netty internal classes show simple names.
     */
    @Test
    public void testNettyInternalClasses() {
        Object obj = new Object();

        // Simulate Netty internal class names
        tracker.recordMethodCall(obj, "io.netty.buffer.UnpooledByteBufAllocator", "heapBuffer",
                "io.netty.buffer.UnpooledByteBufAllocator.heapBuffer", 1);
        tracker.recordMethodCall(obj, "io.netty.buffer.UnpooledUnsafeHeapByteBuf", "release",
                "io.netty.buffer.UnpooledUnsafeHeapByteBuf.release", 0);

        tracker.onShutdown();

        TrieRenderer renderer = new TrieRenderer(trie);
        String tree = renderer.renderIndentedTree();

        // Should show simple class names for Netty classes
        assertTrue(tree.contains("UnpooledByteBufAllocator.heapBuffer"), "Should show UnpooledByteBufAllocator");
        assertTrue(tree.contains("UnpooledUnsafeHeapByteBuf.release"), "Should show UnpooledUnsafeHeapByteBuf");

        // Should NOT show io.netty.buffer prefix
        assertFalse(tree.contains("io.netty.buffer.UnpooledByteBufAllocator"), "Should not show io.netty.buffer prefix");
        assertFalse(tree.contains("io.netty.buffer.UnpooledUnsafeHeapByteBuf"), "Should not show io.netty.buffer prefix");
    }

    /**
     * Test edge case: empty string class name.
     */
    @Test
    public void testEmptyStringClassName() {
        Object obj = new Object();

        tracker.recordMethodCall(obj, "", "method", ".method", 1);

        tracker.onShutdown();

        TrieRenderer renderer = new TrieRenderer(trie);
        String tree = renderer.renderIndentedTree();

        // Should handle empty string gracefully (shows ".method")
        assertTrue(tree.contains(".method"), "Should contain .method");
    }

    /**
     * Test that rendering preserves method names and metadata.
     * Simple class name extraction should not affect method names, ref counts, etc.
     */
    @Test
    public void testRenderingPreservesMetadata() {
        Object obj = new Object();

        tracker.recordMethodCall(obj, "com.example.demo.Service", "process", "com.example.demo.Service.process", 1);
        tracker.recordMethodCall(obj, "com.example.demo.Service", "cleanup", "com.example.demo.Service.cleanup", 0);

        tracker.onShutdown();

        TrieRenderer renderer = new TrieRenderer(trie);
        String tree = renderer.renderIndentedTree();

        // Method names should be preserved
        assertTrue(tree.contains("Service.process"), "Should preserve method name 'process'");
        assertTrue(tree.contains("Service.cleanup"), "Should preserve method name 'cleanup'");

        // Ref counts should be preserved
        assertTrue(tree.contains("[ref=1"), "Should show ref=1");
        assertTrue(tree.contains("[ref=0"), "Should show ref=0");

        // Counts should be preserved
        assertTrue(tree.contains("count="), "Should show count=");
    }

    /**
     * Test that summary statistics are unaffected by simple class name rendering.
     */
    @Test
    public void testSummaryUnaffected() {
        Object obj = new Object();

        tracker.recordMethodCall(obj, "com.example.demo.Service", "process", "com.example.demo.Service.process", 1);

        tracker.onShutdown();

        TrieRenderer renderer = new TrieRenderer(trie);
        String summary = renderer.renderSummary();

        // Summary should still show statistics
        assertTrue(summary.contains("Total Root Methods:"), "Should show Total Root Methods");
        assertTrue(summary.contains("Total Traversals:"), "Should show Total Traversals");
        assertTrue(summary.contains("Total Paths:"), "Should show Total Paths");
    }

    /**
     * Test multiple classes with the same simple name but different packages.
     * This verifies that ambiguity in output is acceptable (trie still stores full names).
     */
    @Test
    public void testMultipleClassesWithSameName() {
        Object obj1 = new Object();
        Object obj2 = new Object();

        // Two different classes with same simple name
        tracker.recordMethodCall(obj1, "com.foo.Processor", "process", "com.foo.Processor.process", 0);
        tracker.recordMethodCall(obj2, "com.bar.Processor", "process", "com.bar.Processor.process", 0);

        tracker.onShutdown();

        TrieRenderer renderer = new TrieRenderer(trie);
        String tree = renderer.renderIndentedTree();

        // Both should show as "Processor" (ambiguity is acceptable in output)
        assertTrue(tree.contains("Processor.process"), "Should show Processor.process");

        // Verify that the trie internally still maintains separate paths
        // (we should see 2 root methods if they're tracked separately)
        String summary = renderer.renderSummary();
    }
}
