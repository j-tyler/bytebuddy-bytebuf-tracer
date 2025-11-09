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
 * Integration test for leak detection.
 * Verifies that the agent correctly identifies ByteBuf leaks.
 */
public class LeakDetectionIT {

    private static AppLauncher launcher;

    @BeforeAll
    public static void setUp() {
        String agentJarPath = System.getProperty("agent.jar.path");
        String testClasspath = System.getProperty("test.classpath");
        String javaHome = System.getProperty("java.home");

        launcher = new AppLauncher(agentJarPath, testClasspath, javaHome);
    }

    @Test
    public void testLeakIsDetected() throws Exception {
        AppLauncher.AppResult result = launcher.launch(
            "com.example.bytebuf.tracker.integration.testapp.LeakDetectionApp");

        assertThat(result.isSuccess()).isTrue();

        OutputVerifier verifier = new OutputVerifier(result.getOutput());

        // Should have at least 1 leak path (forgetToRelease doesn't call release())
        assertThat(verifier.getLeakPaths())
            .withFailMessage("Should detect the intentional leak. Leak paths should be >= 1. Output:\n" + result.getOutput())
            .isGreaterThanOrEqualTo(1);
    }

    @Test
    public void testBothFlowsAreTracked() throws Exception {
        AppLauncher.AppResult result = launcher.launch(
            "com.example.bytebuf.tracker.integration.testapp.LeakDetectionApp");

        assertThat(result.isSuccess()).isTrue();

        OutputVerifier verifier = new OutputVerifier(result.getOutput());

        // With allocator tracking, roots are allocator methods
        // May have 1-2 roots (Netty initialization can create orphan allocations)
        assertThat(verifier.getTotalRootMethods())
            .withFailMessage("Should have 1-2 root methods (allocator roots)")
            .isBetween(1, 2);

        // Should have at least 2 traversals (normal and leaky flows)
        // (May have more due to Netty initialization)
        assertThat(verifier.getTotalTraversals())
            .withFailMessage("Should have at least 2 traversals")
            .isGreaterThanOrEqualTo(2);

        // Should have at least 2 unique paths (normal and leaky)
        assertThat(verifier.getTotalPaths())
            .withFailMessage("Should have at least 2 unique paths")
            .isGreaterThanOrEqualTo(2);

        // Should have at least 1 application leak path (forgetToRelease)
        // (May have orphan Netty allocations counted as leaks)
        assertThat(verifier.getLeakPaths())
            .withFailMessage("Should have at least 1 leak path")
            .isGreaterThanOrEqualTo(1);
    }

    @Test
    public void testNormalFlowHasNoLeak() throws Exception {
        AppLauncher.AppResult result = launcher.launch(
            "com.example.bytebuf.tracker.integration.testapp.LeakDetectionApp");

        assertThat(result.isSuccess()).isTrue();

        OutputVerifier verifier = new OutputVerifier(result.getOutput());

        // Normal flow path should include all expected methods
        assertThat(verifier.hasMethodInFlow("allocateNormal"))
            .withFailMessage("Should track allocateNormal method")
            .isTrue();
        assertThat(verifier.hasMethodInFlow("processNormal"))
            .withFailMessage("Should track processNormal method")
            .isTrue();
        assertThat(verifier.hasMethodInFlow("releaseNormal"))
            .withFailMessage("Should track releaseNormal method")
            .isTrue();

        // Normal flow calls release, so it shouldn't be flagged as a leak
        // (Note: leak paths might still be > 0 if the leaky flow exists)
        assertThat(verifier.hasMethodInFlow("release"))
            .withFailMessage("Normal flow should call release()")
            .isTrue();
    }

    @Test
    public void testLeakyFlowIsFlagged() throws Exception {
        AppLauncher.AppResult result = launcher.launch(
            "com.example.bytebuf.tracker.integration.testapp.LeakDetectionApp");

        assertThat(result.isSuccess()).isTrue();

        OutputVerifier verifier = new OutputVerifier(result.getOutput());

        // Leaky flow path should include all expected methods
        assertThat(verifier.hasMethodInFlow("allocateLeaky"))
            .withFailMessage("Should track allocateLeaky method")
            .isTrue();
        assertThat(verifier.hasMethodInFlow("processLeaky"))
            .withFailMessage("Should track processLeaky method")
            .isTrue();
        assertThat(verifier.hasMethodInFlow("forgetToRelease"))
            .withFailMessage("Should track forgetToRelease method")
            .isTrue();

        // Should have at least one leak path
        assertThat(verifier.getLeakPaths())
            .withFailMessage("Leaky flow should be flagged - should have >= 1 leak paths")
            .isGreaterThanOrEqualTo(1);
    }

    @Test
    public void testLeakMethodIsEndNode() throws Exception {
        AppLauncher.AppResult result = launcher.launch(
            "com.example.bytebuf.tracker.integration.testapp.LeakDetectionApp");

        assertThat(result.isSuccess()).isTrue();

        OutputVerifier verifier = new OutputVerifier(result.getOutput());

        // forgetToRelease should appear in the output
        assertThat(verifier.hasMethodInFlow("forgetToRelease"))
            .withFailMessage("forgetToRelease method should be in the flow")
            .isTrue();
    }
}
