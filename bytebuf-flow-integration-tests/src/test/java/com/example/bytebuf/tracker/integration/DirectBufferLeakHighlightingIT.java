/*
 * Copyright 2025 Justin Marsh
 * SPDX-License-Identifier: Apache-2.0
 */

package com.example.bytebuf.tracker.integration;

import com.example.bytebuf.tracker.integration.utils.AppLauncher;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test for direct buffer leak highlighting with 🚨 emoji.
 * Verifies that direct buffer leaks are visually distinguished from heap buffer leaks.
 */
public class DirectBufferLeakHighlightingIT {

    private static AppLauncher launcher;

    @BeforeAll
    public static void setUp() {
        String agentJarPath = System.getProperty("agent.jar.path");
        String testClasspath = System.getProperty("test.classpath");
        String javaHome = System.getProperty("java.home");

        launcher = new AppLauncher(agentJarPath, testClasspath, javaHome);
    }

    @Test
    public void testDirectBufferLeakShowsCriticalEmoji() throws Exception {
        AppLauncher.AppResult result = launcher.launch(
            "com.example.bytebuf.tracker.integration.testapp.DirectBufferLeakApp");

        assertThat(result.isSuccess()).isTrue();

        String output = result.getOutput();
        System.out.println("=== DirectBufferLeakApp Output ===");
        System.out.println(output);

        // Verify direct buffer leak shows as CRITICAL_LEAK in LLM format
        assertThat(output)
            .withFailMessage("Should show CRITICAL_LEAK for direct buffer leak")
            .contains("CRITICAL_LEAK|root=io.netty.buffer.UnpooledByteBufAllocator.directBuffer");

        // Verify directBuffer is identified as root
        assertThat(output)
            .withFailMessage("Should show directBuffer as root")
            .contains("ROOT: io.netty.buffer.UnpooledByteBufAllocator.directBuffer");
    }

    @Test
    public void testHeapBufferLeakShowsWarningEmoji() throws Exception {
        AppLauncher.AppResult result = launcher.launch(
            "com.example.bytebuf.tracker.integration.testapp.DirectBufferLeakApp");

        assertThat(result.isSuccess()).isTrue();

        String output = result.getOutput();

        // Verify heap buffer leak shows as regular "leak" in LLM format (not CRITICAL_LEAK)
        assertThat(output)
            .withFailMessage("Should show regular 'leak' (not CRITICAL_LEAK) for heap buffer")
            .containsPattern("(?m)^leak\\|root=io\\.netty\\.buffer\\.UnpooledByteBufAllocator\\.heapBuffer");

        // Verify heapBuffer is identified as root
        assertThat(output)
            .withFailMessage("Should show heapBuffer as root")
            .contains("ROOT: io.netty.buffer.UnpooledByteBufAllocator.heapBuffer");
    }

    @Test
    public void testBothLeakTypesAppearInOutput() throws Exception {
        AppLauncher.AppResult result = launcher.launch(
            "com.example.bytebuf.tracker.integration.testapp.DirectBufferLeakApp");

        assertThat(result.isSuccess()).isTrue();

        String output = result.getOutput();

        // Verify BOTH leak types appear (critical and moderate)
        assertThat(output)
            .withFailMessage("Should show CRITICAL_LEAK for direct buffer")
            .contains("CRITICAL_LEAK|root=io.netty.buffer.UnpooledByteBufAllocator.directBuffer");

        assertThat(output)
            .withFailMessage("Should show regular leak for heap buffer")
            .containsPattern("(?m)^leak\\|root=io\\.netty\\.buffer\\.UnpooledByteBufAllocator\\.heapBuffer");

        // Verify summary shows both leak types
        assertThat(output)
            .withFailMessage("Should show critical and moderate leak path counts in summary")
            .contains("Critical Leak Paths")
            .contains("Moderate Leak Paths");
    }

    @Test
    public void testLLMFormatShowsCriticalLeakLabel() throws Exception {
        AppLauncher.AppResult result = launcher.launch(
            "com.example.bytebuf.tracker.integration.testapp.DirectBufferLeakApp");

        assertThat(result.isSuccess()).isTrue();

        String output = result.getOutput();

        // LLM format should show CRITICAL_LEAK for direct buffers
        assertThat(output)
            .withFailMessage("Should show CRITICAL_LEAK in LLM format")
            .contains("CRITICAL_LEAK");

        // And regular "leak" for heap buffers
        assertThat(output)
            .withFailMessage("Should show regular 'leak' for heap buffers")
            .containsPattern("(?m)^leak\\|root=.*heapBuffer");
    }

    @Test
    public void testDirectBufferRootIdentified() throws Exception {
        AppLauncher.AppResult result = launcher.launch(
            "com.example.bytebuf.tracker.integration.testapp.DirectBufferLeakApp");

        assertThat(result.isSuccess()).isTrue();

        String output = result.getOutput();

        // Verify directBuffer root is identified
        assertThat(output)
            .withFailMessage("Should show directBuffer as root")
            .contains("ROOT: io.netty.buffer.UnpooledByteBufAllocator.directBuffer");

        // Verify heapBuffer root is identified
        assertThat(output)
            .withFailMessage("Should show heapBuffer as root")
            .contains("ROOT: io.netty.buffer.UnpooledByteBufAllocator.heapBuffer");
    }

    @Test
    public void testProperlyReleasedBufferShowsNoLeak() throws Exception {
        AppLauncher.AppResult result = launcher.launch(
            "com.example.bytebuf.tracker.integration.testapp.DirectBufferLeakApp");

        assertThat(result.isSuccess()).isTrue();

        String output = result.getOutput();

        // Verify cleanupProperly path exists (it will have ref=1 before calling release)
        assertThat(output)
            .withFailMessage("Should show cleanupProperly in tree")
            .contains("cleanupProperly");

        // Verify the release() call under cleanupProperly shows ref=0
        assertThat(output)
            .withFailMessage("Should show ref=0 for release() call")
            .containsPattern("release.*\\[ref=0");

        // The cleanupProperly path should NOT have a leak marker
        String[] lines = output.split("\n");
        for (String line : lines) {
            if (line.contains("cleanupProperly")) {
                assertThat(line)
                    .withFailMessage("Clean path should not show LEAK emoji")
                    .doesNotContain("LEAK");
            }
        }
    }

    @Test
    public void testLeakMethodsCorrectlyIdentified() throws Exception {
        AppLauncher.AppResult result = launcher.launch(
            "com.example.bytebuf.tracker.integration.testapp.DirectBufferLeakApp");

        assertThat(result.isSuccess()).isTrue();

        String output = result.getOutput();

        // Verify heap leak method
        assertThat(output)
            .withFailMessage("Should track forgetToReleaseHeap method")
            .contains("forgetToReleaseHeap");

        // Verify direct leak method
        assertThat(output)
            .withFailMessage("Should track forgetToReleaseDirect method")
            .contains("forgetToReleaseDirect");
    }

}
