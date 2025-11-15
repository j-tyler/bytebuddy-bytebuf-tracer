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
 * Regression test to prevent double instrumentation of ByteBuf lifecycle methods.
 *
 * <p><b>WHY THIS TEST EXISTS:</b>
 * The agent uses two separate transformers that could potentially both match ByteBuf classes:
 * <ul>
 *   <li><b>ByteBufTransformer:</b> Instruments application code based on include patterns.
 *       Uses specialized advice classes for performance optimization.</li>
 *   <li><b>ByteBufLifecycleTransformer:</b> Instruments ByteBuf implementation classes
 *       (via {@code hasSuperType(named("io.netty.buffer.ByteBuf"))} check) to track
 *       lifecycle methods with special semantics (e.g., release() only when refCnt→0).</li>
 * </ul>
 *
 * <p><b>THE PROBLEM:</b>
 * If a user includes {@code io.netty.buffer.*} in their agent configuration, ByteBuf
 * implementation classes would match BOTH transformers, causing methods like {@code retain()}
 * (which returns ByteBuf) to be instrumented twice with different advice classes.
 *
 * <p><b>THE SOLUTION:</b>
 * ByteBufTransformer excludes ByteBuf classes via:
 * <pre>
 * .type(config.getTypeMatcher()
 *     .and(not(hasSuperType(named("io.netty.buffer.ByteBuf")))))
 * </pre>
 * This ensures ByteBuf classes are handled exclusively by ByteBufLifecycleTransformer,
 * which correctly implements lifecycle-specific tracking semantics.
 *
 * <p><b>WHAT THIS TEST VERIFIES:</b>
 * <ul>
 *   <li>ByteBuf lifecycle methods (retain, release, etc.) appear EXACTLY ONCE in flow tree</li>
 *   <li>Lifecycle tracking works correctly (refCount changes, final release detection)</li>
 *   <li>No duplicate instrumentation even in edge case configurations</li>
 * </ul>
 *
 * <p><b>NOTE:</b> This test passes with default configuration. It serves as a regression
 * test to ensure the exclusion filter continues to work correctly.
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
    public void testNoDoubleInstrumentation() {
        // Verify the exclusion filter is working: ByteBuf methods should appear exactly once,
        // not twice (which would indicate both transformers are instrumenting them)

        ByteBuf buf = Unpooled.buffer(256);
        buf.retain();

        TrieRenderer renderer = new TrieRenderer(tracker.getTrie());
        String tree = renderer.renderIndentedTree();

        long retainCount = tree.lines()
            .filter(line -> line.contains("retain"))
            .count();

        if (retainCount > 1) {
            System.err.println("⚠️  REGRESSION: Double instrumentation detected!");
            System.err.println("⚠️  The exclusion filter in ByteBufTransformer may have been removed");
            System.err.println("⚠️  Check that ByteBuf classes are excluded via: .and(not(hasSuperType(...)))");

            fail("Double instrumentation detected. " +
                 "retain() appears " + retainCount + " times instead of once. " +
                 "This indicates the ByteBuf exclusion filter is not working.");
        } else {
            System.out.println("✓ Exclusion filter working: No double instrumentation detected");
        }

        buf.release(2);
    }
}
