/*
 * Copyright 2025 Justin Marsh
 * SPDX-License-Identifier: Apache-2.0
 */

package com.example.bytebuf.tracker.trie;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Immutable-identity Trie node that stores clean/leak statistics.
 * Memory-efficient design with string interning and bucketed refCounts.
 *
 * <p><b>Memory Optimization:</b> Uses lazy children map initialization to save memory
 * on leaf nodes (~50-70% of all nodes). Dual-reference pattern (volatile + non-volatile)
 * eliminates memory barrier overhead after initialization while maintaining thread safety:
 * <ul>
 *   <li>Fast path: Check non-volatile {@code children} (no memory barrier)</li>
 *   <li>Slow path: Check volatile {@code visibleChildren} for initialization status</li>
 *   <li>Memory cost: +8 bytes per node, but saves 32 bytes on 50-70% of nodes (net win)</li>
 * </ul>
 *
 * <p><b>Thread Safety:</b> This class is thread-safe. The node identity
 * (methodSignature, refCountBucket) is immutable. Statistics and children
 * are protected using atomic operations and {@link java.util.concurrent.ConcurrentHashMap}.
 *
 * <p><b>Memory Bounds:</b> Each node limits children to {@value #MAX_CHILDREN_PER_NODE}
 * to prevent path explosion. When this limit is reached, the node stops accepting new
 * children to avoid concurrency overhead from eviction (cache coherency, atomic reads).
 *
 * @see BoundedImprintTrie
 */
public class ImprintNode {

    // Immutable identity (interned for memory efficiency)
    // Store only methodSignature (className.methodName) to save 16 bytes per node
    // Previously stored: className (8 bytes) + methodName (8 bytes) + methodSignature (8 bytes) = 24 bytes
    // Now stores: methodSignature (8 bytes) = 8 bytes
    // Savings: 16 bytes per node (eliminated 2 redundant string references)
    private final String methodSignature;  // 8 bytes (reference to interned string)
    private final byte refCountBucket;     // 1 byte (0=zero, 1=low, 2=med, 3=high)

    // Parent node for path reconstruction (null for root nodes)
    // +8 bytes per node, but enables full flow path in metrics
    private final ImprintNode parent;    // 8 bytes (null for roots)

    // Traversal count (for all nodes, including intermediate)
    private final AtomicLong traversalCount = new AtomicLong(0);

    // Outcome statistics (atomic for thread-safety - for leaf nodes)
    private final AtomicLong cleanCount = new AtomicLong(0);  // Released properly (refCnt→0)
    private final AtomicLong leakCount = new AtomicLong(0);   // GC'd without release

    // Children (shared Trie structure) - lazy initialized for memory efficiency
    // Dual-reference pattern: non-volatile for fast access, volatile for safe initialization
    private Map<NodeKey, ImprintNode> children;              // Fast path (no memory barrier)
    private volatile Map<NodeKey, ImprintNode> visibleChildren;  // Safe initialization guard

    // Memory bound enforcement
    // Value of 1000 chosen to provide headroom for pathological branching cases.
    // Typical observations (not rigorously measured):
    // - Most method calls: 1-10 downstream paths
    // - Large switch statements/routers: 100-200 paths
    // - Extreme cases (e.g., message type dispatchers): 300-500 paths
    //
    // Tradeoff analysis:
    // - GAIN: Eliminates eviction overhead (~50-200ns + cache coherency cost)
    // - GAIN: Predictable, deterministic behavior (first-N-paths)
    // - COST: Paths beyond 1000 are dropped (stop-on-limit, not tracked)
    // - MITIGATION: Global maxTotalNodes (1M default) still enforces memory bounds
    // - MITIGATION: In practice, first 1000 paths per node cover majority of traffic
    //
    // When limit is hit: node stops accepting new children (returns self as leaf)
    //
    // NOTE: This is currently not configurable to keep memory bounds predictable,
    // but could be made configurable via system property if needed.
    private static final int MAX_CHILDREN_PER_NODE = 1000;

    public ImprintNode(String methodSignature, byte refCountBucket, ImprintNode parent) {
        this.methodSignature = methodSignature;
        this.refCountBucket = refCountBucket;
        this.parent = parent;
    }

    /**
     * Get or create a child node.
     * Enforces per-node branching limit to prevent path explosion.
     *
     * <p><b>Performance Optimization:</b> Uses dual-reference pattern for lazy initialization:
     * <ol>
     *   <li>Fast path: Check non-volatile {@code children} (no memory barrier)</li>
     *   <li>If null, check volatile {@code visibleChildren} (with memory barrier)</li>
     *   <li>If both null, lock and initialize both references</li>
     * </ol>
     * This eliminates memory barrier overhead on subsequent accesses by the same thread
     * after it has observed initialization, while maintaining thread-safe lazy initialization.
     *
     * @param methodSignature Interned method signature (className.methodName)
     * @param refCountBucket Reference count bucket (0-3)
     * @return The child node (existing or newly created)
     */
    public ImprintNode getOrCreateChild(String methodSignature, byte refCountBucket) {
        // Fast path: Check non-volatile children first (no memory barrier)
        Map<NodeKey, ImprintNode> localChildren = children;
        if (localChildren == null) {
            // Slow path: Check volatile visibleChildren (with memory barrier)
            localChildren = visibleChildren;
            if (localChildren == null) {
                // Double-checked locking: Initialize children map
                synchronized (this) {
                    localChildren = visibleChildren;
                    if (localChildren == null) {
                        localChildren = new ConcurrentHashMap<>(4);  // Small initial capacity
                        this.children = localChildren;              // Set non-volatile reference
                        this.visibleChildren = localChildren;       // Publish via volatile write
                    } else {
                        // Another thread initialized it, use their instance
                        // Safe to write non-volatile: the volatile read above establishes
                        // happens-before, so this write won't be reordered before initialization
                        this.children = localChildren;
                    }
                }
            } else {
                // Another thread initialized it, cache locally for fast access
                // Safe to write non-volatile: the volatile read above establishes
                // happens-before, so this write won't be reordered before initialization
                this.children = localChildren;
            }
        }

        // Now we have a valid children map, proceed with normal logic
        // Use the pre-interned methodSignature for compressed NodeKey (saves 8 bytes per key)
        NodeKey key = new NodeKey(methodSignature, refCountBucket);

        ImprintNode existing = localChildren.get(key);
        if (existing != null) {
            return existing;
        }

        // Check branching limit - if reached, stop tracking this branch
        // No eviction to avoid concurrency overhead (cache coherency, atomic reads, map writes)
        if (localChildren.size() >= MAX_CHILDREN_PER_NODE) {
            // Return self to act as leaf node (stops further tracking down this path)
            return this;
        }

        // Create new child with this node as parent
        ImprintNode newChild = new ImprintNode(methodSignature, refCountBucket, this);
        ImprintNode result = localChildren.putIfAbsent(key, newChild);
        return result != null ? result : newChild;
    }

    /**
     * Record traversal through this node (called every time we pass through).
     */
    public void recordTraversal() {
        traversalCount.incrementAndGet();
    }

    /**
     * Record that a path ending at this node was completed.
     * @param wasClean true if released (refCnt=0), false if leaked (GC'd)
     */
    public void recordOutcome(boolean wasClean) {
        if (wasClean) {
            cleanCount.incrementAndGet();
        } else {
            leakCount.incrementAndGet();
        }
    }


    // Getters
    public String getClassName() {
        int lastDot = methodSignature.lastIndexOf('.');
        if (lastDot == -1) {
            // Malformed signature, return as-is
            return methodSignature;
        }
        return methodSignature.substring(0, lastDot);
    }

    public String getMethodName() {
        int lastDot = methodSignature.lastIndexOf('.');
        if (lastDot == -1) {
            // Malformed signature, return as-is
            return methodSignature;
        }
        return methodSignature.substring(lastDot + 1);
    }
    public byte getRefCountBucket() { return refCountBucket; }
    public long getTraversalCount() { return traversalCount.get(); }
    public long getCleanCount() { return cleanCount.get(); }
    public long getLeakCount() { return leakCount.get(); }
    public long getTotalCount() { return cleanCount.get() + leakCount.get(); }

    /**
     * Get the children map. Returns empty map if this is a leaf node.
     * This method checks visibleChildren (volatile) for thread-safe access.
     */
    public Map<NodeKey, ImprintNode> getChildren() {
        Map<NodeKey, ImprintNode> localChildren = visibleChildren;  // Read volatile field
        if (localChildren == null) {
            return Collections.emptyMap();  // Leaf node - no children
        }
        return Collections.unmodifiableMap(localChildren);
    }

    /**
     * Get parent node (null for root nodes).
     * Used for path reconstruction in metrics.
     */
    public ImprintNode getParent() {
        return parent;
    }

    /**
     * Check if this is a leaf node (no children).
     * Checks visibleChildren (volatile) for thread-safe access.
     */
    public boolean isLeaf() {
        return visibleChildren == null;  // Check volatile field
    }

    /**
     * Get the actual refCount value from the bucket (for display purposes).
     * Returns a representative value from the bucket.
     */
    public int getRefCountForDisplay() {
        switch (refCountBucket) {
            case 0: return 0;
            case 1: return 1;  // LOW (1-2)
            case 2: return 3;  // MEDIUM (3-5)
            case 3: return 6;  // HIGH (6+)
            default: return -1;
        }
    }

    /**
     * Node key for child lookup - compressed to reduce memory overhead.
     *
     * <p><b>Memory Optimization:</b> Stores methodSignature as single string
     * (className + "." + methodName) instead of two separate strings, saving
     * 8 bytes per NodeKey instance.
     *
     * <p>Uses interned strings for memory efficiency and identity-based hashing.
     *
     * <p><b>Memory Layout:</b>
     * <ul>
     *   <li>Before: className (8) + methodName (8) + refCountBucket (1) + hashCode (4) + padding (3) = 24 bytes</li>
     *   <li>After: methodSignature (8) + refCountBucket (1) + hashCode (4) + padding (3) = 16 bytes</li>
     *   <li>Savings: 8 bytes per NodeKey (33% reduction)</li>
     * </ul>
     */
    public static class NodeKey {
        public final String methodSignature;  // className.methodName (interned)
        public final byte refCountBucket;
        private final int hashCode;

        public NodeKey(String methodSignature, byte refCountBucket) {
            this.methodSignature = methodSignature;
            this.refCountBucket = refCountBucket;
            // Use identity hashcode for consistency with identity-based equals().
            // Since strings are interned and equals() uses ==, identity hashcode is
            // more semantically correct. Performance is equivalent to String.hashCode()
            // (which is cached), so this is chosen for correctness, not performance.
            this.hashCode = computeHashCode();
        }

        private int computeHashCode() {
            int result = System.identityHashCode(methodSignature);
            result = 31 * result + (int) refCountBucket;
            return result;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof NodeKey)) return false;
            NodeKey that = (NodeKey) o;
            // Since strings are interned, can use == for comparison
            return refCountBucket == that.refCountBucket &&
                   methodSignature == that.methodSignature;
        }

        @Override
        public int hashCode() {
            return hashCode;
        }
    }
}
