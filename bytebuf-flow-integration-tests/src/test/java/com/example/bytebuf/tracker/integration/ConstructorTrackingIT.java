package com.example.bytebuf.tracker.integration;

import com.example.bytebuf.tracker.integration.utils.AppLauncher;
import com.example.bytebuf.tracker.integration.utils.OutputVerifier;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test for constructor tracking.
 * Verifies that constructors can be enabled/disabled via trackConstructors config.
 */
public class ConstructorTrackingIT {

    private static AppLauncher launcher;

    @BeforeAll
    public static void setUp() {
        String agentJarPath = System.getProperty("agent.jar.path");
        String testClasspath = System.getProperty("test.classpath");
        String javaHome = System.getProperty("java.home");

        launcher = new AppLauncher(agentJarPath, testClasspath, javaHome);
    }

    @Test
    public void testConstructorsNotTrackedByDefault() throws Exception {
        // Run without trackConstructors config
        AppLauncher.AppResult result = launcher.launch(
            "com.example.bytebuf.tracker.integration.testapp.ConstructorTrackingApp",
            "include=com.example.bytebuf.tracker.integration.testapp");

        assertThat(result.isSuccess()).isTrue();

        OutputVerifier verifier = new OutputVerifier(result.getOutput());

        // Regular methods should be tracked
        assertThat(verifier.hasMethodInFlow("allocate"))
            .withFailMessage("allocate method should be tracked")
            .isTrue();

        assertThat(verifier.hasMethodInFlow("prepare"))
            .withFailMessage("prepare method should be tracked")
            .isTrue();

        // Constructor should NOT be tracked by default
        String flowTree = verifier.getFlowTree();
        boolean hasConstructor = flowTree.contains("<init>") || flowTree.contains("Message.<init>");

        assertThat(hasConstructor)
            .withFailMessage("Constructor should NOT be tracked by default")
            .isFalse();
    }

    @Test
    public void testConstructorsTrackedWithConfig() throws Exception {
        // Run WITH trackConstructors config
        AppLauncher.AppResult result = launcher.launch(
            "com.example.bytebuf.tracker.integration.testapp.ConstructorTrackingApp",
            "include=com.example.bytebuf.tracker.integration.testapp;trackConstructors=com.example.bytebuf.tracker.integration.testapp.ConstructorTrackingApp$Message");

        assertThat(result.isSuccess()).isTrue();

        OutputVerifier verifier = new OutputVerifier(result.getOutput());

        // Regular methods should still be tracked
        assertThat(verifier.hasMethodInFlow("allocate"))
            .withFailMessage("allocate method should be tracked")
            .isTrue();

        assertThat(verifier.hasMethodInFlow("prepare"))
            .withFailMessage("prepare method should be tracked")
            .isTrue();

        // Constructor SHOULD now be tracked
        String flowTree = verifier.getFlowTree();
        boolean hasConstructor = flowTree.contains("<init>") || flowTree.contains("Message");

        assertThat(hasConstructor)
            .withFailMessage("Constructor should be tracked with trackConstructors config. Flow tree:\n" + flowTree)
            .isTrue();
    }

    @Test
    public void testContinuousFlowWithConstructorTracking() throws Exception {
        // Run WITH trackConstructors config
        AppLauncher.AppResult result = launcher.launch(
            "com.example.bytebuf.tracker.integration.testapp.ConstructorTrackingApp",
            "include=com.example.bytebuf.tracker.integration.testapp;trackConstructors=com.example.bytebuf.tracker.integration.testapp.ConstructorTrackingApp$Message");

        assertThat(result.isSuccess()).isTrue();

        OutputVerifier verifier = new OutputVerifier(result.getOutput());

        // Should have continuous flow: allocate -> prepare -> <init> -> process
        // This means only 1 root, not multiple disconnected roots
        assertThat(verifier.getTotalRootMethods())
            .withFailMessage("Should have 1 continuous flow with constructor tracking")
            .isEqualTo(1);

        // Should have proper cleanup
        assertThat(verifier.hasProperCleanup())
            .withFailMessage("Should have proper cleanup")
            .isTrue();

        // Should have no leaks
        assertThat(verifier.getLeakPaths())
            .withFailMessage("Should have no leaks")
            .isEqualTo(0);
    }

    @Test
    public void testWildcardConstructorTracking() throws Exception {
        // Run with wildcard pattern for trackConstructors
        AppLauncher.AppResult result = launcher.launch(
            "com.example.bytebuf.tracker.integration.testapp.ConstructorTrackingApp",
            "include=com.example.bytebuf.tracker.integration.testapp;trackConstructors=com.example.bytebuf.tracker.integration.testapp.*");

        assertThat(result.isSuccess()).isTrue();

        OutputVerifier verifier = new OutputVerifier(result.getOutput());

        // Constructor should be tracked with wildcard
        String flowTree = verifier.getFlowTree();
        boolean hasConstructor = flowTree.contains("<init>") || flowTree.contains("Message");

        assertThat(hasConstructor)
            .withFailMessage("Constructor should be tracked with wildcard pattern")
            .isTrue();
    }
}
