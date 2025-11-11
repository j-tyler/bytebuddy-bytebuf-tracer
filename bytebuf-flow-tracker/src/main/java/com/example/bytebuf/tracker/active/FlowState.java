/*
 * Copyright 2025 Justin Marsh
 * SPDX-License-Identifier: Apache-2.0
 */

package com.example.bytebuf.tracker.active;

import com.example.bytebuf.tracker.trie.ImprintNode;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Poolable flow state object that tracks a ByteBuf's position in the trie.
 * Separated from WeakActiveFlow to enable object pooling while maintaining
 * WeakReference semantics.
 *
 * <p><b>Memory Optimization:</b> Uses bit-packing to reduce memory footprint:
 * <ul>
 *   <li>Bit 7 of {@code depthAndFlags}: completed flag</li>
 *   <li>Bits 0-6 of {@code depthAndFlags}: depth value (0-127, max depth is 100)</li>
 * </ul>
 *
 * <p><b>Object Pooling:</b> This class is designed to be pooled and reused across
 * different ByteBuf lifecycles. The {@link #reset(ImprintNode)} method prepares
 * the object for reuse.
 *
 * <p><b>Thread Safety:</b> Uses AtomicInteger for lock-free thread-safe operations.
 * Lock-free CAS operations are preferred since FlowState instances are pooled.
 *
 * @see WeakActiveFlow
 * @see FlowStatePool
 */
public class FlowState {

    // Bit masks for packed int field
    private static final int DEPTH_MASK = 0x7F;      // Bits 0-6: depth value (0-127)
    private static final int COMPLETED_FLAG = 0x80;  // Bit 7: completed flag

    // Current position in Trie (volatile for visibility across threads)
    private volatile ImprintNode currentNode;

    // Packed int: [bit 7: completed flag | bits 0-6: depth (0-127)]
    // AtomicInteger for lock-free thread-safe operations
    private final AtomicInteger depthAndFlags;

    /**
     * Package-private constructor - instances should be obtained via {@link FlowStatePool}.
     */
    FlowState() {
        this.depthAndFlags = new AtomicInteger(0);
    }

    /**
     * Reset this state for reuse with a new ByteBuf.
     * Called by the pool when acquiring an instance.
     *
     * @param rootNode the root node for the new flow
     */
    void reset(ImprintNode rootNode) {
        this.currentNode = rootNode;
        this.depthAndFlags.set(0);  // depth=0, completed=false
    }

    public ImprintNode getCurrentNode() {
        return currentNode;
    }

    public void setCurrentNode(ImprintNode node) {
        this.currentNode = node;
    }

    /**
     * Get the current depth from the packed int.
     * Extracts bits 0-6 (depth value 0-127).
     */
    public int getCurrentDepth() {
        return depthAndFlags.get() & DEPTH_MASK;
    }

    /**
     * Increment the depth counter while preserving the completed flag.
     * Note: Depth is limited to 127 (7 bits), but max configured depth is typically 100.
     *
     * <p><b>Concurrency:</b> Uses lock-free CAS loop for thread-safe read-modify-write.
     */
    public void incrementDepth() {
        depthAndFlags.updateAndGet(current -> {
            // Extract depth and completed flag
            int depth = current & DEPTH_MASK;
            boolean wasCompleted = (current & COMPLETED_FLAG) != 0;

            // Increment depth (with overflow protection)
            depth = Math.min(depth + 1, 127);

            // Repack: preserve completed flag, update depth
            return (wasCompleted ? COMPLETED_FLAG : 0) | depth;
        });
    }

    /**
     * Check if this flow has been completed (refCnt reached 0).
     * Checks bit 7 of the packed int.
     */
    public boolean isCompleted() {
        return (depthAndFlags.get() & COMPLETED_FLAG) != 0;
    }

    /**
     * Mark this flow as completed while preserving the depth value.
     * Sets bit 7 of the packed int.
     *
     * <p><b>Concurrency:</b> Uses lock-free CAS loop for thread-safe read-modify-write.
     */
    public void markCompleted() {
        depthAndFlags.updateAndGet(current -> current | COMPLETED_FLAG);
    }
}
