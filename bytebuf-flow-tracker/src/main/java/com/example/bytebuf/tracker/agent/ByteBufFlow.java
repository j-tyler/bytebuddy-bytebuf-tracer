/*
 * Copyright 2025 Justin Marsh
 * SPDX-License-Identifier: Apache-2.0
 */

package com.example.bytebuf.tracker.agent;

import com.example.bytebuf.tracker.ByteBufFlowTracker;
import com.example.bytebuf.tracker.view.TrieRenderer;

import javax.management.MBeanServer;
import javax.management.ObjectName;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.lang.management.ManagementFactory;
import java.util.Date;

/**
 * JMX MBean implementation.
 * Follows JMX Standard MBean naming convention:
 * - Interface: ByteBufFlowMBean
 * - Implementation: ByteBufFlow (this class)
 */
public class ByteBufFlow implements ByteBufFlowMBean {

    private final com.example.bytebuf.tracker.ByteBufFlowTracker tracker =
        com.example.bytebuf.tracker.ByteBufFlowTracker.getInstance();

    @Override
    public String getTreeView() {
        tracker.ensureGCProcessed();
        TrieRenderer renderer = new TrieRenderer(tracker.getTrie());
        return renderer.renderIndentedTree();
    }

    @Override
    public String getLLMView() {
        tracker.ensureGCProcessed();
        TrieRenderer renderer = new TrieRenderer(tracker.getTrie());
        return renderer.renderForLLM();
    }

    @Override
    public String getSummary() {
        tracker.ensureGCProcessed();
        TrieRenderer renderer = new TrieRenderer(tracker.getTrie());
        StringBuilder sb = new StringBuilder();

        sb.append("=== ByteBuf Flow Tracking Status ===\n");
        sb.append("Time: ").append(new Date()).append("\n");
        sb.append("Active Flows: ").append(tracker.getActiveFlowCount()).append("\n");
        sb.append("Root Methods: ").append(tracker.getTrie().getRootCount()).append("\n");
        sb.append("\n");
        sb.append(renderer.renderSummary());

        return sb.toString();
    }

    @Override
    public int getRootCount() {
        return tracker.getTrie().getRootCount();
    }

    @Override
    public int getActiveFlowCount() {
        return tracker.getActiveFlowCount();
    }

    @Override
    public void exportToFile(String filepath, String format) {
        tracker.ensureGCProcessed();
        TrieRenderer renderer = new TrieRenderer(tracker.getTrie());
        String content;

        switch (format.toLowerCase()) {
            case "tree":
                content = renderer.renderIndentedTree();
                break;
            case "llm":
                content = renderer.renderForLLM();
                break;
            default:
                content = "Unknown format: " + format + "\n" +
                         "Supported formats: tree, llm";
        }

        try (PrintWriter writer = new PrintWriter(new FileWriter(filepath))) {
            writer.write(content);
            System.out.println("[ByteBufFlowMBean] Exported to " + filepath);
        } catch (IOException e) {
            System.err.println("[ByteBufFlowMBean] Failed to export: " + e);
        }
    }

    @Override
    public void reset() {
        tracker.reset();
        System.out.println("[ByteBufFlowMBean] Tracker reset");
    }
}

/**
 * Reporter for generating comprehensive reports
 */
class ByteBufFlowReporter {

    private final ByteBufFlowTracker tracker = ByteBufFlowTracker.getInstance();

    /**
     * Generate a comprehensive report
     */
    public String generateReport() {
        tracker.ensureGCProcessed();
        TrieRenderer renderer = new TrieRenderer(tracker.getTrie());
        StringBuilder report = new StringBuilder();

        // Header
        report.append("ByteBuf Flow Analysis Report\n");
        report.append("Generated: ").append(new Date()).append("\n");
        StringBuilder padding = new StringBuilder();
        for (int i = 0; i < 80; i++) padding.append("=");
        report.append(padding).append("\n\n");

        // Summary
        report.append(renderer.renderSummary()).append("\n");

        // Tree view
        report.append("=== Flow Tree ===\n");
        report.append(renderer.renderIndentedTree()).append("\n");

        // Potential leaks - extract from LLM format
        report.append("=== Potential Leaks ===\n");
        String llmView = renderer.renderForLLM();
        String[] sections = llmView.split("\n\n");

        // Find the LEAKS section
        boolean foundLeaks = false;
        for (String section : sections) {
            if (section.startsWith("LEAKS:")) {
                String[] lines = section.split("\n");
                int leakCount = 0;
                for (int i = 1; i < lines.length; i++) { // Skip the "LEAKS:" header
                    String line = lines[i];
                    if (!line.trim().equals("(none)") && !line.trim().isEmpty()) {
                        // Parse LLM format: leak|root=X|final_ref=Y|path=Z
                        // Also parse CRITICAL_LEAK| for direct buffer leaks
                        if (line.startsWith("leak|") || line.startsWith("CRITICAL_LEAK|")) {
                            String[] parts = line.split("\\|");
                            String path = "";
                            String finalRef = "";
                            for (String part : parts) {
                                if (part.startsWith("path=")) {
                                    path = part.substring(5);
                                } else if (part.startsWith("final_ref=")) {
                                    finalRef = part.substring(10);
                                }
                            }
                            report.append("[LEAK:ref=").append(finalRef).append("] ").append(path).append("\n");
                            leakCount++;
                            foundLeaks = true;
                        }
                    }
                }
                if (leakCount == 0) {
                    report.append("No leaks detected\n");
                }
                break;
            }
        }

        if (!foundLeaks) {
            report.append("No leaks detected\n");
        }
        report.append("\n");

        // LLM format section - for programmatic analysis
        report.append("=== LLM Format ===\n");
        report.append(llmView).append("\n");

        return report.toString();
    }
}
