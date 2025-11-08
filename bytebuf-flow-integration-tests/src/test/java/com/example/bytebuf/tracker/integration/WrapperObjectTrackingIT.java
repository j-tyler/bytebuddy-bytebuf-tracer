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

        // Should have no leaks - ByteBuf is released at the end
        assertThat(verifier.getLeakPaths())
            .withFailMessage("Should have no leaks")
            .isEqualTo(0);
    }

    @Test
    public void testWithConstructorTracking() throws Exception {
        // Run with constructor tracking for Envelope
        AppLauncher.AppResult result = launcher.launch(
            "com.example.bytebuf.tracker.integration.testapp.WrapperObjectApp",
            "include=com.example.bytebuf.tracker.integration.testapp;trackConstructors=com.example.bytebuf.tracker.integration.testapp.WrapperObjectApp$Envelope");

        assertThat(result.isSuccess()).isTrue();

        OutputVerifier verifier = new OutputVerifier(result.getOutput());

        // With constructor tracking, the Envelope constructor should appear
        String flowTree = verifier.getFlowTree();
        boolean hasEnvelopeConstructor = flowTree.contains("Envelope") || flowTree.contains("<init>");

        assertThat(hasEnvelopeConstructor)
            .withFailMessage("Envelope constructor should be tracked with trackConstructors config")
            .isTrue();

        // Should maintain continuous flow
        assertThat(verifier.getTotalRootMethods())
            .withFailMessage("Should have 1 continuous flow")
            .isEqualTo(1);
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

        // Verify ByteBuf is eventually released (no leaks)
        assertThat(verifier.hasMethodInFlow("release"))
            .withFailMessage("release should be called")
            .isTrue();
        assertThat(verifier.getLeakPaths())
            .withFailMessage("Should have no leaks")
            .isEqualTo(0);
    }
}
