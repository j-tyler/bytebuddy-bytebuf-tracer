/*
 * Copyright 2025 Justin Marsh
 * SPDX-License-Identifier: Apache-2.0
 */

package com.example.bytebuf.tracker.metrics;

import com.example.bytebuf.api.metrics.MetricSnapshot;
import com.example.bytebuf.api.metrics.MetricType;
import com.example.bytebuf.tracker.ByteBufFlowTracker;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for MetricCollector.
 *
 * <p><b>Delta Behavior:</b> MetricCollector drains the pending leak queue on each capture,
 * so successive captures return only NEW leaks. Integration tests verify this behavior.
 */
public class MetricCollectorTest {

    @Test
    public void testSelectiveMetricCapture_OnlyDirectLeaks() {
        ByteBufFlowTracker tracker = ByteBufFlowTracker.getInstance();
        MetricCollector collector = new MetricCollector(tracker);

        // Request only DIRECT_LEAKS
        Set<MetricType> requested = EnumSet.of(MetricType.DIRECT_LEAKS);
        MetricSnapshot snapshot = collector.captureSnapshot(requested);

        // Direct leak fields should be populated (even if empty)
        assertNotNull(snapshot.getDirectLeakFlows());

        // Heap leak fields should be empty (not requested)
        assertEquals(0, snapshot.getTotalHeapLeaks());
        assertTrue(snapshot.getHeapLeakFlows().isEmpty());
    }

    @Test
    public void testSelectiveMetricCapture_OnlyHeapLeaks() {
        ByteBufFlowTracker tracker = ByteBufFlowTracker.getInstance();
        MetricCollector collector = new MetricCollector(tracker);

        // Request only HEAP_LEAKS
        Set<MetricType> requested = EnumSet.of(MetricType.HEAP_LEAKS);
        MetricSnapshot snapshot = collector.captureSnapshot(requested);

        // Heap leak fields should be populated (even if empty)
        assertNotNull(snapshot.getHeapLeakFlows());

        // Direct leak fields should be empty (not requested)
        assertEquals(0, snapshot.getTotalDirectLeaks());
        assertTrue(snapshot.getDirectLeakFlows().isEmpty());
    }

    @Test
    public void testSelectiveMetricCapture_BothTypes() {
        ByteBufFlowTracker tracker = ByteBufFlowTracker.getInstance();
        MetricCollector collector = new MetricCollector(tracker);

        // Request both types
        Set<MetricType> requested = EnumSet.of(MetricType.DIRECT_LEAKS, MetricType.HEAP_LEAKS);
        MetricSnapshot snapshot = collector.captureSnapshot(requested);

        // Both should be populated
        assertNotNull(snapshot.getDirectLeakFlows());
        assertNotNull(snapshot.getHeapLeakFlows());
    }

    @Test
    public void testSelectiveMetricCapture_NoneRequested() {
        ByteBufFlowTracker tracker = ByteBufFlowTracker.getInstance();
        MetricCollector collector = new MetricCollector(tracker);

        // Request nothing
        Set<MetricType> requested = EnumSet.noneOf(MetricType.class);
        MetricSnapshot snapshot = collector.captureSnapshot(requested);

        // Both should be empty
        assertEquals(0, snapshot.getTotalDirectLeaks());
        assertEquals(0, snapshot.getTotalHeapLeaks());
        assertTrue(snapshot.getDirectLeakFlows().isEmpty());
        assertTrue(snapshot.getHeapLeakFlows().isEmpty());
    }

    @Test
    public void testSnapshotHasTimestamp() {
        ByteBufFlowTracker tracker = ByteBufFlowTracker.getInstance();
        MetricCollector collector = new MetricCollector(tracker);

        long beforeCapture = System.currentTimeMillis();
        MetricSnapshot snapshot = collector.captureSnapshot(EnumSet.noneOf(MetricType.class));
        long afterCapture = System.currentTimeMillis();

        // Timestamp should be within reasonable range
        assertTrue(snapshot.getCaptureTimestamp() >= beforeCapture);
        assertTrue(snapshot.getCaptureTimestamp() <= afterCapture);
    }
}
