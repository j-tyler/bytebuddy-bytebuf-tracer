/*
 * Copyright 2025 Justin Marsh
 * SPDX-License-Identifier: Apache-2.0
 */

package com.example.bytebuf.tracker.metrics;

import com.example.bytebuf.tracker.trie.ImprintNode;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Coordinates leak event recording with metric handler registration.
 *
 * <p>Provides NOOP behavior via {@link #isRecording()} when no handlers exist,
 * preventing wasted allocations.
 *
 * <p><b>Queue Growth:</b> The queue is unbounded but naturally limited by
 * (leak detection rate × push interval). If the application leaks faster than
 * handlers can consume, the queue grows until the next push drains it.
 * Applications with pathological leak rates should fix their leaks rather than
 * rely on event dropping.
 *
 * <p><b>Thread Safety:</b> All methods are thread-safe.
 */
public class MetricEventSink {

    private static final MetricEventSink INSTANCE = new MetricEventSink();

    private final ConcurrentLinkedQueue<LeakEvent> pendingEvents = new ConcurrentLinkedQueue<LeakEvent>();

    private MetricEventSink() {
    }

    /**
     * Check if leak events should be recorded.
     * Returns false when no metric handlers are registered (NOOP mode).
     *
     * <p>Callers should check this before constructing LeakEvent objects
     * to avoid wasted allocations.
     *
     * @return true if events should be recorded, false otherwise
     */
    public boolean isRecording() {
        return MetricHandlerRegistry.hasHandlers();
    }

    /**
     * Record a leak event for metrics.
     * Only call this if {@link #isRecording()} returns true.
     *
     * @param leafNode The trie node where leak occurred
     * @param rootMethod The allocator method (e.g., "UnpooledByteBufAllocator.directBuffer")
     * @param isDirect Whether this is a direct buffer leak
     * @param detectedAtMs Timestamp when leak was detected
     */
    public void recordLeak(ImprintNode leafNode, String rootMethod, boolean isDirect, long detectedAtMs) {
        LeakEvent event = new LeakEvent(leafNode, rootMethod, isDirect, detectedAtMs);
        pendingEvents.add(event);
    }

    /**
     * Drain all pending leak events.
     * Returns all events since the last drain and clears the queue.
     *
     * <p>Called by MetricCollector during each metric push.
     *
     * @return List of leak events (never null, may be empty)
     */
    public List<LeakEvent> drainEvents() {
        List<LeakEvent> events = new ArrayList<LeakEvent>();
        LeakEvent event;
        while ((event = pendingEvents.poll()) != null) {
            events.add(event);
        }
        return events;
    }

    /**
     * Get the singleton instance.
     */
    public static MetricEventSink getInstance() {
        return INSTANCE;
    }
}
