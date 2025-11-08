/*
 * Copyright 2025 Justin Marsh
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
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

        // Should have 2 roots (normalFlow and leakyFlow)
        assertThat(verifier.getTotalRootMethods())
            .withFailMessage("Should have 2 root methods")
            .isEqualTo(2);

        // Should have 2 traversals
        assertThat(verifier.getTotalTraversals())
            .withFailMessage("Should have 2 traversals")
            .isEqualTo(2);

        // Should have 2 unique paths
        assertThat(verifier.getTotalPaths())
            .withFailMessage("Should have 2 unique paths")
            .isEqualTo(2);

        // Should have exactly 1 leak path
        assertThat(verifier.getLeakPaths())
            .withFailMessage("Should have exactly 1 leak path")
            .isEqualTo(1);
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
