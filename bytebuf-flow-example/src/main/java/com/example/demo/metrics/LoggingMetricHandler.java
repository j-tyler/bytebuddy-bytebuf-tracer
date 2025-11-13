/*
 * Copyright 2025 Justin Marsh
 * SPDX-License-Identifier: Apache-2.0
 */

package com.example.demo.metrics;

import com.example.bytebuf.api.metrics.MetricHandler;
import com.example.bytebuf.api.metrics.MetricSnapshot;
import com.example.bytebuf.api.metrics.MetricType;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Example metric handler that logs leaks to stdout.
 * In production, you would use a proper logging framework (SLF4J, Log4j, etc.)
 * and send alerts when critical leaks are detected.
 */
public class LoggingMetricHandler implements MetricHandler {

    private static final SimpleDateFormat DATE_FORMAT = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");

    @Override
    public Set<MetricType> getRequiredMetrics() {
        // Request both direct and heap leaks
        return EnumSet.of(MetricType.DIRECT_LEAKS, MetricType.HEAP_LEAKS);
    }

    @Override
    public void onMetrics(MetricSnapshot snapshot) {
        System.out.println("\n========== ByteBuf Leak Metrics ==========");
        System.out.println("Snapshot captured at: " + formatTimestamp(snapshot.getCaptureTimestamp()));

        // Process direct leaks (critical - off-heap, never GC'd)
        long totalDirectLeaks = snapshot.getTotalDirectLeaks();
        List<String> directLeakFlows = snapshot.getDirectLeakFlows();

        if (totalDirectLeaks > 0) {
            System.out.println("\n🚨 CRITICAL: " + totalDirectLeaks + " Direct Buffer Leaks Detected:");
            System.out.println("   (" + directLeakFlows.size() + " unique leak paths)");
            for (String flow : directLeakFlows) {
                System.out.println("  " + flow);
                System.out.println();
            }
        } else {
            System.out.println("\n✓ No direct buffer leaks detected");
        }

        // Process heap leaks (moderate - eventually GC'd)
        long totalHeapLeaks = snapshot.getTotalHeapLeaks();
        List<String> heapLeakFlows = snapshot.getHeapLeakFlows();

        if (totalHeapLeaks > 0) {
            System.out.println("\n⚠️  WARNING: " + totalHeapLeaks + " Heap Buffer Leaks Detected:");
            System.out.println("   (" + heapLeakFlows.size() + " unique leak paths)");
            // Only show first 5 to avoid overwhelming output
            int count = Math.min(5, heapLeakFlows.size());
            for (int i = 0; i < count; i++) {
                System.out.println("  " + heapLeakFlows.get(i));
                System.out.println();
            }
            if (heapLeakFlows.size() > 5) {
                System.out.println("  ... and " + (heapLeakFlows.size() - 5) + " more heap leak paths");
            }
        } else {
            System.out.println("\n✓ No heap buffer leaks detected");
        }

        System.out.println("==========================================\n");
    }

    @Override
    public String getName() {
        return "LoggingMetricHandler";
    }

    private String formatTimestamp(long timestampMs) {
        return DATE_FORMAT.format(new Date(timestampMs));
    }
}
