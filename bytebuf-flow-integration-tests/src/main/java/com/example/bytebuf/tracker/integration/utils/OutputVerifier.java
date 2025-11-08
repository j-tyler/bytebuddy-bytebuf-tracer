package com.example.bytebuf.tracker.integration.utils;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Utility to parse and verify tracking output from test applications.
 */
public class OutputVerifier {

    private final String output;

    public OutputVerifier(String output) {
        this.output = output;
    }

    /**
     * Check if output contains agent startup message.
     */
    public boolean hasAgentStarted() {
        return output.contains("[ByteBufFlowAgent] Starting with config");
    }

    /**
     * Check if output contains instrumentation success message.
     */
    public boolean hasInstrumentationInstalled() {
        return output.contains("[ByteBufFlowAgent] Instrumentation installed successfully");
    }

    /**
     * Check if output contains JMX registration message.
     */
    public boolean hasJmxRegistered() {
        return output.contains("[ByteBufFlowAgent] JMX MBean registered");
    }

    /**
     * Check if output contains a specific method in the flow tree.
     */
    public boolean hasMethodInFlow(String methodName) {
        return output.contains(methodName);
    }

    /**
     * Check if output contains a specific class.method in the flow tree.
     */
    public boolean hasClassMethodInFlow(String className, String methodName) {
        return output.contains(className + "." + methodName);
    }

    /**
     * Check if output contains a leak marker.
     */
    public boolean hasLeakDetected() {
        return output.contains("⚠️ LEAK") ||
               output.contains("[ref=1]") ||
               output.contains("[ref=2]");
    }

    /**
     * Check if output indicates proper cleanup (ref=0).
     */
    public boolean hasProperCleanup() {
        return output.contains("[ref=0]");
    }

    /**
     * Extract total root methods from summary.
     */
    public int getTotalRootMethods() {
        return extractIntFromSummary("Total Root Methods: (\\d+)");
    }

    /**
     * Extract total traversals from summary.
     */
    public int getTotalTraversals() {
        return extractIntFromSummary("Total Traversals: (\\d+)");
    }

    /**
     * Extract total paths from summary.
     */
    public int getTotalPaths() {
        return extractIntFromSummary("Total Paths: (\\d+)");
    }

    /**
     * Extract leak paths from summary.
     */
    public int getLeakPaths() {
        return extractIntFromSummary("Leak Paths: (\\d+)");
    }

    /**
     * Check if a specific ROOT appears in the output.
     */
    public boolean hasRoot(String className, String methodName) {
        String pattern = "ROOT: " + className + "\\." + methodName;
        return output.contains(pattern);
    }

    /**
     * Extract integer value from summary using regex.
     */
    private int extractIntFromSummary(String regex) {
        Pattern pattern = Pattern.compile(regex);
        Matcher matcher = pattern.matcher(output);
        if (matcher.find()) {
            return Integer.parseInt(matcher.group(1));
        }
        return -1;
    }

    /**
     * Get the raw output for debugging.
     */
    public String getOutput() {
        return output;
    }

    /**
     * Check if output contains error or exception.
     */
    public boolean hasError() {
        return output.toLowerCase().contains("error") ||
               output.toLowerCase().contains("exception") ||
               output.toLowerCase().contains("failed");
    }

    /**
     * Extract the flow tree section from output.
     */
    public String getFlowTree() {
        int start = output.indexOf("=== Flow Tree ===");
        if (start == -1) {
            return "";
        }
        int end = output.indexOf("===", start + 17);
        if (end == -1) {
            end = output.length();
        }
        return output.substring(start, end).trim();
    }

    /**
     * Extract the flat paths section from output.
     */
    public String getFlatPaths() {
        int start = output.indexOf("=== Flat Paths ===");
        if (start == -1) {
            return "";
        }
        int end = output.indexOf("===", start + 18);
        if (end == -1) {
            end = output.length();
        }
        return output.substring(start, end).trim();
    }

    /**
     * Extract the summary section from output.
     */
    public String getSummary() {
        int start = output.indexOf("=== Flow Summary ===");
        if (start == -1) {
            return "";
        }
        int end = output.indexOf("===", start + 20);
        if (end == -1) {
            end = output.length();
        }
        return output.substring(start, end).trim();
    }
}
