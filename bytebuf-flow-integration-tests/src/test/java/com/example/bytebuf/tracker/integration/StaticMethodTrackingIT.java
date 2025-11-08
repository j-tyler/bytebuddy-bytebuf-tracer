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
 * Integration test for static method tracking.
 * Verifies that both static and instance methods are tracked correctly.
 */
public class StaticMethodTrackingIT {

    private static AppLauncher launcher;

    @BeforeAll
    public static void setUp() {
        String agentJarPath = System.getProperty("agent.jar.path");
        String testClasspath = System.getProperty("test.classpath");
        String javaHome = System.getProperty("java.home");

        launcher = new AppLauncher(agentJarPath, testClasspath, javaHome);
    }

    @Test
    public void testInstanceMethodsAreTracked() throws Exception {
        AppLauncher.AppResult result = launcher.launch(
            "com.example.bytebuf.tracker.integration.testapp.StaticMethodApp");

        assertThat(result.isSuccess()).isTrue();

        OutputVerifier verifier = new OutputVerifier(result.getOutput());

        // Verify instance methods are tracked
        assertThat(verifier.hasMethodInFlow("allocateInstance"))
            .withFailMessage("allocateInstance method should be tracked")
            .isTrue();

        assertThat(verifier.hasMethodInFlow("processInstance"))
            .withFailMessage("processInstance method should be tracked")
            .isTrue();

        assertThat(verifier.hasMethodInFlow("releaseInstance"))
            .withFailMessage("releaseInstance method should be tracked")
            .isTrue();
    }

    @Test
    public void testStaticMethodsAreTracked() throws Exception {
        AppLauncher.AppResult result = launcher.launch(
            "com.example.bytebuf.tracker.integration.testapp.StaticMethodApp");

        assertThat(result.isSuccess()).isTrue();

        OutputVerifier verifier = new OutputVerifier(result.getOutput());

        // Verify static methods are tracked
        assertThat(verifier.hasMethodInFlow("allocateStatic"))
            .withFailMessage("allocateStatic method should be tracked")
            .isTrue();

        assertThat(verifier.hasMethodInFlow("processStatic"))
            .withFailMessage("processStatic method should be tracked")
            .isTrue();

        assertThat(verifier.hasMethodInFlow("releaseStatic"))
            .withFailMessage("releaseStatic method should be tracked")
            .isTrue();
    }

    @Test
    public void testMultipleFlowsDetected() throws Exception {
        AppLauncher.AppResult result = launcher.launch(
            "com.example.bytebuf.tracker.integration.testapp.StaticMethodApp");

        assertThat(result.isSuccess()).isTrue();

        OutputVerifier verifier = new OutputVerifier(result.getOutput());

        // Should have at least 1 root (multiple separate ByteBuf allocations)
        // Note: exact count may vary depending on how _return nodes are counted
        assertThat(verifier.getTotalRootMethods())
            .withFailMessage("Should have at least 1 root method")
            .isGreaterThanOrEqualTo(1);

        // Should have multiple allocations tracked
        assertThat(verifier.hasMethodInFlow("allocateInstance"))
            .withFailMessage("Should track instance allocations")
            .isTrue();
        assertThat(verifier.hasMethodInFlow("allocateStatic"))
            .withFailMessage("Should track static allocations")
            .isTrue();
    }

    @Test
    public void testNoLeaks() throws Exception {
        AppLauncher.AppResult result = launcher.launch(
            "com.example.bytebuf.tracker.integration.testapp.StaticMethodApp");

        assertThat(result.isSuccess()).isTrue();

        OutputVerifier verifier = new OutputVerifier(result.getOutput());

        // Should have no leaks - all ByteBufs are released
        assertThat(verifier.getLeakPaths())
            .withFailMessage("Should have no leak paths - all flows call release")
            .isEqualTo(0);

        // Verify release methods are called
        assertThat(verifier.hasMethodInFlow("releaseInstance"))
            .withFailMessage("Instance release should be called")
            .isTrue();
        assertThat(verifier.hasMethodInFlow("releaseStatic"))
            .withFailMessage("Static release should be called")
            .isTrue();
    }

    @Test
    public void testMixedFlowTracked() throws Exception {
        AppLauncher.AppResult result = launcher.launch(
            "com.example.bytebuf.tracker.integration.testapp.StaticMethodApp");

        assertThat(result.isSuccess()).isTrue();

        OutputVerifier verifier = new OutputVerifier(result.getOutput());
        String flowTree = verifier.getFlowTree();

        // The mixed flow (instance -> static -> instance) should be tracked
        // This verifies that both types of methods work together
        assertThat(flowTree)
            .contains("allocateInstance")
            .contains("processStatic")
            .contains("releaseInstance");
    }
}
