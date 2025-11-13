/*
 * Copyright 2025 Justin Marsh
 * SPDX-License-Identifier: Apache-2.0
 */

package com.example.bytebuf.tracker.metrics;

import com.example.bytebuf.api.metrics.MetricHandler;
import com.example.bytebuf.api.metrics.MetricSnapshot;
import com.example.bytebuf.api.metrics.MetricType;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for MetricHandlerRegistry.
 * Note: Registry is static, so tests register/unregister handlers explicitly.
 */
public class MetricHandlerRegistryTest {

    @Test
    public void testProgrammaticRegistration() {
        TestHandler handler = new TestHandler("TestHandler1");
        try {
            MetricHandlerRegistry.register(handler);
            List<MetricHandler> handlers = MetricHandlerRegistry.getHandlers();
            assertTrue(handlers.contains(handler));
            assertEquals("TestHandler1", handler.getName());
        } finally {
            MetricHandlerRegistry.unregister(handler);
        }
    }

    @Test
    public void testMultipleHandlerRegistration() {
        TestHandler handler1 = new TestHandler("Handler1");
        TestHandler handler2 = new TestHandler("Handler2");
        try {
            int initialSize = MetricHandlerRegistry.getHandlers().size();

            MetricHandlerRegistry.register(handler1);
            MetricHandlerRegistry.register(handler2);

            List<MetricHandler> handlers = MetricHandlerRegistry.getHandlers();
            assertEquals(initialSize + 2, handlers.size());
            assertTrue(handlers.contains(handler1));
            assertTrue(handlers.contains(handler2));
        } finally {
            MetricHandlerRegistry.unregister(handler1);
            MetricHandlerRegistry.unregister(handler2);
        }
    }

    @Test
    public void testRequiredMetricsAggregation() {
        // Handler 1 requests only direct leaks
        MetricHandler handler1 = new MetricHandler() {
            public Set<MetricType> getRequiredMetrics() {
                return EnumSet.of(MetricType.DIRECT_LEAKS);
            }
            public void onMetrics(MetricSnapshot snapshot) {}
            public String getName() { return "Handler1"; }
        };

        // Handler 2 requests only heap leaks
        MetricHandler handler2 = new MetricHandler() {
            public Set<MetricType> getRequiredMetrics() {
                return EnumSet.of(MetricType.HEAP_LEAKS);
            }
            public void onMetrics(MetricSnapshot snapshot) {}
            public String getName() { return "Handler2"; }
        };

        try {
            MetricHandlerRegistry.register(handler1);
            MetricHandlerRegistry.register(handler2);

            // Should aggregate to both types
            Set<MetricType> required = MetricHandlerRegistry.getRequiredMetrics();
            assertEquals(2, required.size());
            assertTrue(required.contains(MetricType.DIRECT_LEAKS));
            assertTrue(required.contains(MetricType.HEAP_LEAKS));
        } finally {
            MetricHandlerRegistry.unregister(handler1);
            MetricHandlerRegistry.unregister(handler2);
        }
    }

    @Test
    public void testHasHandlers() {
        // Registry may have handlers from static init, so just test registration adds one
        int initialSize = MetricHandlerRegistry.getHandlers().size();
        assertTrue(MetricHandlerRegistry.hasHandlers() == (initialSize > 0));

        TestHandler handler = new TestHandler("Test");
        try {
            MetricHandlerRegistry.register(handler);
            assertTrue(MetricHandlerRegistry.hasHandlers());
        } finally {
            MetricHandlerRegistry.unregister(handler);
        }
    }

    @Test
    public void testUnregister() {
        TestHandler handler1 = new TestHandler("Test1");
        TestHandler handler2 = new TestHandler("Test2");

        MetricHandlerRegistry.register(handler1);
        MetricHandlerRegistry.register(handler2);
        int sizeWithBoth = MetricHandlerRegistry.getHandlers().size();

        MetricHandlerRegistry.unregister(handler1);
        assertEquals(sizeWithBoth - 1, MetricHandlerRegistry.getHandlers().size());
        assertFalse(MetricHandlerRegistry.getHandlers().contains(handler1));
        assertTrue(MetricHandlerRegistry.getHandlers().contains(handler2));

        MetricHandlerRegistry.unregister(handler2);
        assertEquals(sizeWithBoth - 2, MetricHandlerRegistry.getHandlers().size());
        assertFalse(MetricHandlerRegistry.getHandlers().contains(handler2));
    }

    /**
     * Simple test handler implementation.
     */
    private static class TestHandler implements MetricHandler {
        private final String name;

        public TestHandler(String name) {
            this.name = name;
        }

        @Override
        public Set<MetricType> getRequiredMetrics() {
            return EnumSet.of(MetricType.DIRECT_LEAKS, MetricType.HEAP_LEAKS);
        }

        @Override
        public void onMetrics(MetricSnapshot snapshot) {
            // No-op for testing
        }

        @Override
        public String getName() {
            return name;
        }
    }
}
