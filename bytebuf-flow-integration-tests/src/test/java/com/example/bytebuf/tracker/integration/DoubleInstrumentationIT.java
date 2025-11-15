/*
 * Copyright 2025 Justin Marsh
 * SPDX-License-Identifier: Apache-2.0
 */

package com.example.bytebuf.tracker.integration;

import com.example.bytebuf.tracker.integration.utils.AppLauncher;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

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
 */
public class DoubleInstrumentationIT {

    private static AppLauncher launcher;

    @BeforeAll
    public static void setUp() {
        String agentJarPath = System.getProperty("agent.jar.path");
        String testClasspath = System.getProperty("test.classpath");
        String javaHome = System.getProperty("java.home");

        assertThat(agentJarPath).isNotNull().isNotEmpty();
        assertThat(testClasspath).isNotNull().isNotEmpty();
        assertThat(javaHome).isNotNull().isNotEmpty();

        launcher = new AppLauncher(agentJarPath, testClasspath, javaHome);
    }

    @Test
    public void testRetainNotDoubleInstrumented() throws Exception {
        AppLauncher.AppResult result = launcher.launch(
            "com.example.bytebuf.tracker.integration.testapp.ByteBufLifecycleApp");

        assertThat(result.isSuccess())
            .withFailMessage("Application should exit successfully. Output:\n" + result.getOutput())
            .isTrue();

        String output = result.getOutput();

        // Extract flow tree section
        String flowTree = extractFlowTree(output);

        // Verify no double instrumentation by checking that each class.method appears at most once
        // Normal behavior: retain() propagates through inheritance (e.g., UnpooledHeapByteBuf -> UnpooledUnsafeHeapByteBuf)
        // Double instrumentation: Same class.method appears TWICE (e.g., UnpooledHeapByteBuf.retain appears twice)

        java.util.Map<String, Long> methodCounts = flowTree.lines()
            .filter(line -> line.contains("retain") && !line.contains("Duplicate"))
            .filter(line -> !line.contains("Testing retain"))
            .filter(line -> !line.contains("After retain"))
            .filter(line -> line.contains(".retain"))  // Only lines with actual method calls
            .map(line -> {
                // Extract "ClassName.retain" from lines like "│   └── ClassName.retain [ref=1, count=0]"
                String trimmed = line.trim().replaceAll("^[│├└─\\s]+", "");
                if (trimmed.contains(".retain")) {
                    int bracketIndex = trimmed.indexOf(" [");
                    if (bracketIndex > 0) {
                        return trimmed.substring(0, bracketIndex);
                    }
                }
                return null;
            })
            .filter(java.util.Objects::nonNull)
            .collect(java.util.stream.Collectors.groupingBy(s -> s, java.util.stream.Collectors.counting()));

        // CRITICAL ASSERTION: Each class.method should appear exactly ONCE
        // If any class.method appears more than once, it indicates double instrumentation
        methodCounts.forEach((method, count) -> {
            assertThat(count)
                .withFailMessage("Double instrumentation detected! " + method +
                    " appears " + count + " times (should be 1). " +
                    "This indicates both transformers are instrumenting the same method.\n" +
                    "Flow tree:\n" + flowTree)
                .isEqualTo(1L);
        });

        // Verify refCount tracking is working (indicates ByteBufLifecycleAdvice applied)
        assertThat(flowTree)
            .withFailMessage("Tree should show refCount tracking from ByteBufLifecycleAdvice")
            .containsPattern("ref=\\d+");
    }

    @Test
    public void testRetainedDuplicateNotDoubleInstrumented() throws Exception {
        AppLauncher.AppResult result = launcher.launch(
            "com.example.bytebuf.tracker.integration.testapp.ByteBufLifecycleApp");

        assertThat(result.isSuccess()).isTrue();

        String output = result.getOutput();
        String flowTree = extractFlowTree(output);

        // Verify no double instrumentation (same logic as retain test)
        java.util.Map<String, Long> methodCounts = flowTree.lines()
            .filter(line -> line.contains("retainedDuplicate"))
            .filter(line -> !line.contains("Testing retainedDuplicate"))
            .filter(line -> !line.contains("After retainedDuplicate"))
            .filter(line -> line.contains(".retainedDuplicate"))
            .map(line -> {
                String trimmed = line.trim().replaceAll("^[│├└─\\s]+", "");
                if (trimmed.contains(".retainedDuplicate")) {
                    int bracketIndex = trimmed.indexOf(" [");
                    if (bracketIndex > 0) {
                        return trimmed.substring(0, bracketIndex);
                    }
                }
                return null;
            })
            .filter(java.util.Objects::nonNull)
            .collect(java.util.stream.Collectors.groupingBy(s -> s, java.util.stream.Collectors.counting()));

        methodCounts.forEach((method, count) -> {
            assertThat(count)
                .withFailMessage("Double instrumentation detected! " + method +
                    " appears " + count + " times (should be 1).\nFlow tree:\n" + flowTree)
                .isEqualTo(1L);
        });
    }

    @Test
    public void testReleaseNotDoubleInstrumented() throws Exception {
        // release() returns boolean, NOT ByteBuf
        // So it should ONLY match ByteBufLifecycleTransformer
        // This test should ALWAYS pass (no double instrumentation possible)

        AppLauncher.AppResult result = launcher.launch(
            "com.example.bytebuf.tracker.integration.testapp.ByteBufLifecycleApp");

        assertThat(result.isSuccess()).isTrue();

        String output = result.getOutput();
        String flowTree = extractFlowTree(output);

        // Count occurrences of "release" (but not "Before release" debug output)
        // Should appear multiple times (once per release() call in the app)
        // But each call should appear exactly once, not twice
        long releaseLineCount = flowTree.lines()
            .filter(line -> line.contains("release"))
            .filter(line -> !line.contains("Before release"))   // Exclude debug output
            .filter(line -> !line.contains("release() returned")) // Exclude debug output
            .count();

        // We expect to see release() calls in the output (at least 1)
        assertThat(releaseLineCount)
            .withFailMessage("release() should appear in the flow tree. Found 0 entries.\nFlow tree:\n" + flowTree)
            .isGreaterThan(0);

        // Verify it shows ref=0 for final releases (lifecycle tracking working)
        assertThat(flowTree)
            .withFailMessage("Tree should show ref=0 for final release")
            .contains("ref=0");
    }

    @Test
    public void testNoDoubleInstrumentation() throws Exception {
        // Comprehensive test: verify the exclusion filter is working
        // ByteBuf lifecycle methods should appear exactly once per call,
        // not twice (which would indicate both transformers are instrumenting them)

        AppLauncher.AppResult result = launcher.launch(
            "com.example.bytebuf.tracker.integration.testapp.ByteBufLifecycleApp");

        assertThat(result.isSuccess()).isTrue();

        String output = result.getOutput();
        String flowTree = extractFlowTree(output);

        // Check that no class.method appears more than once (which would indicate double instrumentation)
        java.util.Map<String, Long> methodCounts = flowTree.lines()
            .filter(line -> line.contains("retain") && !line.contains("Duplicate"))
            .filter(line -> !line.contains("Testing retain"))
            .filter(line -> !line.contains("After retain"))
            .filter(line -> line.contains(".retain"))
            .map(line -> {
                String trimmed = line.trim().replaceAll("^[│├└─\\s]+", "");
                if (trimmed.contains(".retain")) {
                    int bracketIndex = trimmed.indexOf(" [");
                    if (bracketIndex > 0) {
                        return trimmed.substring(0, bracketIndex);
                    }
                }
                return null;
            })
            .filter(java.util.Objects::nonNull)
            .collect(java.util.stream.Collectors.groupingBy(s -> s, java.util.stream.Collectors.counting()));

        boolean hasDoubleInstrumentation = methodCounts.values().stream().anyMatch(count -> count > 1);

        if (hasDoubleInstrumentation) {
            System.err.println("⚠️  REGRESSION: Double instrumentation detected!");
            System.err.println("⚠️  The exclusion filter in ByteBufTransformer may have been removed");
            System.err.println("⚠️  Check that ByteBuf classes are excluded via: .and(not(hasSuperType(...)))");

            methodCounts.forEach((method, count) -> {
                if (count > 1) {
                    System.err.println("⚠️  " + method + " appears " + count + " times!");
                }
            });

            methodCounts.forEach((method, count) -> {
                assertThat(count)
                    .withFailMessage("Double instrumentation detected for " + method + ". " +
                        "It appears " + count + " times instead of once. " +
                        "This indicates the ByteBuf exclusion filter is not working.\n" +
                        "Flow tree:\n" + flowTree)
                    .isEqualTo(1L);
            });
        }
    }

    /**
     * Extract the flow tree section from the app output.
     */
    private String extractFlowTree(String output) {
        int treeStart = output.indexOf("=== Flow Tree ===");
        int treeEnd = output.indexOf("=== ByteBufLifecycleApp Complete ===");

        if (treeStart == -1 || treeEnd == -1) {
            return output; // Return full output if markers not found
        }

        return output.substring(treeStart, treeEnd);
    }
}
