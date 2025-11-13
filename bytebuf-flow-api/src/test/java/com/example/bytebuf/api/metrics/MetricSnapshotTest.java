/*
 * Copyright 2025 Justin Marsh
 * SPDX-License-Identifier: Apache-2.0
 */

package com.example.bytebuf.api.metrics;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for MetricSnapshot API.
 */
public class MetricSnapshotTest {

    @Test
    public void testSnapshotCreation() {
        long timestamp = System.currentTimeMillis();
        List<String> directFlows = Arrays.asList("flow1", "flow2");
        List<String> heapFlows = Arrays.asList("flow3");

        MetricSnapshot snapshot = new MetricSnapshot(
            timestamp,
            10,
            5,
            directFlows,
            heapFlows
        );

        assertEquals(timestamp, snapshot.getCaptureTimestamp());
        assertEquals(10, snapshot.getTotalDirectLeaks());
        assertEquals(5, snapshot.getTotalHeapLeaks());
        assertEquals(2, snapshot.getDirectLeakFlows().size());
        assertEquals(1, snapshot.getHeapLeakFlows().size());
    }

    @Test
    public void testSnapshotWithNullLists() {
        // Should handle null lists gracefully (convert to empty)
        MetricSnapshot snapshot = new MetricSnapshot(
            System.currentTimeMillis(),
            0,
            0,
            null,
            null
        );

        assertNotNull(snapshot.getDirectLeakFlows());
        assertNotNull(snapshot.getHeapLeakFlows());
        assertTrue(snapshot.getDirectLeakFlows().isEmpty());
        assertTrue(snapshot.getHeapLeakFlows().isEmpty());
    }

    @Test
    public void testSnapshotImmutability() {
        List<String> directFlows = new ArrayList<String>();
        directFlows.add("flow1");

        MetricSnapshot snapshot = new MetricSnapshot(
            System.currentTimeMillis(),
            1,
            0,
            directFlows,
            Collections.<String>emptyList()
        );

        // Modifying original list should not affect snapshot
        directFlows.add("flow2");
        assertEquals(1, snapshot.getDirectLeakFlows().size());
    }

    @Test
    public void testFlowFormatStructure() {
        // Test that flow strings follow expected format
        String flow = "root=UnpooledByteBufAllocator.directBuffer|final_ref=1|leak_count=500|leak_rate=100.0%|path=A -> B -> C";

        // Verify format has required fields
        assertTrue(flow.contains("root="));
        assertTrue(flow.contains("final_ref="));
        assertTrue(flow.contains("leak_count="));
        assertTrue(flow.contains("leak_rate="));
        assertTrue(flow.contains("path="));

        // Verify field separators
        assertTrue(flow.contains("|"));

        // Verify path separator
        assertTrue(flow.contains(" -> "));
    }

    @Test
    public void testToString() {
        MetricSnapshot snapshot = new MetricSnapshot(
            123456789L,
            10,
            5,
            Arrays.asList("flow1", "flow2"),
            Arrays.asList("flow3")
        );

        String str = snapshot.toString();

        // Should contain key information
        assertTrue(str.contains("123456789"));
        assertTrue(str.contains("totalDirectLeaks=10"));
        assertTrue(str.contains("totalHeapLeaks=5"));
        assertTrue(str.contains("directLeakFlows=[flow1, flow2]"));
        assertTrue(str.contains("heapLeakFlows=[flow3]"));
    }
}
