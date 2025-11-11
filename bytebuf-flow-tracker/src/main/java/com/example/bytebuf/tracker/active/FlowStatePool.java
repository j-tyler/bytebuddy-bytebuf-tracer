/*
 * Copyright 2025 Justin Marsh
 * SPDX-License-Identifier: Apache-2.0
 */

package com.example.bytebuf.tracker.active;

import com.example.bytebuf.tracker.trie.ImprintNode;
import stormpot.*;

import java.util.concurrent.TimeUnit;

/**
 * Stormpot-based pool for {@link FlowState} objects to reduce allocation overhead.
 *
 * <p><b>Design Rationale:</b>
 * <ul>
 *   <li><b>Stormpot pooling</b>: Battle-tested, high-performance object pooling library</li>
 *   <li><b>Thread-safe</b>: Stormpot handles all thread-safety concerns internally</li>
 *   <li><b>Bounded pool size</b>: Configured size limits memory overhead</li>
 *   <li><b>Zero custom synchronization</b>: All locking handled by stormpot</li>
 *   <li><b>Lock-free fast path</b>: Stormpot uses lock-free algorithms for claim/release</li>
 * </ul>
 *
 * <p><b>Memory Tradeoff:</b> Pool can hold up to configured size of FlowState objects.
 * FlowState is ~32 bytes, so default pool size of 1024 = ~32KB overhead.
 * For applications with high ByteBuf churn, this provides significant allocation savings.
 *
 * <p><b>Lifecycle:</b> The pool is created once at tracker initialization and
 * lives for the lifetime of the JVM. Stormpot handles cleanup internally.
 *
 * @see FlowState
 * @see WeakActiveFlow
 */
public class FlowStatePool {

    /**
     * The stormpot pool instance (shared globally).
     * Uses Pool.from() API introduced in Stormpot 3.0.
     */
    private static final Pool<StormpotPooledFlowState> POOL;

    static {
        // Configure pool with reasonable defaults using Stormpot 3.x API
        POOL = Pool.from(new FlowStateAllocator())
            .setSize(1024)  // Max 1024 pooled objects (~32KB overhead)
            .setExpiration(Expiration.never())  // Never expire - reuse indefinitely
            .build();
    }

    /**
     * Acquire a PooledFlowState from the pool, initialized with the given root node.
     *
     * <p><b>Performance:</b> Lock-free claim operation in the common case.
     * Falls back to allocation if pool is empty.
     *
     * @param rootNode the root node for the flow
     * @return a PooledFlowState instance (never null), either from pool or newly allocated
     */
    public static PooledFlowState acquire(ImprintNode rootNode) {
        try {
            // Claim with 1ms timeout - if pool exhausted, fail fast and allocate
            Timeout timeout = new Timeout(1, TimeUnit.MILLISECONDS);
            StormpotPooledFlowState pooled = POOL.claim(timeout);

            if (pooled != null) {
                // Got a pooled instance - reset and return
                pooled.flowState.reset(rootNode);
                pooled.rootNode = rootNode; // Store for debugging
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
        FlowState state = new FlowState();
        state.reset(rootNode);
        return new UnpooledFlowState(state, rootNode);
    }

    /**
     * Return a PooledFlowState to the pool for reuse.
     *
     * <p><b>Performance:</b> Lock-free release operation.
     *
     * @param pooled the PooledFlowState to return to the pool (must not be null)
     */
    public static void release(PooledFlowState pooled) {
        if (pooled == null) {
            return;
        }

        // Clear node reference to allow GC of trie nodes
        pooled.getFlowState().setCurrentNode(null);

        // Return to pool (no-op for unpooled instances)
        pooled.release();
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
     * Stormpot Allocator for FlowState objects.
     */
    private static class FlowStateAllocator implements Allocator<StormpotPooledFlowState> {
        @Override
        public StormpotPooledFlowState allocate(Slot slot) {
            return new StormpotPooledFlowState(slot);
        }

        @Override
        public void deallocate(StormpotPooledFlowState pooled) {
            // Clear references to allow GC
            pooled.flowState.setCurrentNode(null);
            pooled.rootNode = null;
        }
    }

    /**
     * Interface for pooled FlowState objects (both stormpot-pooled and unpooled).
     */
    public interface PooledFlowState {
        FlowState getFlowState();
        void release();

        // Expose FlowState methods for convenience
        default ImprintNode getCurrentNode() { return getFlowState().getCurrentNode(); }
        default void setCurrentNode(ImprintNode node) { getFlowState().setCurrentNode(node); }
        default int getCurrentDepth() { return getFlowState().getCurrentDepth(); }
        default void incrementDepth() { getFlowState().incrementDepth(); }
        default boolean isCompleted() { return getFlowState().isCompleted(); }
        default void markCompleted() { getFlowState().markCompleted(); }
    }

    /**
     * Wrapper for FlowState that implements Stormpot's Poolable interface.
     * This is the object that actually lives in the pool.
     */
    private static class StormpotPooledFlowState implements PooledFlowState, Poolable {
        private final Slot slot;
        final FlowState flowState;
        ImprintNode rootNode; // Stored for debugging

        StormpotPooledFlowState(Slot slot) {
            this.slot = slot;
            this.flowState = new FlowState();
        }

        @Override
        public FlowState getFlowState() {
            return flowState;
        }

        @Override
        public void release() {
            // Return this pooled object back to stormpot
            slot.release(this);
        }
    }

    /**
     * Non-pooled FlowState wrapper for when pool is exhausted.
     * release() is a no-op - object will be GC'd normally.
     */
    private static class UnpooledFlowState implements PooledFlowState {
        private final FlowState flowState;
        private ImprintNode rootNode; // Stored for debugging

        UnpooledFlowState(FlowState flowState, ImprintNode rootNode) {
            this.flowState = flowState;
            this.rootNode = rootNode;
        }

        @Override
        public FlowState getFlowState() {
            return flowState;
        }

        @Override
        public void release() {
            // No-op - unpooled instance will be GC'd
        }
    }
}
