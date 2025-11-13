/*
 * Copyright 2025 Justin Marsh
 * SPDX-License-Identifier: Apache-2.0
 */

package com.example.bytebuf.tracker.integration;

import com.example.bytebuf.tracker.integration.utils.AppLauncher;
import com.example.bytebuf.tracker.integration.utils.OutputVerifier;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test for optimized advice classes.
 * Verifies that specialized advice works correctly for:
 * - 0-param methods returning ByteBuf (ZeroParamByteBufReturnAdvice)
 * - 1-param methods with ByteBuf (SingleByteBufParamAdvice)
 * - 2-param methods (ByteBuf, X) (TwoParamByteBufAt0Advice)
 * - 2-param methods (X, ByteBuf) (TwoParamByteBufAt1Advice)
 * - 2-param methods (ByteBuf, ByteBuf) (TwoParamBothByteBufAdvice)
 */
public class OptimizedAdviceIT {

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
    public void testOptimizationEnabled() throws Exception {
        AppLauncher.AppResult result = launcher.launch(
            "com.example.bytebuf.tracker.integration.testapp.OptimizedAdviceApp");

        assertThat(result.isSuccess())
            .withFailMessage("Application should exit successfully. Output:\n" + result.getOutput())
            .isTrue();

        String output = result.getOutput();

        // Verify optimization is enabled (not using custom handler)
        assertThat(output)
            .withFailMessage("Should show optimization enabled")
            .contains("Optimization enabled:");

        // Verify all specialized advice classes are mentioned
        assertThat(output)
            .withFailMessage("Should mention ZeroParamByteBufReturnAdvice")
            .contains("0-param methods returning ByteBuf: Optimized");

        assertThat(output)
            .withFailMessage("Should mention SingleByteBufParamAdvice")
            .contains("1-param methods (ByteBuf): Optimized");

        assertThat(output)
            .withFailMessage("Should mention TwoParamByteBufAt0Advice")
            .contains("2-param methods (ByteBuf, X): Optimized");

        assertThat(output)
            .withFailMessage("Should mention TwoParamByteBufAt1Advice")
            .contains("2-param methods (X, ByteBuf): Optimized");

        assertThat(output)
            .withFailMessage("Should mention TwoParamBothByteBufAdvice")
            .contains("2-param methods (ByteBuf, ByteBuf): Optimized");
    }

    @Test
    public void testZeroParamAdvice() throws Exception {
        AppLauncher.AppResult result = launcher.launch(
            "com.example.bytebuf.tracker.integration.testapp.OptimizedAdviceApp");

        assertThat(result.isSuccess()).isTrue();

        OutputVerifier verifier = new OutputVerifier(result.getOutput());

        // Verify createBuffer (0-param method) is tracked
        assertThat(verifier.hasMethodInFlow("createBuffer"))
            .withFailMessage("createBuffer (0-param) should be tracked. Output:\n" + result.getOutput())
            .isTrue();
    }

    @Test
    public void testSingleParamAdvice() throws Exception {
        AppLauncher.AppResult result = launcher.launch(
            "com.example.bytebuf.tracker.integration.testapp.OptimizedAdviceApp");

        assertThat(result.isSuccess()).isTrue();

        OutputVerifier verifier = new OutputVerifier(result.getOutput());

        // Verify processBuffer (1-param method) is tracked
        assertThat(verifier.hasMethodInFlow("processBuffer"))
            .withFailMessage("processBuffer (1-param) should be tracked. Output:\n" + result.getOutput())
            .isTrue();
    }

    @Test
    public void testTwoParamByteBufAt0Advice() throws Exception {
        AppLauncher.AppResult result = launcher.launch(
            "com.example.bytebuf.tracker.integration.testapp.OptimizedAdviceApp");

        assertThat(result.isSuccess()).isTrue();

        OutputVerifier verifier = new OutputVerifier(result.getOutput());

        // Verify logBuffer (2-param: ByteBuf, String) is tracked
        assertThat(verifier.hasMethodInFlow("logBuffer"))
            .withFailMessage("logBuffer (2-param ByteBuf, String) should be tracked. Output:\n" + result.getOutput())
            .isTrue();
    }

    @Test
    public void testTwoParamByteBufAt1Advice() throws Exception {
        AppLauncher.AppResult result = launcher.launch(
            "com.example.bytebuf.tracker.integration.testapp.OptimizedAdviceApp");

        assertThat(result.isSuccess()).isTrue();

        OutputVerifier verifier = new OutputVerifier(result.getOutput());

        // Verify handleError (2-param: Exception, ByteBuf) is tracked
        assertThat(verifier.hasMethodInFlow("handleError"))
            .withFailMessage("handleError (2-param Exception, ByteBuf) should be tracked. Output:\n" + result.getOutput())
            .isTrue();
    }

    @Test
    public void testTwoParamBothByteBufAdvice() throws Exception {
        AppLauncher.AppResult result = launcher.launch(
            "com.example.bytebuf.tracker.integration.testapp.OptimizedAdviceApp");

        assertThat(result.isSuccess()).isTrue();

        OutputVerifier verifier = new OutputVerifier(result.getOutput());

        // Verify mergeBuffers (2-param: ByteBuf, ByteBuf) is tracked
        assertThat(verifier.hasMethodInFlow("mergeBuffers"))
            .withFailMessage("mergeBuffers (2-param ByteBuf, ByteBuf) should be tracked. Output:\n" + result.getOutput())
            .isTrue();
    }

    @Test
    public void testAllMethodsTracked() throws Exception {
        AppLauncher.AppResult result = launcher.launch(
            "com.example.bytebuf.tracker.integration.testapp.OptimizedAdviceApp");

        assertThat(result.isSuccess()).isTrue();

        OutputVerifier verifier = new OutputVerifier(result.getOutput());

        // Verify all methods are tracked
        assertThat(verifier.hasMethodInFlow("createBuffer"))
            .withFailMessage("createBuffer should be tracked")
            .isTrue();

        assertThat(verifier.hasMethodInFlow("processBuffer"))
            .withFailMessage("processBuffer should be tracked")
            .isTrue();

        assertThat(verifier.hasMethodInFlow("logBuffer"))
            .withFailMessage("logBuffer should be tracked")
            .isTrue();

        assertThat(verifier.hasMethodInFlow("handleError"))
            .withFailMessage("handleError should be tracked")
            .isTrue();

        assertThat(verifier.hasMethodInFlow("mergeBuffers"))
            .withFailMessage("mergeBuffers should be tracked")
            .isTrue();

        // Verify release is tracked (cleanup)
        assertThat(verifier.hasMethodInFlow("release"))
            .withFailMessage("release should be tracked")
            .isTrue();
    }

    @Test
    public void testProperCleanup() throws Exception {
        AppLauncher.AppResult result = launcher.launch(
            "com.example.bytebuf.tracker.integration.testapp.OptimizedAdviceApp");

        assertThat(result.isSuccess()).isTrue();

        OutputVerifier verifier = new OutputVerifier(result.getOutput());

        // Should track the release() calls
        assertThat(verifier.hasMethodInFlow("release"))
            .withFailMessage("Should track release() calls showing cleanup occurred")
            .isTrue();

        // Should have proper cleanup marker (ref=0)
        assertThat(verifier.hasProperCleanup())
            .withFailMessage("Should have ref=0 showing ByteBufs were released")
            .isTrue();
    }

    @Test
    public void testNoErrors() throws Exception {
        AppLauncher.AppResult result = launcher.launch(
            "com.example.bytebuf.tracker.integration.testapp.OptimizedAdviceApp");

        assertThat(result.isSuccess()).isTrue();

        String output = result.getOutput().toLowerCase();

        // Should not have any errors
        assertThat(output)
            .withFailMessage("Should not contain unexpected errors")
            .doesNotContain("nullpointerexception")
            .doesNotContain("classnotfoundexception")
            .doesNotContain("failed to");
    }

    @Test
    public void testExpectedOutput() throws Exception {
        AppLauncher.AppResult result = launcher.launch(
            "com.example.bytebuf.tracker.integration.testapp.OptimizedAdviceApp");

        assertThat(result.isSuccess()).isTrue();

        String output = result.getOutput();

        // Verify expected application output
        assertThat(output)
            .withFailMessage("Should show test app started")
            .contains("=== Optimized Advice Test App ===");

        assertThat(output)
            .withFailMessage("Should show test app completed")
            .contains("=== Test Complete ===");

        assertThat(output)
            .withFailMessage("Should show 0-param method executed")
            .contains("Created buffer via 0-param method");

        assertThat(output)
            .withFailMessage("Should show 1-param method executed")
            .contains("Processed buffer via 1-param method");

        assertThat(output)
            .withFailMessage("Should show 2-param (ByteBuf, X) method executed")
            .contains("Logged buffer via 2-param method (ByteBuf, String)");

        assertThat(output)
            .withFailMessage("Should show 2-param (X, ByteBuf) method executed")
            .contains("Handled error via 2-param method (Exception, ByteBuf)");

        assertThat(output)
            .withFailMessage("Should show 2-param (ByteBuf, ByteBuf) method executed")
            .contains("Merged buffers via 2-param method (ByteBuf, ByteBuf)");

        assertThat(output)
            .withFailMessage("Should show buffers released")
            .contains("Released all buffers");
    }
}
