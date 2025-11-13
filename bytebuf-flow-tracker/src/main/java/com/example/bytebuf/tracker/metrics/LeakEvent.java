/*
 * Copyright 2025 Justin Marsh
 * SPDX-License-Identifier: Apache-2.0
 */

package com.example.bytebuf.tracker.metrics;

import com.example.bytebuf.tracker.trie.ImprintNode;

/**
 * Immutable event capturing a detected leak for delta-based metrics.
 *
 * <p>When the GC detects a leaked object, ByteBufFlowTracker creates a LeakEvent
 * and adds it to a pending queue. MetricCollector drains this queue during each
 * push, ensuring only NEW leaks are reported (not cumulative).
 */
public final class LeakEvent {

    private final ImprintNode leafNode;
    private final String rootMethod;
    private final boolean isDirect;
    private final long detectedAtMs;

    public LeakEvent(ImprintNode leafNode, String rootMethod, boolean isDirect, long detectedAtMs) {
        this.leafNode = leafNode;
        this.rootMethod = rootMethod;
        this.isDirect = isDirect;
        this.detectedAtMs = detectedAtMs;
    }

    public ImprintNode getLeafNode() {
        return leafNode;
    }

    public String getRootMethod() {
        return rootMethod;
    }

    public boolean isDirect() {
        return isDirect;
    }

    public long getDetectedAtMs() {
        return detectedAtMs;
    }

    @Override
    public String toString() {
        return "LeakEvent{" +
                "rootMethod=" + rootMethod +
                ", isDirect=" + isDirect +
                ", detectedAt=" + detectedAtMs +
                ", leafNode=" + leafNode.getClassName() + "." + leafNode.getMethodName() +
                '}';
    }
}
