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

        // With ByteBuf construction tracking, roots are allocator methods
        // May have 1-2 roots (Netty initialization can create orphan allocations)
        assertThat(verifier.getTotalRootMethods())
            .withFailMessage("Should have 1-2 root methods (allocator roots)")
            .isBetween(1, 2);

        // Verify we have at least one root (allocator method)
        // Don't be too specific about which allocator method, as it varies
        assertThat(verifier.getTotalRootMethods())
            .withFailMessage("Should have at least 1 root method")
            .isGreaterThan(0);

        // Should have 1 complete traversal for the application flow
        assertThat(verifier.getTotalTraversals())
            .withFailMessage("Should have at least 1 traversal")
            .isGreaterThanOrEqualTo(1);

        // Should have at least 1 complete path from allocation to release
        assertThat(verifier.getTotalPaths())
            .withFailMessage("Should have at least 1 unique path")
            .isGreaterThanOrEqualTo(1);

        // The main application flow should have no leaks
        // (May have orphan Netty initialization allocations)
        assertThat(verifier.hasMethodInFlow("release"))
            .withFailMessage("Should track release() showing proper cleanup")
            .isTrue();
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

        // Should have proper cleanup marker (ref=0)
        assertThat(verifier.hasProperCleanup())
            .withFailMessage("Should have ref=0 showing ByteBuf was released")
            .isTrue();

        // Verify the flow shows allocate_return -> process -> cleanup -> release
        String flowTree = verifier.getFlowTree();
        assertThat(flowTree)
            .withFailMessage("Flow tree should show complete path to release")
            .contains("allocate")
            .contains("processStep")
            .contains("cleanup")
            .contains("release")
            .contains("[ref=0");
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
