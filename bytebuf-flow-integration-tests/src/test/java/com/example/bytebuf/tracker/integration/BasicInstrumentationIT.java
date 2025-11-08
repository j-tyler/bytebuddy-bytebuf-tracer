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
 * Integration test for basic bytecode instrumentation.
 * Verifies that the agent loads, instruments methods, and tracks ByteBuf flow.
 */
public class BasicInstrumentationIT {

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
    public void testAgentLoads() throws Exception {
        AppLauncher.AppResult result = launcher.launch(
            "com.example.bytebuf.tracker.integration.testapp.BasicFlowApp");

        assertThat(result.isSuccess())
            .withFailMessage("Application should exit successfully. Output:\n" + result.getOutput())
            .isTrue();

        OutputVerifier verifier = new OutputVerifier(result.getOutput());

        // Verify agent started
        assertThat(verifier.hasAgentStarted())
            .withFailMessage("Agent should start. Output:\n" + result.getOutput())
            .isTrue();

        // Verify instrumentation installed
        assertThat(verifier.hasInstrumentationInstalled())
            .withFailMessage("Instrumentation should be installed. Output:\n" + result.getOutput())
            .isTrue();

        // Verify JMX registered
        assertThat(verifier.hasJmxRegistered())
            .withFailMessage("JMX MBean should be registered. Output:\n" + result.getOutput())
            .isTrue();
    }

    @Test
    public void testMethodsAreTracked() throws Exception {
        AppLauncher.AppResult result = launcher.launch(
            "com.example.bytebuf.tracker.integration.testapp.BasicFlowApp");

        assertThat(result.isSuccess()).isTrue();

        OutputVerifier verifier = new OutputVerifier(result.getOutput());

        // Verify all methods appear in the flow
        assertThat(verifier.hasMethodInFlow("allocate"))
            .withFailMessage("allocate method should be tracked")
            .isTrue();

        assertThat(verifier.hasMethodInFlow("processStep1"))
            .withFailMessage("processStep1 method should be tracked")
            .isTrue();

        assertThat(verifier.hasMethodInFlow("processStep2"))
            .withFailMessage("processStep2 method should be tracked")
            .isTrue();

        assertThat(verifier.hasMethodInFlow("processStep3"))
            .withFailMessage("processStep3 method should be tracked")
            .isTrue();

        assertThat(verifier.hasMethodInFlow("cleanup"))
            .withFailMessage("cleanup method should be tracked")
            .isTrue();
    }

    @Test
    public void testFlowStructure() throws Exception {
        AppLauncher.AppResult result = launcher.launch(
            "com.example.bytebuf.tracker.integration.testapp.BasicFlowApp");

        assertThat(result.isSuccess()).isTrue();

        OutputVerifier verifier = new OutputVerifier(result.getOutput());

        // Should have exactly 1 root (allocate is the first method)
        assertThat(verifier.getTotalRootMethods())
            .withFailMessage("Should have exactly 1 root method")
            .isEqualTo(1);

        // Should have 1 complete traversal
        assertThat(verifier.getTotalTraversals())
            .withFailMessage("Should have 1 traversal")
            .isEqualTo(1);

        // Should have 1 unique path
        assertThat(verifier.getTotalPaths())
            .withFailMessage("Should have 1 unique path")
            .isEqualTo(1);

        // Should have no leaks
        assertThat(verifier.getLeakPaths())
            .withFailMessage("Should have no leak paths")
            .isEqualTo(0);
    }

    @Test
    public void testProperCleanup() throws Exception {
        AppLauncher.AppResult result = launcher.launch(
            "com.example.bytebuf.tracker.integration.testapp.BasicFlowApp");

        assertThat(result.isSuccess()).isTrue();

        OutputVerifier verifier = new OutputVerifier(result.getOutput());

        // Should track the release() call, indicating cleanup occurred
        assertThat(verifier.hasMethodInFlow("release"))
            .withFailMessage("Should track release() call showing cleanup occurred")
            .isTrue();

        // Should have no leak paths (proper cleanup means no leaks)
        assertThat(verifier.getLeakPaths())
            .withFailMessage("Should have 0 leak paths when ByteBuf is properly released")
            .isEqualTo(0);

        // Should NOT have leak markers in the output
        assertThat(verifier.hasLeakDetected())
            .withFailMessage("Should not detect any leaks when ByteBuf is properly released")
            .isFalse();
    }

    @Test
    public void testNoErrors() throws Exception {
        AppLauncher.AppResult result = launcher.launch(
            "com.example.bytebuf.tracker.integration.testapp.BasicFlowApp");

        assertThat(result.isSuccess()).isTrue();

        OutputVerifier verifier = new OutputVerifier(result.getOutput());

        // Should not have any errors (excluding expected log messages)
        String output = verifier.getOutput().toLowerCase();
        assertThat(output)
            .withFailMessage("Should not contain unexpected errors")
            .doesNotContain("nullpointerexception")
            .doesNotContain("classnotfoundexception")
            .doesNotContain("failed to");
    }
}
