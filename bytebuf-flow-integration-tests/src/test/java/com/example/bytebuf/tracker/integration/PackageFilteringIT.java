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
 * Integration test for package filtering (include/exclude config).
 * Verifies that only specified packages are instrumented.
 */
public class PackageFilteringIT {

    private static AppLauncher launcher;

    @BeforeAll
    public static void setUp() {
        String agentJarPath = System.getProperty("agent.jar.path");
        String testClasspath = System.getProperty("test.classpath");
        String javaHome = System.getProperty("java.home");

        launcher = new AppLauncher(agentJarPath, testClasspath, javaHome);
    }

    @Test
    public void testIncludePackage() throws Exception {
        // Run with include config
        AppLauncher.AppResult result = launcher.launch(
            "com.example.bytebuf.tracker.integration.testapp.BasicFlowApp",
            "include=com.example.bytebuf.tracker.integration.testapp");

        assertThat(result.isSuccess()).isTrue();

        OutputVerifier verifier = new OutputVerifier(result.getOutput());

        // Agent should start
        assertThat(verifier.hasAgentStarted())
            .isTrue();

        // Methods from included package should be tracked
        assertThat(verifier.hasMethodInFlow("allocate"))
            .isTrue();
    }

    @Test
    public void testExcludePackage() throws Exception {
        // Run with exclude config that excludes the test app package
        // This should result in no methods being tracked
        AppLauncher.AppResult result = launcher.launch(
            "com.example.bytebuf.tracker.integration.testapp.BasicFlowApp",
            "include=com.example;exclude=com.example.bytebuf.tracker.integration.testapp");

        assertThat(result.isSuccess()).isTrue();

        OutputVerifier verifier = new OutputVerifier(result.getOutput());

        // Agent should start
        assertThat(verifier.hasAgentStarted())
            .isTrue();

        // But since we excluded the testapp package, methods should NOT be tracked
        // Total traversals should be 0
        int traversals = verifier.getTotalTraversals();
        assertThat(traversals)
            .withFailMessage("Should have no traversals when package is excluded")
            .isEqualTo(0);
    }

    @Test
    public void testMultipleIncludes() throws Exception {
        // Run with multiple include packages
        AppLauncher.AppResult result = launcher.launch(
            "com.example.bytebuf.tracker.integration.testapp.BasicFlowApp",
            "include=com.example.bytebuf.tracker.integration.testapp,com.example.demo");

        assertThat(result.isSuccess()).isTrue();

        OutputVerifier verifier = new OutputVerifier(result.getOutput());

        // Agent should start and track methods
        assertThat(verifier.hasAgentStarted())
            .isTrue();

        assertThat(verifier.hasMethodInFlow("allocate"))
            .isTrue();
    }

    @Test
    public void testBroadInclude() throws Exception {
        // Run with broad include
        AppLauncher.AppResult result = launcher.launch(
            "com.example.bytebuf.tracker.integration.testapp.BasicFlowApp",
            "include=com.example");

        assertThat(result.isSuccess()).isTrue();

        OutputVerifier verifier = new OutputVerifier(result.getOutput());

        // Should track methods from com.example packages
        assertThat(verifier.hasMethodInFlow("allocate"))
            .isTrue();
    }

    @Test
    public void testNarrowInclude() throws Exception {
        // Run with very specific include
        AppLauncher.AppResult result = launcher.launch(
            "com.example.bytebuf.tracker.integration.testapp.BasicFlowApp",
            "include=com.example.bytebuf.tracker.integration.testapp.BasicFlowApp");

        assertThat(result.isSuccess()).isTrue();

        OutputVerifier verifier = new OutputVerifier(result.getOutput());

        // Should still track methods from BasicFlowApp
        assertThat(verifier.hasMethodInFlow("allocate"))
            .isTrue();
    }
}
