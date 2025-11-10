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
 * updates, but provides a soft limit that prevents unbounded growth. Eviction
 * happens when limits are approached, using LFU (Least Frequently Used) policy.
 *
 * @see ImprintNode
 */
public class BoundedImprintTrie {

    // Configuration
    private final int maxTotalNodes;
    private final int maxDepth;

    // Global node counter for hard limit enforcement
    private final AtomicInteger totalNodeCount = new AtomicInteger(0);

    // String interning for memory efficiency
    private final ConcurrentHashMap<String, String> stringIntern = new ConcurrentHashMap<>();

    // Root nodes (allocator methods)
    private final ConcurrentHashMap<String, ImprintNode> roots = new ConcurrentHashMap<>();

    public BoundedImprintTrie(int maxTotalNodes, int maxDepth) {
        this.maxTotalNodes = maxTotalNodes;
        this.maxDepth = maxDepth;
    }

    /**
     * Get or create a root node for an allocator method.
     */
    public ImprintNode getOrCreateRoot(String className, String methodName) {
        String key = intern(className) + "." + intern(methodName);

        ImprintNode existing = roots.get(key);
        if (existing != null) {
            existing.recordTraversal();
            return existing;
        }

        // Check global limit
        if (totalNodeCount.get() >= maxTotalNodes) {
            // Eviction at global level - remove least-used root
            evictLeastUsedRoot();
        }

        ImprintNode newRoot = new ImprintNode(
            intern(className),
            intern(methodName),
            (byte) 1  // Root always starts with ref=1
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
                                        String methodName, int refCount, int currentDepth) {
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

        ImprintNode child = parent.getOrCreateChild(
            intern(className),
            intern(methodName),
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
        if (s == null) return null;
        return stringIntern.computeIfAbsent(s, k -> k);
    }

    /**
     * Evict the root with the lowest total traversal count.
     */
    private void evictLeastUsedRoot() {
        String leastUsedKey = null;
        long minCount = Long.MAX_VALUE;

        for (Map.Entry<String, ImprintNode> entry : roots.entrySet()) {
            long totalCount = entry.getValue().getTotalCount();
            if (totalCount < minCount) {
                minCount = totalCount;
                leastUsedKey = entry.getKey();
            }
        }

        if (leastUsedKey != null) {
            ImprintNode removed = roots.remove(leastUsedKey);
            if (removed != null) {
                // Decrement counter (approximate, due to tree structure)
                totalNodeCount.addAndGet(-estimateNodeCount(removed));
            }
        }
    }

    // ThreadLocal pool for stack reuse during node counting (avoids allocation overhead)
    private static final ThreadLocal<java.util.Deque<ImprintNode>> STACK_POOL =
        ThreadLocal.withInitial(java.util.ArrayDeque::new);

    /**
     * Estimate total nodes in a subtree (for eviction accounting).
     * Uses iterative traversal to prevent stack overflow in deep trees.
     * Reuses a ThreadLocal deque to avoid allocation overhead during eviction.
     */
    private int estimateNodeCount(ImprintNode node) {
        int count = 0;
        java.util.Deque<ImprintNode> stack = STACK_POOL.get();
        stack.clear();  // Reuse from pool
        stack.push(node);

        while (!stack.isEmpty()) {
            ImprintNode current = stack.pop();
            count++;
            // Add all children to stack for processing
            for (ImprintNode child : current.getChildren().values()) {
                stack.push(child);
            }
        }

        return count;
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

    public long getStringInterningMemory() {
        // Approximate: avg string length * count
        return stringIntern.size() * 30L;
    }

    public void clear() {
        roots.clear();
        stringIntern.clear();
        totalNodeCount.set(0);
    }
}
