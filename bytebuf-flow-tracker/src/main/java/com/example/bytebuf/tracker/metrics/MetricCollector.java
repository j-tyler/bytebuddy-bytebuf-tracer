/*
 * Copyright 2025 Justin Marsh
 * SPDX-License-Identifier: Apache-2.0
 */

package com.example.bytebuf.tracker.metrics;

import com.example.bytebuf.api.metrics.MetricSnapshot;
import com.example.bytebuf.api.metrics.MetricType;
import com.example.bytebuf.tracker.ByteBufFlowTracker;
import com.example.bytebuf.tracker.trie.ImprintNode;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Collects delta-based metrics from the tracker.
 * Each capture drains pending leak events, reporting only NEW leaks since last capture.
 *
 * <p><b>Delta-Based Design:</b> Leaks are enqueued when detected (GC processing),
 * then drained on each metric push. This ensures handlers receive only new leaks,
 * not cumulative totals.
 */
public class MetricCollector {

    private final ByteBufFlowTracker tracker;

    public MetricCollector(ByteBufFlowTracker tracker) {
        this.tracker = tracker;
    }

    /**
     * Capture a snapshot of requested metrics.
     * Returns ONLY new leaks detected since the last capture (delta, not cumulative).
     *
     * @param requiredMetrics Set of metrics to capture
     * @return MetricSnapshot containing only new leaks since last drain
     */
    public MetricSnapshot captureSnapshot(Set<MetricType> requiredMetrics) {
        long captureTime = System.currentTimeMillis();

        // Process GC queue before draining (ensures leaked objects are detected)
        tracker.ensureGCProcessed();

        // Drain all pending leak events (delta since last drain)
        List<LeakEvent> events = tracker.drainPendingLeaks();

        long totalDirectLeaks = 0;
        long totalHeapLeaks = 0;
        List<String> directLeakFlows = new ArrayList<String>();
        List<String> heapLeakFlows = new ArrayList<String>();

        // Group events by flow path to count occurrences and build flow strings
        Map<FlowKey, LeakAggregation> directFlows = new HashMap<FlowKey, LeakAggregation>();
        Map<FlowKey, LeakAggregation> heapFlows = new HashMap<FlowKey, LeakAggregation>();

        for (LeakEvent event : events) {
            if (event.isDirect() && requiredMetrics.contains(MetricType.DIRECT_LEAKS)) {
                totalDirectLeaks++;
                FlowKey flowKey = new FlowKey(event.getLeafNode(), event.getRootMethod());
                LeakAggregation agg = directFlows.get(flowKey);
                if (agg == null) {
                    agg = new LeakAggregation(event);
                    directFlows.put(flowKey, agg);
                }
                agg.count++;
            } else if (!event.isDirect() && requiredMetrics.contains(MetricType.HEAP_LEAKS)) {
                totalHeapLeaks++;
                FlowKey flowKey = new FlowKey(event.getLeafNode(), event.getRootMethod());
                LeakAggregation agg = heapFlows.get(flowKey);
                if (agg == null) {
                    agg = new LeakAggregation(event);
                    heapFlows.put(flowKey, agg);
                }
                agg.count++;
            }
        }

        // Build flow representations (one per unique path)
        for (LeakAggregation agg : directFlows.values()) {
            String flowString = buildFlowRepresentation(agg);
            directLeakFlows.add(flowString);
        }

        for (LeakAggregation agg : heapFlows.values()) {
            String flowString = buildFlowRepresentation(agg);
            heapLeakFlows.add(flowString);
        }

        return new MetricSnapshot(captureTime, totalDirectLeaks, totalHeapLeaks,
                                 directLeakFlows, heapLeakFlows);
    }

    /**
     * Build LLM-optimized flow representation with leak count.
     * Format: "root=Method|final_ref=N|leak_count=N|path=A.m1[ref=N] -> B.m2[ref=N] -> C.m3[ref=N]"
     */
    private String buildFlowRepresentation(LeakAggregation agg) {
        StringBuilder sb = new StringBuilder();

        // Root method
        sb.append("root=").append(agg.event.getRootMethod());

        // Final ref count
        ImprintNode leafNode = agg.event.getLeafNode();
        sb.append("|final_ref=").append(leafNode.getRefCountForDisplay());

        // Leak count for this push interval
        sb.append("|leak_count=").append(agg.count);

        // Path - walk from leaf back to root building full flow path
        sb.append("|path=");
        buildPathString(leafNode, sb);

        return sb.toString();
    }

    /**
     * Build full path string by walking from leaf to root via parent pointers.
     * Output format: "Root.method1[ref=2] -> Child.method2[ref=1] -> Leaf.method3[ref=0]"
     */
    private void buildPathString(ImprintNode leafNode, StringBuilder sb) {
        List<ImprintNode> pathNodes = new ArrayList<ImprintNode>();
        ImprintNode current = leafNode;
        while (current != null) {
            pathNodes.add(current);
            current = current.getParent();
        }

        // Output root-to-leaf (same as TrieRenderer)
        for (int i = pathNodes.size() - 1; i >= 0; i--) {
            ImprintNode node = pathNodes.get(i);
            sb.append(node.getClassName()).append(".").append(node.getMethodName());
            sb.append("[ref=").append(node.getRefCountForDisplay()).append("]");
            if (i > 0) {
                sb.append(" -> ");
            }
        }
    }

    /**
     * Aggregates leak events by unique flow path.
     */
    private static class LeakAggregation {
        final LeakEvent event;  // Representative event for this path
        long count = 0;         // Number of leaks with this path

        LeakAggregation(LeakEvent event) {
            this.event = event;
        }
    }

    /**
     * Key for deduplicating leaks by flow path.
     * Uses object identity for ImprintNode (not hashCode) to avoid collisions.
     */
    private static class FlowKey {
        final ImprintNode node;
        final String rootMethod;

        FlowKey(ImprintNode node, String rootMethod) {
            this.node = node;
            this.rootMethod = rootMethod;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof FlowKey)) return false;
            FlowKey that = (FlowKey) o;
            return node == that.node && rootMethod.equals(that.rootMethod);
        }

        @Override
        public int hashCode() {
            return System.identityHashCode(node) * 31 + rootMethod.hashCode();
        }
    }
}
