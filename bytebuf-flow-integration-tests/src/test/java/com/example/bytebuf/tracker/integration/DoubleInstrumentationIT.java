package com.example.bytebuf.tracker.integration;

import com.example.bytebuf.tracker.ByteBufFlowTracker;
import com.example.bytebuf.tracker.view.TrieRenderer;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test to verify Issue #1: Potential double instrumentation when
 * io.netty.buffer.* is explicitly included in agent configuration.
 *
 * <p><b>Background:</b> When users include io.netty.buffer.* packages, ByteBuf
 * implementation classes get matched by BOTH:
 * <ul>
 *   <li>ByteBufTransformer (via include pattern + hasByteBufInSignature check)</li>
 *   <li>ByteBufLifecycleTransformer (via hasSuperType check + method name)</li>
 * </ul>
 *
 * <p><b>Affected Methods:</b>
 * <ul>
 *   <li>retain() - returns ByteBuf (matches both transformers)</li>
 *   <li>retainedDuplicate() - returns ByteBuf (matches both transformers)</li>
 *   <li>retainedSlice() - returns ByteBuf (matches both transformers)</li>
 *   <li>release() - returns boolean (matches only ByteBufLifecycleTransformer) ✓ SAFE</li>
 * </ul>
 *
 * <p><b>Test Setup:</b>
 * This test is designed to PASS with default configuration (where io.netty.buffer.*
 * is NOT included), and potentially FAIL or show duplicate entries if run with:
 * <pre>
 * -javaagent:tracker.jar=include=io.netty.buffer.*;include=com.example.*
 * </pre>
 *
 * <p><b>Expected Behavior:</b>
 * <ul>
 *   <li>Each method call should appear EXACTLY ONCE in the flow tree</li>
 *   <li>retain() should show refCount tracking (from ByteBufLifecycleAdvice)</li>
 *   <li>No duplicate entries even if both transformers apply</li>
 * </ul>
 *
 * <p><b>Failure Modes:</b>
 * <ul>
 *   <li>If method appears TWICE: Both transformers instrumented it</li>
 *   <li>If IS_TRACKING guard prevents second: Only first transformer's behavior seen</li>
 *   <li>If second transformer overwrites: Correct behavior, but wasteful</li>
 * </ul>
 */
public class DoubleInstrumentationIT {

    private ByteBufFlowTracker tracker;

    @BeforeEach
    public void setUp() {
        tracker = ByteBufFlowTracker.getInstance();
        tracker.reset();
    }

    @AfterEach
    public void tearDown() {
        tracker.reset();
    }

    @Test
    public void testRetainNotDoubleInstrumented() {
        // Create buffer
        ByteBuf buf = Unpooled.buffer(256);
        assertEquals(1, buf.refCnt(), "Initial refCount should be 1");

        // Call retain() - this returns ByteBuf
        // If io.netty.buffer.* is included, this could match BOTH transformers:
        // 1. ByteBufTransformer: hasByteBufInSignature() matches (returns ByteBuf)
        // 2. ByteBufLifecycleTransformer: named("retain") matches
        ByteBuf retained = buf.retain();
        assertEquals(2, buf.refCnt(), "After retain(), refCount should be 2");

        assertSame(buf, retained, "retain() should return same instance");

        // Check flow tree
        TrieRenderer renderer = new TrieRenderer(tracker.getTrie());
        String tree = renderer.renderIndentedTree();

        System.out.println("=== Flow Tree After retain() ===");
        System.out.println(tree);
        System.out.println("================================\n");

        // Verify retain() appears in tree
        assertTrue(tree.contains("retain"), "Tree should contain retain() call");

        // Count occurrences of "retain" in tree
        // This is the KEY assertion - should be exactly 1, not 2
        long retainCount = tree.lines()
            .filter(line -> line.contains("retain"))
            .count();

        System.out.println("Number of 'retain' entries found: " + retainCount);

        // CRITICAL ASSERTION: Should appear exactly ONCE
        // If this fails, it indicates double instrumentation
        assertEquals(1, retainCount,
            "retain() should appear exactly ONCE in flow tree. " +
            "Multiple entries indicate double instrumentation from both transformers. " +
            "Check if io.netty.buffer.* is included in agent configuration.");

        // Verify refCount tracking is working (indicates ByteBufLifecycleAdvice applied)
        assertTrue(tree.contains("ref=2") || tree.contains("refCount"),
            "Tree should show refCount tracking from ByteBufLifecycleAdvice");

        // Cleanup
        buf.release(2);
    }

    @Test
    public void testRetainedDuplicateNotDoubleInstrumented() {
        ByteBuf buf = Unpooled.buffer(256);

        // Call retainedDuplicate() - returns ByteBuf
        // Same potential double instrumentation issue as retain()
        ByteBuf duplicated = buf.retainedDuplicate();
        assertEquals(2, buf.refCnt(), "retainedDuplicate() increases refCount");

        TrieRenderer renderer = new TrieRenderer(tracker.getTrie());
        String tree = renderer.renderIndentedTree();

        System.out.println("=== Flow Tree After retainedDuplicate() ===");
        System.out.println(tree);
        System.out.println("===========================================\n");

        // Count occurrences
        long count = tree.lines()
            .filter(line -> line.contains("retainedDuplicate"))
            .count();

        System.out.println("Number of 'retainedDuplicate' entries found: " + count);

        assertEquals(1, count,
            "retainedDuplicate() should appear exactly ONCE in flow tree");

        // Cleanup
        buf.release();
        duplicated.release();
    }

    @Test
    public void testReleaseNotDoubleInstrumented() {
        // release() returns boolean, NOT ByteBuf
        // So it should ONLY match ByteBufLifecycleTransformer
        // This test should ALWAYS pass (no double instrumentation possible)

        ByteBuf buf = Unpooled.buffer(256);

        boolean released = buf.release();
        assertTrue(released, "release() should return true");
        assertEquals(0, buf.refCnt(), "After release(), refCount should be 0");

        TrieRenderer renderer = new TrieRenderer(tracker.getTrie());
        String tree = renderer.renderIndentedTree();

        System.out.println("=== Flow Tree After release() ===");
        System.out.println(tree);
        System.out.println("=================================\n");

        // release() should appear exactly once
        long count = tree.lines()
            .filter(line -> line.contains("release"))
            .count();

        System.out.println("Number of 'release' entries found: " + count);

        assertEquals(1, count,
            "release() should appear exactly ONCE (no double instrumentation expected)");

        // Verify it shows ref=0 (final release tracking)
        assertTrue(tree.contains("ref=0"),
            "Tree should show ref=0 for final release");
    }

    @Test
    public void testDetectDoubleInstrumentationConfiguration() {
        // This test attempts to detect if we're running with the edge case configuration
        // that triggers double instrumentation

        // Unfortunately, we can't directly query agent configuration at runtime
        // But we can infer it from behavior

        ByteBuf buf = Unpooled.buffer(256);
        buf.retain();

        TrieRenderer renderer = new TrieRenderer(tracker.getTrie());
        String tree = renderer.renderIndentedTree();

        long retainCount = tree.lines()
            .filter(line -> line.contains("retain"))
            .count();

        if (retainCount > 1) {
            System.err.println("⚠️  WARNING: Double instrumentation detected!");
            System.err.println("⚠️  This indicates io.netty.buffer.* is included in agent configuration");
            System.err.println("⚠️  Recommendation: Only include application packages, not Netty internals");
            System.err.println("⚠️  See Issue #1 in code review for details");

            fail("Double instrumentation detected. " +
                 "retain() appears " + retainCount + " times instead of once. " +
                 "This indicates a configuration issue where io.netty.buffer.* is included.");
        } else {
            System.out.println("✓ Configuration OK: No double instrumentation detected");
        }

        buf.release(2);
    }
}
