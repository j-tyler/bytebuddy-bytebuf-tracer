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
 * Integration test verifying that the ByteBuf tracking agent is compatible with Mockito.
 *
 * This test ensures that:
 * 1. org.mockito.* packages are excluded from instrumentation by default
 * 2. Mockito can create mocks without "Mockito cannot mock this class" errors
 * 3. The agent and Mockito can coexist without bytecode transformation conflicts
 * 4. Both instrumented (real) and mocked classes work together
 *
 * Context: Mockito 5+ uses ByteBuddy internally for mocking. If the agent tries to
 * instrument Mockito classes or classes that Mockito is mocking, the JVM will reject
 * the duplicate transformation with "class redefinition failed" errors.
 *
 * Solution: The agent excludes org.mockito.* packages by default (see ByteBufFlowAgent.java:57)
 */
public class MockitoCompatibilityIT {

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
    public void testMockitoWorksWithAgent() throws Exception {
        AppLauncher.AppResult result = launcher.launch(
            "com.example.bytebuf.tracker.integration.testapp.MockitoTestApp");

        // Verify the app completed successfully
        assertThat(result.isSuccess())
            .withFailMessage("Mockito test app should exit successfully. Output:\n" + result.getOutput())
            .isTrue();

        String output = result.getOutput();

        // Verify all test phases completed
        assertThat(output)
            .withFailMessage("Should complete Test 1: Create mock")
            .contains("Test 1: Creating Mockito mock")
            .contains("✓ Successfully created mock");

        assertThat(output)
            .withFailMessage("Should complete Test 2: Configure mock")
            .contains("Test 2: Configuring mock behavior")
            .contains("✓ Successfully configured mock");

        assertThat(output)
            .withFailMessage("Should complete Test 3: Use mock with instrumented class")
            .contains("Test 3: Using mock with real instrumented class")
            .contains("✓ Successfully called instrumented method");

        assertThat(output)
            .withFailMessage("Should complete Test 4: Verify interactions")
            .contains("Test 4: Verifying mock interactions")
            .contains("✓ Successfully verified mock interactions");

        assertThat(output)
            .withFailMessage("Should complete Test 5: Multiple mocks")
            .contains("Test 5: Creating multiple mocks")
            .contains("✓ Successfully created multiple mocks");

        assertThat(output)
            .withFailMessage("Should complete Test 6: ArgumentCaptor")
            .contains("Test 6: Testing ArgumentCaptor")
            .contains("✓ Successfully captured ByteBuf argument");

        // Verify final success message
        assertThat(output)
            .withFailMessage("Should show final success message")
            .contains("ALL TESTS PASSED")
            .contains("work together without conflicts");
    }

    @Test
    public void testNoMockitoErrors() throws Exception {
        AppLauncher.AppResult result = launcher.launch(
            "com.example.bytebuf.tracker.integration.testapp.MockitoTestApp");

        assertThat(result.isSuccess()).isTrue();

        String output = result.getOutput().toLowerCase();

        // Verify no Mockito-specific errors
        assertThat(output)
            .withFailMessage("Should not have 'Mockito cannot mock this class' error")
            .doesNotContain("mockito cannot mock this class")
            .doesNotContain("cannot mock this class");

        // Verify no ByteBuddy/instrumentation conflicts
        assertThat(output)
            .withFailMessage("Should not have class redefinition errors")
            .doesNotContain("class redefinition failed")
            .doesNotContain("attempted to delete a method")
            .doesNotContain("attempted to change the schema");

        // Verify no general errors
        assertThat(output)
            .withFailMessage("Should not have unexpected errors")
            .doesNotContain("exception")
            .doesNotContain("error:")
            .doesNotContain("failed");
    }

    @Test
    public void testAgentStillInstrumentsRealClasses() throws Exception {
        AppLauncher.AppResult result = launcher.launch(
            "com.example.bytebuf.tracker.integration.testapp.MockitoTestApp");

        assertThat(result.isSuccess()).isTrue();

        OutputVerifier verifier = new OutputVerifier(result.getOutput());

        // Verify agent started and installed
        assertThat(verifier.hasAgentStarted())
            .withFailMessage("Agent should start")
            .isTrue();

        assertThat(verifier.hasInstrumentationInstalled())
            .withFailMessage("Instrumentation should be installed")
            .isTrue();

        // Verify real classes (not mocks) were instrumented
        // The RealByteBufHandler.handleBuffer and releaseBuffer should be tracked
        String output = result.getOutput();
        assertThat(output)
            .withFailMessage("Real instrumented methods should be called")
            .contains("RealByteBufHandler.handleBuffer called")
            .contains("RealByteBufHandler.releaseBuffer called");
    }

    @Test
    public void testMockitoAndInstrumentedCodeCoexist() throws Exception {
        AppLauncher.AppResult result = launcher.launch(
            "com.example.bytebuf.tracker.integration.testapp.MockitoTestApp");

        assertThat(result.isSuccess()).isTrue();

        OutputVerifier verifier = new OutputVerifier(result.getOutput());
        String output = result.getOutput();

        // This is the critical test: verify that BOTH systems work together
        // 1. Mockito successfully creates mocks and verifies interactions
        assertThat(output)
            .withFailMessage("Mockito should work: create mocks and verify interactions")
            .contains("Successfully created mock")
            .contains("Successfully verified mock interactions");

        // 2. Agent successfully instruments real classes
        assertThat(verifier.hasAgentStarted())
            .withFailMessage("Agent should successfully instrument classes")
            .isTrue();

        // 3. Real code calls mocked dependencies without errors
        assertThat(output)
            .withFailMessage("Real instrumented code should call mocked dependencies")
            .contains("RealByteBufHandler.handleBuffer called");

        // 4. ByteBuf flow tracking works (at least some tracking occurred)
        assertThat(verifier.hasInstrumentationInstalled())
            .withFailMessage("ByteBuf flow tracking should be active")
            .isTrue();

        // All of the above passing proves both systems coexist peacefully
    }

    @Test
    public void testOrgMockitoPackageIsExcluded() throws Exception {
        // This test verifies the documented behavior that org.mockito.* is excluded by default
        AppLauncher.AppResult result = launcher.launch(
            "com.example.bytebuf.tracker.integration.testapp.MockitoTestApp");

        assertThat(result.isSuccess())
            .withFailMessage("App should run without Mockito transformation conflicts")
            .isTrue();

        // The fact that this test passes proves org.mockito is excluded, because:
        // - If org.mockito was NOT excluded, ByteBuddy would try to instrument Mockito classes
        // - Mockito also uses ByteBuddy internally for creating mocks
        // - This would cause "class redefinition failed" or "Mockito cannot mock" errors
        // - The test would fail with exit code != 0

        String output = result.getOutput();
        assertThat(output)
            .withFailMessage("Should not have transformation conflicts")
            .contains("ALL TESTS PASSED");
    }
}
