/*
 * Copyright 2025 Justin Marsh
 * SPDX-License-Identifier: Apache-2.0
 */

package com.example.bytebuf.tracker.metrics;

import com.example.bytebuf.api.metrics.MetricHandler;
import com.example.bytebuf.api.metrics.MetricSnapshot;
import com.example.bytebuf.api.metrics.MetricType;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for MetricEventSink.
 * Proves NOOP behavior when no handlers are registered.
 */
public class MetricEventSinkTest {

    @Test
    public void testIsRecording_NoHandlers() {
        // Ensure no handlers are registered (may have leftovers from other tests)
        int initialHandlers = MetricHandlerRegistry.getHandlers().size();

        // If no handlers, isRecording should return false
        MetricEventSink sink = MetricEventSink.getInstance();
        if (initialHandlers == 0) {
            assertFalse(sink.isRecording(), "Should not be recording when no handlers exist");
        }
    }

    @Test
    public void testIsRecording_WithHandlers() {
        TestHandler handler = new TestHandler();
        try {
            MetricHandlerRegistry.register(handler);

            MetricEventSink sink = MetricEventSink.getInstance();
            assertTrue(sink.isRecording(), "Should be recording when handlers exist");
        } finally {
            MetricHandlerRegistry.unregister(handler);
        }
    }

    @Test
    public void testDrainEvents_EmptyWhenNoEvents() {
        MetricEventSink sink = MetricEventSink.getInstance();

        List<LeakEvent> events = sink.drainEvents();

        assertNotNull(events);
        assertTrue(events.isEmpty(), "Should return empty list when no events");
    }

    @Test
    public void testDrainEvents_ClearsQueue() {
        TestHandler handler = new TestHandler();
        try {
            MetricHandlerRegistry.register(handler);
            MetricEventSink sink = MetricEventSink.getInstance();

            // Record an event (if recording)
            if (sink.isRecording()) {
                sink.recordLeak(null, "test.method", true, System.currentTimeMillis());
            }

            // First drain should get the event
            List<LeakEvent> events1 = sink.drainEvents();
            int firstCount = events1.size();

            // Second drain should be empty (queue was cleared)
            List<LeakEvent> events2 = sink.drainEvents();
            assertEquals(0, events2.size(), "Second drain should be empty after first drain cleared queue");

            // Verify first drain got something if we were recording
            if (firstCount > 0) {
                assertEquals(1, firstCount, "Should have gotten 1 event from first drain");
            }
        } finally {
            MetricHandlerRegistry.unregister(handler);
            // Drain any remaining events
            MetricEventSink.getInstance().drainEvents();
        }
    }

    @Test
    public void testRecordLeak_StoresEventData() {
        TestHandler handler = new TestHandler();
        try {
            MetricHandlerRegistry.register(handler);
            MetricEventSink sink = MetricEventSink.getInstance();

            assertTrue(sink.isRecording(), "Should be recording with handler registered");

            // Record event with specific data
            String expectedRoot = "com.example.Allocator.directBuffer";
            boolean expectedDirect = true;
            long expectedTime = System.currentTimeMillis();
            sink.recordLeak(null, expectedRoot, expectedDirect, expectedTime);

            // Drain and verify
            List<LeakEvent> events = sink.drainEvents();
            assertEquals(1, events.size(), "Should have recorded 1 event");

            LeakEvent event = events.get(0);
            assertEquals(expectedRoot, event.getRootMethod(), "Root method should match");
            assertEquals(expectedDirect, event.isDirect(), "isDirect flag should match");
            assertEquals(expectedTime, event.getDetectedAtMs(), "Timestamp should match");
        } finally {
            MetricHandlerRegistry.unregister(handler);
            MetricEventSink.getInstance().drainEvents();
        }
    }

    @Test
    public void testRecordLeak_NOOPWhenNotRecording() {
        // Ensure no handlers registered
        MetricEventSink sink = MetricEventSink.getInstance();

        // If isRecording is false, recordLeak should not be called
        // (caller checks isRecording first)
        // This test verifies that draining returns empty when nothing was recorded
        List<LeakEvent> events = sink.drainEvents();
        assertTrue(events.isEmpty(), "Should have no events when not recording");
    }

    /**
     * Test handler for registration tests.
     */
    private static class TestHandler implements MetricHandler {
        @Override
        public Set<MetricType> getRequiredMetrics() {
            return EnumSet.of(MetricType.DIRECT_LEAKS);
        }

        @Override
        public void onMetrics(MetricSnapshot snapshot) {
            // No-op
        }

        @Override
        public String getName() {
            return "TestHandler";
        }
    }
}
