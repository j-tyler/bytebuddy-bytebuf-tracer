/*
 * Copyright 2025 Justin Marsh
 * SPDX-License-Identifier: Apache-2.0
 */

package com.example.bytebuf.tracker.integration;

import com.example.bytebuf.tracker.integration.utils.AppLauncher;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test for metric handler functionality.
 * Verifies that metric handlers receive correct leak counts and flow paths.
 */
public class MetricHandlerIT {

    private static AppLauncher launcher;

    @BeforeAll
    public static void setUp() {
        String agentJarPath = System.getProperty("agent.jar.path");
        String testClasspath = System.getProperty("test.classpath");
        String javaHome = System.getProperty("java.home");

        launcher = new AppLauncher(agentJarPath, testClasspath, javaHome);
    }

    /**
     * Test that metric handlers receive correct leak counts and flow paths.
     *
     * FIXED: The issue was that MetricCollector uses delta-based metrics that drain
     * a queue on each push. The test was using the last snapshot which had already
     * been drained. Now the test finds the snapshot with leaks.
     */
    @Test
    public void testMetricHandlerReceivesLeaksWithCounts() throws Exception {
        // Configure metric push interval to 1 second for fast testing
        Map<String, String> systemProperties = new HashMap<String, String>();
        systemProperties.put("bytebuf.metrics.pushInterval", "1");
        systemProperties.put("io.netty.leakDetection.level", "DISABLED");  // Disable Netty leak detection

        AppLauncher.AppResult result = launcher.launchWithSystemProperties(
            "com.example.bytebuf.tracker.integration.testapp.MetricHandlerTestApp",
            systemProperties);

        // Verify app succeeded (exit code 0)
        assertThat(result.isSuccess())
            .withFailMessage("App should exit successfully. Output:\n" + result.getOutput())
            .isTrue();

        String output = result.getOutput();

        // Verify test handler was registered
        assertThat(output)
            .withFailMessage("Should register test handler")
            .contains("Registered test handler");

        // Verify metrics were received
        assertThat(output)
            .withFailMessage("Should receive metrics snapshot")
            .contains("Received metrics snapshot");

        // Verify correct number of direct leaks (4: 3 explicit + 1 from Netty internal initialization)
        assertThat(output)
            .withFailMessage("Should detect 4 direct leaks")
            .contains("Direct leak count correct: 4");

        // Verify correct number of heap leaks (2)
        assertThat(output)
            .withFailMessage("Should detect 2 heap leaks")
            .contains("Heap leak count correct: 2");

        // Verify leak count is embedded in flow representation
        assertThat(output)
            .withFailMessage("Flow should contain leak_count field")
            .contains("Leak counts embedded in flow representation");

        // Verify test completed successfully
        assertThat(output)
            .withFailMessage("Test should complete successfully")
            .contains("Test completed successfully");
    }

    /**
     * Test that metric push scheduler runs and delivers metrics to handlers.
     *
     * FIXED: Same fix as testMetricHandlerReceivesLeaksWithCounts - the test app
     * now finds the correct snapshot with leak data.
     */
    @Test
    public void testMetricHandlerReceivesMetrics() throws Exception {
        // Test that scheduler runs and pushes metrics to handlers
        Map<String, String> systemProperties = new HashMap<String, String>();
        systemProperties.put("bytebuf.metrics.pushInterval", "1");

        AppLauncher.AppResult result = launcher.launchWithSystemProperties(
            "com.example.bytebuf.tracker.integration.testapp.MetricHandlerTestApp",
            systemProperties);

        // Verify app succeeded
        assertThat(result.isSuccess())
            .withFailMessage("App should exit successfully. Output:\n" + result.getOutput())
            .isTrue();

        String output = result.getOutput();

        // Verify metrics were received (proves scheduler ran and pushed)
        assertThat(output)
            .withFailMessage("Should receive metrics snapshot")
            .contains("Received metrics snapshot");

        // Verify test completed (proves metrics arrived within expected time)
        assertThat(output)
            .withFailMessage("Should not timeout waiting for metrics")
            .doesNotContain("Timeout waiting for metrics");
    }
}
