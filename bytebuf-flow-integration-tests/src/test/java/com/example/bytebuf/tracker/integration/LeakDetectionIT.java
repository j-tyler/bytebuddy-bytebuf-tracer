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

        // Should have leak detected
        assertThat(verifier.hasLeakDetected())
            .withFailMessage("Should detect the intentional leak. Output:\n" + result.getOutput())
            .isTrue();
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
        String flatPaths = verifier.getFlatPaths();

        // Normal flow path should end with ref=0
        assertThat(flatPaths)
            .contains("allocateNormal")
            .contains("processNormal")
            .contains("releaseNormal");

        // Normal flow should have proper cleanup
        assertThat(verifier.hasProperCleanup())
            .withFailMessage("Normal flow should show proper cleanup")
            .isTrue();
    }

    @Test
    public void testLeakyFlowIsFlagged() throws Exception {
        AppLauncher.AppResult result = launcher.launch(
            "com.example.bytebuf.tracker.integration.testapp.LeakDetectionApp");

        assertThat(result.isSuccess()).isTrue();

        OutputVerifier verifier = new OutputVerifier(result.getOutput());
        String flatPaths = verifier.getFlatPaths();

        // Leaky flow path should include the leak method
        assertThat(flatPaths)
            .contains("allocateLeaky")
            .contains("processLeaky")
            .contains("forgetToRelease");

        // Leaky path should be flagged
        assertThat(verifier.hasLeakDetected())
            .withFailMessage("Leaky flow should be flagged as a leak")
            .isTrue();
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
