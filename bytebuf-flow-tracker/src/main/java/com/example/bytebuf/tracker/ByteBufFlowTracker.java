/*
 * Copyright 2025 Justin Marsh
 * SPDX-License-Identifier: Apache-2.0
 */

package com.example.bytebuf.tracker;

import com.example.bytebuf.tracker.active.WeakActiveFlow;
import com.example.bytebuf.tracker.active.WeakActiveTracker;
import com.example.bytebuf.tracker.metrics.LeakEvent;
import com.example.bytebuf.tracker.metrics.MetricEventSink;
import com.example.bytebuf.tracker.trie.BoundedImprintTrie;
import com.example.bytebuf.tracker.trie.ImprintNode;

import java.util.List;

/**
 * Main tracker with bounded memory guarantees.
 * Uses weak references for active tracking and immutable Trie for historical imprint.
 *
 * <p><b>Memory Bound:</b> (max_concurrent_objects × 80 bytes) + (MAX_NODES × 100 bytes) + ~50KB
 *
 * <p><b>Thread Safety:</b> This class is thread-safe. All public methods can be
 * called concurrently from multiple threads. The singleton instance is safely
 * published and all internal state is protected by thread-safe data structures.
 *
 * @see WeakActiveTracker
 * @see BoundedImprintTrie
 */
public class ByteBufFlowTracker {

    private static final ByteBufFlowTracker INSTANCE = new ByteBufFlowTracker();

    // Configuration (can be set via system properties)
    // Aggressive defaults: 1M nodes, depth 100
    private static final int DEFAULT_MAX_NODES = 1_000_000;
    private static final int DEFAULT_MAX_DEPTH = 100;

    // PART 1: Active tracking (self-limiting, bounded by concurrency)
    private final WeakActiveTracker activeTracker;

    // PART 2: Historical imprint (bounded by configuration)
    private final BoundedImprintTrie trie;

    private ByteBufFlowTracker() {
        int maxNodes = Integer.getInteger("bytebuf.tracker.maxNodes", DEFAULT_MAX_NODES);
        int maxDepth = Integer.getInteger("bytebuf.tracker.maxDepth", DEFAULT_MAX_DEPTH);

        this.trie = new BoundedImprintTrie(maxNodes, maxDepth);
        this.activeTracker = new WeakActiveTracker(trie);
    }

    /**
     * Record a method call involving a tracked object.
     *
     * <p><b>Thread Safety:</b> This method is thread-safe. The completed flag is checked
     * both before and after traversal to prevent race conditions where another thread
     * marks the flow as completed during traversal.
     *
     * @param obj the tracked object (e.g., ByteBuf)
     * @param className the class name containing the method
     * @param methodName the method name being called
     * @param refCount the current reference count (or metric value) of the object
     */
    /**
     * Record a method call for a tracked object.
     *
     * <p><b>Optimization (Idea 2):</b> Accepts pre-computed method signature instead of
     * separate className/methodName to eliminate runtime string concatenation.
     *
     * @param obj the tracked object (e.g., ByteBuf)
     * @param methodSignature pre-computed method signature (e.g., "MyClass.myMethod")
     * @param refCount the current metric value (e.g., refCount for ByteBuf)
     */
    public void recordMethodCall(Object obj, String methodSignature, int refCount) {
        if (obj == null) return;

        // Get or create active flow
        WeakActiveFlow flow = activeTracker.getOrCreate(obj, methodSignature);

        // Skip if flow is completed (already released)
        // Single volatile read - if marked completed during traversal below, that's fine
        // since the flow remains in activeFlows until GC anyway
        if (flow.isCompleted()) {
            return;
        }

        // Parse method signature to extract className and methodName for trie node creation
        // Cost: Only paid when traversing (not when skipping completed flows)
        // Format: "ClassName.methodName" or "ClassName.methodName_return"
        int lastDotIndex = methodSignature.lastIndexOf('.');
        String className = lastDotIndex > 0 ? methodSignature.substring(0, lastDotIndex) : methodSignature;
        String methodName = lastDotIndex > 0 ? methodSignature.substring(lastDotIndex + 1) : "";

        // Traverse Trie (respecting depth limit)
        // Read currentDepth once to avoid inconsistent reads across two calls
        int currentDepth = flow.getCurrentDepth();
        if (currentDepth < trie.getMaxDepth()) {
            ImprintNode nextNode = trie.traverseOrCreate(
                flow.getCurrentNode(),
                className,
                methodName,
                refCount,
                currentDepth
            );

            flow.setCurrentNode(nextNode);
            flow.incrementDepth();
        }

        // If object released, record as clean
        if (refCount == 0) {
            activeTracker.recordCleanRelease(System.identityHashCode(obj));
        }
    }

    /**
     * Get the Trie for rendering/analysis.
     *
     * <p><b>WARNING:</b> The returned trie is mutable and shared with the tracker.
     * Callers must NOT call mutating methods like {@link BoundedImprintTrie#clear()}
     * as this will corrupt tracking state. Use only read-only methods like
     * {@link BoundedImprintTrie#getRoots()}, {@link BoundedImprintTrie#getNodeCount()}, etc.
     *
     * @return the underlying trie (shared, mutable - use with caution)
     */
    public BoundedImprintTrie getTrie() {
        return trie;
    }

    /**
     * Get active flow count (currently tracked objects).
     */
    public int getActiveFlowCount() {
        return activeTracker.getActiveCount();
    }

    /**
     * Get tracking statistics.
     */
    public TrackingStats getTrackingStats() {
        return new TrackingStats(
            activeTracker.getTotalObjectsSeen(),
            activeTracker.getTotalCleaned(),
            activeTracker.getTotalLeaked(),
            activeTracker.getTotalGCDetected(),
            activeTracker.getActiveCount()
        );
    }

    /**
     * Check if an object is currently being tracked in a flow.
     */
    public boolean isTracking(Object obj) {
        return activeTracker.getActiveCount() > 0;  // Simplified check
    }

    /**
     * Reset all tracking data.
     */
    public void reset() {
        activeTracker.markRemainingAsLeaks();  // Finalize any active flows
        trie.clear();
    }

    /**
     * Ensure GC queue is processed for current state.
     * Renderers should call this before rendering to ensure they see all leaked objects.
     */
    public void ensureGCProcessed() {
        activeTracker.ensureGCProcessed();
    }

    /**
     * Drain pending leak events for delta-based metrics.
     * Returns all leaks detected since the last drain.
     * MetricCollector calls this during each push to get only new leaks.
     *
     * @return List of leak events (never null, may be empty)
     */
    public List<LeakEvent> drainPendingLeaks() {
        return MetricEventSink.getInstance().drainEvents();
    }

    /**
     * Shutdown hook - mark all remaining flows as leaks.
     */
    public void onShutdown() {
        // Process any GC'd objects
        activeTracker.processAllGCQueue();

        // Mark remaining active flows as leaks
        activeTracker.markRemainingAsLeaks();
    }

    public static ByteBufFlowTracker getInstance() {
        return INSTANCE;
    }

    /**
     * Tracking statistics.
     */
    public static class TrackingStats {
        public final long totalSeen;
        public final long totalCleaned;
        public final long totalLeaked;
        public final long gcDetected;
        public final int currentlyActive;

        public TrackingStats(long totalSeen, long totalCleaned, long totalLeaked,
                           long gcDetected, int currentlyActive) {
            this.totalSeen = totalSeen;
            this.totalCleaned = totalCleaned;
            this.totalLeaked = totalLeaked;
            this.gcDetected = gcDetected;
            this.currentlyActive = currentlyActive;
        }

        public double getLeakRate() {
            long completed = totalCleaned + totalLeaked;
            return completed == 0 ? 0.0 : (totalLeaked * 100.0) / completed;
        }

        @Override
        public String toString() {
            return String.format(
                "Seen: %d, Cleaned: %d, Leaked: %d (%.2f%%), GC-detected: %d, Active: %d",
                totalSeen, totalCleaned, totalLeaked, getLeakRate(), gcDetected, currentlyActive
            );
        }
    }
}
