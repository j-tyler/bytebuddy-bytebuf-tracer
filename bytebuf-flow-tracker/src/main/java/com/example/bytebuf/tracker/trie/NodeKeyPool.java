/*
 * Copyright 2025 Justin Marsh
 * SPDX-License-Identifier: Apache-2.0
 */

package com.example.bytebuf.tracker.trie;

import stormpot.*;

import java.util.concurrent.TimeUnit;

/**
 * Stormpot-based pool for {@link ImprintNode.NodeKey} objects to reduce allocation overhead.
 *
 * <p><b>Design Rationale:</b>
 * NodeKey objects are allocated on every trie traversal for lookup but immediately discarded.
 * This pool allows reuse of NodeKey instances:
 * <ul>
 *   <li><b>Lookup path (common)</b>: Claim from pool, use for lookup, release back to pool</li>
 *   <li><b>Creation path (rare)</b>: Allocate fresh NodeKey to store in ConcurrentHashMap</li>
 * </ul>
 *
 * <p><b>Memory Tradeoff:</b> Pool can hold up to configured size of NodeKey objects.
 * NodeKey is ~16 bytes, so default pool size of 512 = ~8KB overhead per pool.
 * For applications with high trie traversal rates, this provides significant allocation savings.
 *
 * <p><b>Pool Sizing:</b> Sized based on expected concurrency, not total nodes:
 * <ul>
 *   <li>Pool size = concurrent threads × expected traversal depth</li>
 *   <li>Example: 10 threads × 5 depth = 50 slots minimum</li>
 *   <li>Default 512 provides headroom for 50+ threads or deep traversals</li>
 * </ul>
 *
 * <p><b>Lifecycle:</b> The pool is created once at class initialization and
 * lives for the lifetime of the JVM. Stormpot handles cleanup internally.
 *
 * @see ImprintNode.NodeKey
 * @see ImprintNode#getOrCreateChild
 */
public class NodeKeyPool {

    /**
     * The stormpot pool instance (shared globally).
     * Uses Pool.from() API introduced in Stormpot 3.0.
     */
    private static final Pool<StormpotPooledNodeKey> POOL;

    static {
        // Configure pool with reasonable defaults using Stormpot 3.x API
        // Pool size is based on expected concurrent traversals, not total nodes
        POOL = Pool.from(new NodeKeyAllocator())
            .setSize(512)  // Max 512 pooled objects (~8KB overhead)
            .setExpiration(Expiration.never())  // Never expire - reuse indefinitely
            .build();
    }

    /**
     * Acquire a PooledNodeKey from the pool for lookup.
     *
     * <p><b>Usage Pattern:</b>
     * <pre>{@code
     * PooledNodeKey lookup = NodeKeyPool.acquire(methodSignature, bucket);
     * try {
     *     ImprintNode existing = map.get(lookup.getNodeKey());
     *     // ... use existing ...
     * } finally {
     *     lookup.release();
     * }
     * }</pre>
     *
     * <p><b>Performance:</b> Lock-free claim operation in the common case.
     * Falls back to allocation if pool is empty (graceful degradation).
     *
     * @param methodSignature the interned method signature
     * @param refCountBucket the reference count bucket
     * @return a PooledNodeKey instance (never null), either from pool or newly allocated
     */
    public static PooledNodeKey acquire(String methodSignature, byte refCountBucket) {
        try {
            // Claim with 1ms timeout - if pool exhausted, fail fast and allocate
            Timeout timeout = new Timeout(1, TimeUnit.MILLISECONDS);
            StormpotPooledNodeKey pooled = POOL.claim(timeout);

            if (pooled != null) {
                // Got a pooled instance - reset and return
                pooled.getNodeKey().reset(methodSignature, refCountBucket);
                return pooled;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (PoolException e) {
            // Pool might be shutting down or other pool-specific errors
            // Fall through to unpooled allocation
        }

        // Pool exhausted or interrupted - allocate unpooled instance
        // This is fine - just means we're under heavy load
        ImprintNode.NodeKey key = new ImprintNode.NodeKey(methodSignature, refCountBucket);
        return new UnpooledNodeKey(key);
    }

    /**
     * Allocate a fresh NodeKey for storage in ConcurrentHashMap.
     * This NodeKey will NOT be returned to the pool - it's stored permanently in the map.
     *
     * <p><b>Usage:</b> Only called when creating a new child node (rare path).
     *
     * @param methodSignature the interned method signature
     * @param refCountBucket the reference count bucket
     * @return a new NodeKey instance for permanent storage
     */
    public static ImprintNode.NodeKey allocateForStorage(String methodSignature, byte refCountBucket) {
        return new ImprintNode.NodeKey(methodSignature, refCountBucket);
    }

    /**
     * Shutdown the pool (for testing or graceful shutdown).
     * After calling this, acquire() will allocate new instances.
     */
    public static void shutdown() {
        try {
            POOL.shutdown().await(new Timeout(5, TimeUnit.SECONDS));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Stormpot Allocator for NodeKey objects.
     */
    private static class NodeKeyAllocator implements Allocator<StormpotPooledNodeKey> {
        @Override
        public StormpotPooledNodeKey allocate(Slot slot) {
            return new StormpotPooledNodeKey(slot);
        }

        @Override
        public void deallocate(StormpotPooledNodeKey pooled) {
            // Clear reference to allow GC
            pooled.getNodeKey().reset(null, (byte) 0);
        }
    }

    /**
     * Interface for pooled NodeKey objects (both stormpot-pooled and unpooled).
     */
    public interface PooledNodeKey {
        ImprintNode.NodeKey getNodeKey();
        void release();
    }

    /**
     * Wrapper for NodeKey that implements Stormpot's Poolable interface.
     * This is the object that actually lives in the pool.
     */
    private static class StormpotPooledNodeKey implements PooledNodeKey, Poolable {
        private final Slot slot;
        final ImprintNode.NodeKey nodeKey;

        StormpotPooledNodeKey(Slot slot) {
            this.slot = slot;
            // Initialize with dummy values - will be reset on acquire
            this.nodeKey = new ImprintNode.NodeKey("", (byte) 0);
        }

        @Override
        public ImprintNode.NodeKey getNodeKey() {
            return nodeKey;
        }

        @Override
        public void release() {
            // Return this pooled object back to stormpot
            slot.release(this);
        }
    }

    /**
     * Non-pooled NodeKey wrapper for when pool is exhausted.
     * release() is a no-op - object will be GC'd normally.
     */
    private static class UnpooledNodeKey implements PooledNodeKey {
        private final ImprintNode.NodeKey nodeKey;

        UnpooledNodeKey(ImprintNode.NodeKey nodeKey) {
            this.nodeKey = nodeKey;
        }

        @Override
        public ImprintNode.NodeKey getNodeKey() {
            return nodeKey;
        }

        @Override
        public void release() {
            // No-op - unpooled instance will be GC'd
        }
    }
}
