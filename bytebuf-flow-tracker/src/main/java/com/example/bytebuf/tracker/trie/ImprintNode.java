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
 * <p><b>Thread Safety:</b> This class is thread-safe. The node identity
 * (className, methodName, refCountBucket) is immutable. Statistics and children
 * are protected using atomic operations and {@link java.util.concurrent.ConcurrentHashMap}.
 *
 * <p><b>Memory Bounds:</b> Each node limits children to {@value #MAX_CHILDREN_PER_NODE}
 * to prevent path explosion. When this limit is reached, the least frequently used
 * child is evicted.
 *
 * @see BoundedImprintTrie
 */
public class ImprintNode {

    // Immutable identity (interned for memory efficiency)
    private final String className;      // 8 bytes (reference to interned string)
    private final String methodName;     // 8 bytes (reference to interned string)
    private final byte refCountBucket;   // 1 byte (0=zero, 1=low, 2=med, 3=high)

    // Traversal count (for all nodes, including intermediate)
    private final AtomicLong traversalCount = new AtomicLong(0);

    // Outcome statistics (atomic for thread-safety - for leaf nodes)
    private final AtomicLong cleanCount = new AtomicLong(0);  // Released properly (refCnt→0)
    private final AtomicLong leakCount = new AtomicLong(0);   // GC'd without release

    // Children (shared Trie structure)
    private final Map<NodeKey, ImprintNode> children = new ConcurrentHashMap<>();

    // Memory bound enforcement
    // Value of 100 chosen based on:
    // - Typical method calls have 1-10 downstream paths (P50)
    // - Pathological cases (e.g., switch statements) have 20-50 paths (P95)
    // - 100 provides headroom for unusual patterns while preventing explosion
    // - If this limit is hit, LFU eviction kicks in to remove least-used paths
    // NOTE: This is currently not configurable to keep memory bounds predictable,
    // but could be made configurable via system property if needed.
    private static final int MAX_CHILDREN_PER_NODE = 100;

    public ImprintNode(String className, String methodName, byte refCountBucket) {
        this.className = className;
        this.methodName = methodName;
        this.refCountBucket = refCountBucket;
    }

    /**
     * Get or create a child node.
     * Enforces per-node branching limit to prevent path explosion.
     */
    public ImprintNode getOrCreateChild(String className, String methodName, byte refCountBucket) {
        NodeKey key = new NodeKey(className, methodName, refCountBucket);

        ImprintNode existing = children.get(key);
        if (existing != null) {
            return existing;
        }

        // Check branching limit
        if (children.size() >= MAX_CHILDREN_PER_NODE) {
            // Evict least-traversed child
            evictLeastUsedChild();
        }

        // Create new child
        ImprintNode newChild = new ImprintNode(className, methodName, refCountBucket);
        ImprintNode result = children.putIfAbsent(key, newChild);
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

    /**
     * Evict the child with the lowest total traversal count (LFU eviction).
     * Uses key-based removal to avoid fragile identity comparisons.
     */
    private void evictLeastUsedChild() {
        NodeKey leastUsedKey = null;
        long minCount = Long.MAX_VALUE;

        // Find the key of the least used child
        for (Map.Entry<NodeKey, ImprintNode> entry : children.entrySet()) {
            long totalCount = entry.getValue().getTotalCount();
            if (totalCount < minCount) {
                minCount = totalCount;
                leastUsedKey = entry.getKey();
            }
        }

        // Remove by key (more reliable than identity comparison)
        if (leastUsedKey != null) {
            children.remove(leastUsedKey);
        }
    }

    // Getters
    public String getClassName() { return className; }
    public String getMethodName() { return methodName; }
    public byte getRefCountBucket() { return refCountBucket; }
    public long getTraversalCount() { return traversalCount.get(); }
    public long getCleanCount() { return cleanCount.get(); }
    public long getLeakCount() { return leakCount.get(); }
    public long getTotalCount() { return cleanCount.get() + leakCount.get(); }
    public Map<NodeKey, ImprintNode> getChildren() {
        return Collections.unmodifiableMap(children);
    }
    public boolean isLeaf() { return children.isEmpty(); }

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
     * Node key for child lookup.
     * Uses interned strings for memory efficiency and identity-based hashing.
     */
    public static class NodeKey {
        public final String className;
        public final String methodName;
        public final byte refCountBucket;
        private final int hashCode;

        public NodeKey(String className, String methodName, byte refCountBucket) {
            this.className = className;
            this.methodName = methodName;
            this.refCountBucket = refCountBucket;
            // Use identity hashcodes for consistency with identity-based equals().
            // Since strings are interned and equals() uses ==, identity hashcode is
            // more semantically correct. Performance is equivalent to String.hashCode()
            // (which is cached), so this is chosen for correctness, not performance.
            this.hashCode = computeHashCode();
        }

        private int computeHashCode() {
            int result = System.identityHashCode(className);
            result = 31 * result + System.identityHashCode(methodName);
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
                   className == that.className &&
                   methodName == that.methodName;
        }

        @Override
        public int hashCode() {
            return hashCode;
        }
    }
}
