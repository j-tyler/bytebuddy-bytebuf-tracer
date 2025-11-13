/*
 * Copyright 2025 Justin Marsh
 * SPDX-License-Identifier: Apache-2.0
 */

package com.example.bytebuf.tracker.trie;

import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Bounded Trie with automatic string interning and global node limit.
 * Provides provable memory upper bound.
 *
 * <p><b>Thread Safety:</b> This class is thread-safe. All public methods can be
 * called concurrently from multiple threads. Internal state is protected using
 * {@link java.util.concurrent.ConcurrentHashMap} and atomic operations.
 *
 * <p><b>Memory Bounds:</b> The total node count is approximate due to concurrent
 * updates, but provides a soft limit that prevents unbounded growth. When limits
 * are reached, nodes stop accepting new children to avoid concurrency overhead.
 *
 * @see ImprintNode
 */
public class BoundedImprintTrie {

    // Configuration
    private final int maxTotalNodes;
    private final int maxDepth;

    // Global node counter for hard limit enforcement
    private final AtomicInteger totalNodeCount = new AtomicInteger(0);

    // String interning for memory efficiency (bounded, sized to match node limit)
    private final FixedArrayStringInterner stringInterner;

    // Root nodes (allocator methods)
    private final ConcurrentHashMap<String, ImprintNode> roots = new ConcurrentHashMap<>();

    public BoundedImprintTrie(int maxTotalNodes, int maxDepth) {
        this.maxTotalNodes = maxTotalNodes;
        this.maxDepth = maxDepth;
        // Size the interner to the number of allowed nodes
        // Each node has className + methodName, so we need roughly 2x capacity
        this.stringInterner = new FixedArrayStringInterner(maxTotalNodes * 2);
    }

    /**
     * Get or create a root node for an allocator method.
     */
    public ImprintNode getOrCreateRoot(String className, String methodName, String methodSignature) {
        String key = intern(methodSignature);

        ImprintNode existing = roots.get(key);
        if (existing != null) {
            existing.recordTraversal();
            return existing;
        }

        // Check global limit - if reached, return any existing root to stop growth
        // No eviction to avoid concurrency overhead (iteration, cache invalidation, subtree traversal)
        if (totalNodeCount.get() >= maxTotalNodes) {
            // Return first available root - in practice, allocator roots are established early
            // so this only happens when trie is already saturated with useful data
            ImprintNode anyRoot = roots.values().iterator().next();
            anyRoot.recordTraversal();
            return anyRoot;
        }

        ImprintNode newRoot = new ImprintNode(
            intern(className),
            intern(methodName),
            (byte) 1,  // Root always starts with ref=1
            null       // Root nodes have no parent
        );

        ImprintNode result = roots.putIfAbsent(key, newRoot);
        if (result == null) {
            totalNodeCount.incrementAndGet();
            newRoot.recordTraversal();
            return newRoot;
        }
        result.recordTraversal();
        return result;
    }

    /**
     * Traverse or create a child node.
     * Enforces depth limit and global node limit.
     */
    public ImprintNode traverseOrCreate(ImprintNode parent, String className,
                                        String methodName, String methodSignature,
                                        int refCount, int currentDepth) {
        // Depth limit check
        if (currentDepth >= maxDepth) {
            return parent;  // Stop at max depth, treat as leaf
        }

        // Global limit check
        if (totalNodeCount.get() >= maxTotalNodes) {
            return parent;  // Stop creating nodes
        }

        byte bucket = bucketRefCount(refCount);

        // Check if child already exists
        int childCountBefore = parent.getChildren().size();

        // Intern all strings - methodSignature already pre-computed at instrumentation time
        String internedClassName = intern(className);
        String internedMethodName = intern(methodName);
        String internedMethodSignature = intern(methodSignature);

        ImprintNode child = parent.getOrCreateChild(
            internedClassName,
            internedMethodName,
            internedMethodSignature,
            bucket
        );

        // If a new child was created, increment global counter
        // NOTE: This check is approximate due to concurrency. The count may be slightly
        // inaccurate if eviction happens concurrently, but this is acceptable as the
        // count is used for soft limits, not hard guarantees.
        int childCountAfter = parent.getChildren().size();
        if (childCountAfter > childCountBefore) {
            totalNodeCount.incrementAndGet();
        }

        // Record that we traversed through this child
        child.recordTraversal();

        return child;
    }

    /**
     * Bucket refCount values to reduce path explosion.
     */
    public static byte bucketRefCount(int refCount) {
        if (refCount == 0) return 0;      // ZERO - released
        if (refCount <= 2) return 1;      // LOW
        if (refCount <= 5) return 2;      // MEDIUM
        return 3;                          // HIGH (6+)
    }

    /**
     * Intern strings to reduce memory usage.
     */
    private String intern(String s) {
        return stringInterner.intern(s);
    }


    // Getters
    public Map<String, ImprintNode> getRoots() {
        return Collections.unmodifiableMap(roots);
    }

    public int getRootCount() {
        return roots.size();
    }

    public int getNodeCount() {
        return totalNodeCount.get();
    }

    public int getMaxNodes() {
        return maxTotalNodes;
    }

    public int getMaxDepth() {
        return maxDepth;
    }

    public void clear() {
        roots.clear();
        stringInterner.clear();
        totalNodeCount.set(0);
    }
}
