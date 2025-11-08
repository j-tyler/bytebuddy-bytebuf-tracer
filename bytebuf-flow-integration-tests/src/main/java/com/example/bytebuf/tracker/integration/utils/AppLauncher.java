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

package com.example.bytebuf.tracker.integration.utils;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Utility to launch test applications in a separate JVM with the ByteBuf tracking agent.
 */
public class AppLauncher {

    private final String agentJarPath;
    private final String testClasspath;
    private final String javaHome;

    public AppLauncher(String agentJarPath, String testClasspath, String javaHome) {
        this.agentJarPath = agentJarPath;
        this.testClasspath = testClasspath;
        this.javaHome = javaHome;
    }

    /**
     * Launch an application with the agent and default config.
     */
    public AppResult launch(String mainClass) throws IOException, InterruptedException {
        return launch(mainClass, "include=com.example.bytebuf.tracker.integration.testapp");
    }

    /**
     * Launch an application with the agent and custom config.
     */
    public AppResult launch(String mainClass, String agentConfig) throws IOException, InterruptedException {
        return launch(mainClass, agentConfig, 30);
    }

    /**
     * Launch an application with the agent, custom config, and timeout.
     */
    public AppResult launch(String mainClass, String agentConfig, int timeoutSeconds)
            throws IOException, InterruptedException {

        // Verify agent JAR exists
        File agentFile = new File(agentJarPath);
        if (!agentFile.exists()) {
            throw new IllegalStateException("Agent JAR not found at: " + agentJarPath +
                "\nPlease run 'mvn install' in the bytebuf-flow-tracker module first.");
        }

        // Build command
        List<String> command = new ArrayList<>();
        command.add(javaHome + File.separator + "bin" + File.separator + "java");
        command.add("-javaagent:" + agentJarPath + "=" + agentConfig);
        command.add("-cp");
        command.add(buildClasspath());
        command.add(mainClass);

        System.out.println("Launching: " + String.join(" ", command));

        // Start process
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(true);
        Process process = pb.start();

        // Capture output
        StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append("\n");
                System.out.println("[APP] " + line);
            }
        }

        // Wait for completion
        boolean completed = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
        if (!completed) {
            process.destroyForcibly();
            throw new RuntimeException("Process timed out after " + timeoutSeconds + " seconds");
        }

        int exitCode = process.exitValue();
        String outputStr = output.toString();

        return new AppResult(exitCode, outputStr);
    }

    /**
     * Build the classpath for the launched application.
     * Includes test classes and all dependencies.
     */
    private String buildClasspath() {
        // Get Maven classpath from system property or build it
        String mavenClasspath = System.getProperty("surefire.test.class.path");
        if (mavenClasspath != null && !mavenClasspath.isEmpty()) {
            return testClasspath + File.pathSeparator + mavenClasspath;
        }

        // Fallback: just use test classpath
        return testClasspath;
    }

    /**
     * Result of launching an application.
     */
    public static class AppResult {
        private final int exitCode;
        private final String output;

        public AppResult(int exitCode, String output) {
            this.exitCode = exitCode;
            this.output = output;
        }

        public int getExitCode() {
            return exitCode;
        }

        public String getOutput() {
            return output;
        }

        public boolean isSuccess() {
            return exitCode == 0;
        }
    }
}
