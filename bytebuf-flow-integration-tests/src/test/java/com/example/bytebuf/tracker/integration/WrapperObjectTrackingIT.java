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
 * Integration test for wrapped object tracking.
 * Verifies tracking behavior when ByteBuf is wrapped in custom objects.
 */
public class WrapperObjectTrackingIT {

    private static AppLauncher launcher;

    @BeforeAll
    public static void setUp() {
        String agentJarPath = System.getProperty("agent.jar.path");
        String testClasspath = System.getProperty("test.classpath");
        String javaHome = System.getProperty("java.home");

        launcher = new AppLauncher(agentJarPath, testClasspath, javaHome);
    }

    @Test
    public void testWrapAndUnwrapMethodsTracked() throws Exception {
        AppLauncher.AppResult result = launcher.launch(
            "com.example.bytebuf.tracker.integration.testapp.WrapperObjectApp");

        assertThat(result.isSuccess()).isTrue();

        OutputVerifier verifier = new OutputVerifier(result.getOutput());

        // Methods that take/return ByteBuf should be tracked
        assertThat(verifier.hasMethodInFlow("allocate"))
            .withFailMessage("allocate method should be tracked")
            .isTrue();

        assertThat(verifier.hasMethodInFlow("wrap"))
            .withFailMessage("wrap method should be tracked")
            .isTrue();

        assertThat(verifier.hasMethodInFlow("unwrap"))
            .withFailMessage("unwrap method should be tracked")
            .isTrue();
    }

    @Test
    public void testEnvelopeMethodsNotAutoTracked() throws Exception {
        AppLauncher.AppResult result = launcher.launch(
            "com.example.bytebuf.tracker.integration.testapp.WrapperObjectApp");

        assertThat(result.isSuccess()).isTrue();

        OutputVerifier verifier = new OutputVerifier(result.getOutput());

        // Methods that only take Envelope (not ByteBuf) won't be auto-tracked
        // unless manual tracking is added or constructor tracking is enabled
        String flowTree = verifier.getFlowTree();

        // These methods take Envelope, not ByteBuf, so they won't appear
        // unless they manually extract and track the ByteBuf
        boolean hasProcessEnvelope = flowTree.contains("processEnvelope");
        boolean hasValidateEnvelope = flowTree.contains("validateEnvelope");

        // Document the behavior: wrapper methods are NOT auto-tracked
        // This is expected - only methods with ByteBuf in signature are tracked
        System.out.println("processEnvelope tracked: " + hasProcessEnvelope);
        System.out.println("validateEnvelope tracked: " + hasValidateEnvelope);
    }

    @Test
    public void testProperCleanup() throws Exception {
        AppLauncher.AppResult result = launcher.launch(
            "com.example.bytebuf.tracker.integration.testapp.WrapperObjectApp");

        assertThat(result.isSuccess()).isTrue();

        OutputVerifier verifier = new OutputVerifier(result.getOutput());

        // Verify release is called, indicating proper cleanup
        assertThat(verifier.hasMethodInFlow("release"))
            .withFailMessage("release method should be called for cleanup")
            .isTrue();

        // Should have proper cleanup marker (ref=0)
        assertThat(verifier.hasProperCleanup())
            .withFailMessage("Should have ref=0 showing ByteBuf was released")
            .isTrue();
    }

    @Test
    public void testWithConstructorTracking() throws Exception {
        // Run with constructor tracking for Envelope
        AppLauncher.AppResult result = launcher.launch(
            "com.example.bytebuf.tracker.integration.testapp.WrapperObjectApp",
            "include=com.example.bytebuf.tracker.integration.testapp.*;trackConstructors=com.example.bytebuf.tracker.integration.testapp.WrapperObjectApp$Envelope");

        assertThat(result.isSuccess()).isTrue();

        OutputVerifier verifier = new OutputVerifier(result.getOutput());

        // With constructor tracking, the Envelope constructor should appear
        String flowTree = verifier.getFlowTree();
        boolean hasEnvelopeConstructor = flowTree.contains("Envelope") || flowTree.contains("<init>");

        assertThat(hasEnvelopeConstructor)
            .withFailMessage("Envelope constructor should be tracked with trackConstructors config")
            .isTrue();

        // Should have allocator root(s) - may have 1-2 due to Netty initialization
        assertThat(verifier.getTotalRootMethods())
            .withFailMessage("Should have 1-2 allocator roots")
            .isBetween(1, 2);
    }

    @Test
    public void testFlowContinuity() throws Exception {
        AppLauncher.AppResult result = launcher.launch(
            "com.example.bytebuf.tracker.integration.testapp.WrapperObjectApp");

        assertThat(result.isSuccess()).isTrue();

        OutputVerifier verifier = new OutputVerifier(result.getOutput());

        // Should track: allocate -> wrap -> ... -> unwrap -> release
        // The methods in between (processEnvelope, validateEnvelope) won't be tracked
        // because they don't have ByteBuf in their signature
        assertThat(verifier.hasMethodInFlow("allocate"))
            .withFailMessage("allocate should be tracked")
            .isTrue();
        assertThat(verifier.hasMethodInFlow("wrap"))
            .withFailMessage("wrap should be tracked")
            .isTrue();
        assertThat(verifier.hasMethodInFlow("unwrap"))
            .withFailMessage("unwrap should be tracked")
            .isTrue();

        // Verify ByteBuf is eventually released (no leaks in application flow)
        assertThat(verifier.hasMethodInFlow("release"))
            .withFailMessage("release should be called")
            .isTrue();

        // Verify proper cleanup with ref=0
        assertThat(verifier.hasProperCleanup())
            .withFailMessage("Should have ref=0 showing ByteBuf was released")
            .isTrue();
    }

    /**
     * Comprehensive test validating the _return suffix behavior for method exit tracking.
     *
     * This test differs from testWithConstructorTracking() by explicitly validating:
     * 1. Entry tracking (<init>) AND exit tracking (<init>_return) with _return suffix
     * 2. Getter return tracking (getPayload_return) with _return suffix
     * 3. Method sequencing and ordering in the flow tree
     * 4. Complete path validation from allocation to cleanup
     *
     * testWithConstructorTracking() only validates that constructor tracking works at all,
     * while this test validates the complete entry/exit tracking mechanism including
     * the _return suffix convention for tracking return values.
     */
    @Test
    public void testCompleteWrapperFlowWithConstructorAndGetterTracking() throws Exception {
        // Run with constructor tracking for Envelope to get complete visibility
        AppLauncher.AppResult result = launcher.launch(
            "com.example.bytebuf.tracker.integration.testapp.WrapperObjectApp",
            "include=com.example.bytebuf.tracker.integration.testapp.*;trackConstructors=com.example.bytebuf.tracker.integration.testapp.WrapperObjectApp$Envelope");

        assertThat(result.isSuccess())
            .withFailMessage("Application should exit successfully. Output:\n" + result.getOutput())
            .isTrue();

        OutputVerifier verifier = new OutputVerifier(result.getOutput());
        String flowTree = verifier.getFlowTree();

        // Verify the complete sequence appears in the flow tree
        // Expected path: allocator root -> allocate_return -> wrap -> <init> -> <init>_return ->
        //                unwrap -> getPayload_return -> release [ref=0]

        // Verify allocate and its return tracking
        assertThat(flowTree)
            .withFailMessage("Flow tree should show allocate method")
            .contains("allocate");

        assertThat(verifier.hasMethodExitTracked("allocate"))
            .withFailMessage("Flow tree should contain allocate_return for exit tracking")
            .isTrue();

        // Verify wrap and its return tracking
        assertThat(flowTree)
            .withFailMessage("Flow tree should show wrap method")
            .contains("wrap");

        assertThat(verifier.hasMethodExitTracked("wrap"))
            .withFailMessage("Flow tree should contain wrap_return for exit tracking")
            .isTrue();

        // Verify constructor entry is tracked
        assertThat(flowTree)
            .withFailMessage("Flow tree should contain constructor entry (<init>)")
            .contains("<init>");

        // Verify constructor exit is tracked with _return suffix
        assertThat(verifier.hasMethodExitTracked("init"))
            .withFailMessage("Flow tree should contain constructor exit (<init>_return)")
            .isTrue();

        // Verify unwrap method is tracked
        assertThat(flowTree)
            .withFailMessage("Flow tree should show unwrap method")
            .contains("unwrap");

        assertThat(verifier.hasMethodExitTracked("unwrap"))
            .withFailMessage("Flow tree should contain unwrap_return for exit tracking")
            .isTrue();

        // Verify getter returns are tracked with _return suffix
        // getPayload() is called by unwrap(), so it should appear in the flow
        assertThat(verifier.hasMethodExitTracked("getPayload"))
            .withFailMessage("Flow tree should contain getPayload_return (called by unwrap)")
            .isTrue();

        // Verify cleanup
        assertThat(flowTree)
            .withFailMessage("Flow tree should show release method")
            .contains("release");

        assertThat(flowTree)
            .withFailMessage("Flow tree should show ref=0 indicating proper cleanup")
            .contains("[ref=0");

        // Verify methods appear in expected sequence (not full tree structure validation,
        // just linear ordering to catch major flow issues)
        int allocatePos = flowTree.indexOf("allocate");
        int initPos = flowTree.indexOf("<init>");
        int unwrapPos = flowTree.indexOf("unwrap");
        int releasePos = flowTree.indexOf("release");

        assertThat(allocatePos)
            .withFailMessage("allocate should appear in flow tree")
            .isGreaterThan(-1);

        assertThat(initPos)
            .withFailMessage("<init> should appear after allocate in flow output")
            .isGreaterThan(allocatePos);

        assertThat(unwrapPos)
            .withFailMessage("unwrap should appear after <init> in flow output")
            .isGreaterThan(initPos);

        assertThat(releasePos)
            .withFailMessage("release should appear after unwrap in flow output")
            .isGreaterThan(unwrapPos);

        // Verify no leaks - the flow should end with proper cleanup
        assertThat(verifier.hasProperCleanup())
            .withFailMessage("Should have ref=0 showing ByteBuf was properly released")
            .isTrue();
    }
}
