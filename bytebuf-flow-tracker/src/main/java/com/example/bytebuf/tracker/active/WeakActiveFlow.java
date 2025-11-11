/*
 * Copyright 2025 Justin Marsh
 * SPDX-License-Identifier: Apache-2.0
 */

package com.example.bytebuf.tracker.active;

import com.example.bytebuf.tracker.trie.ImprintNode;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;

/**
 * Tracks a single ByteBuf through its lifecycle using a weak reference.
 * When the ByteBuf is GC'd, this reference is enqueued for leak detection.
 *
 * <p><b>Object Pooling:</b> This class delegates to a stormpot-pooled {@link FlowStatePool.PooledFlowState}
 * object to reduce allocation overhead. The WeakReference itself cannot be pooled
 * (referent is final), but the mutable state can be reused across different ByteBufs.
 *
 * <p><b>Memory Layout:</b>
 * <ul>
 *   <li>WeakActiveFlow: 16 (object header) + 8 (WeakReference) + 4 (objectId) + 8 (pooledState) = ~36 bytes</li>
 *   <li>PooledFlowState: 16 (object header) + 8 (FlowState ref) + 8 (Slot ref) = ~32 bytes</li>
 *   <li>FlowState: 16 (object header) + 8 (currentNode) + 1 (flags) + 3 (padding) = ~28 bytes</li>
 *   <li>Total: ~96 bytes when active, ~36 bytes after PooledFlowState returned to pool</li>
 * </ul>
 *
 * <p><b>Thread Safety:</b> This class is thread-safe. All mutable state is stored
 * in the {@link FlowState} object, which uses volatile fields for visibility.
 *
 * @see WeakActiveTracker
 * @see FlowState
 * @see FlowStatePool
 */
public class WeakActiveFlow extends WeakReference<Object> {

    // Object identity (for map removal after GC)
    private final int objectId;

    // Pooled state object (never null while in activeFlows map)
    private final FlowStatePool.PooledFlowState pooledState;

    public WeakActiveFlow(Object byteBuf, int objectId, FlowStatePool.PooledFlowState pooledState,
                          ReferenceQueue<Object> gcQueue) {
        super(byteBuf, gcQueue);
        this.objectId = objectId;
        this.pooledState = pooledState;
    }

    public int getObjectId() {
        return objectId;
    }

    // Delegate all state operations to the pooled FlowState

    public ImprintNode getCurrentNode() {
        return pooledState.getCurrentNode();
    }

    public void setCurrentNode(ImprintNode node) {
        pooledState.setCurrentNode(node);
    }

    public int getCurrentDepth() {
        return pooledState.getCurrentDepth();
    }

    public void incrementDepth() {
        pooledState.incrementDepth();
    }

    public boolean isCompleted() {
        return pooledState.isCompleted();
    }

    public void markCompleted() {
        pooledState.markCompleted();
    }

    /**
     * Get the pooled state object for pool release (package-private).
     * Called by WeakActiveTracker when returning to pool.
     */
    FlowStatePool.PooledFlowState getPooledState() {
        return pooledState;
    }
}
