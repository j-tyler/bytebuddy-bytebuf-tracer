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
 * <p><b>Thread Safety:</b> This class is thread-safe. The {@code currentNode}
 * and {@code completed} fields are volatile to ensure visibility across threads.
 * The {@code currentDepth} field is not volatile as reading a stale value has
 * negligible impact (going 1-2 levels deeper is benign for flow fidelity).
 * However, updates to these fields are not atomic as a group - callers must
 * ensure proper synchronization if atomicity is required.
 *
 * @see WeakActiveTracker
 */
public class WeakActiveFlow extends WeakReference<Object> {

    // Object identity (for map removal after GC)
    private final int objectId;

    // Current position in Trie (volatile for visibility across threads)
    private volatile ImprintNode currentNode;

    // Depth tracking (not volatile - stale reads are benign)
    private int currentDepth;

    // Track if this flow is completed (refCnt=0) to prevent re-tracking
    private volatile boolean completed;

    public WeakActiveFlow(Object byteBuf, int objectId, ImprintNode rootNode,
                          ReferenceQueue<Object> gcQueue) {
        super(byteBuf, gcQueue);
        this.objectId = objectId;
        this.currentNode = rootNode;
        this.currentDepth = 0;
        this.completed = false;
    }

    public int getObjectId() {
        return objectId;
    }

    public ImprintNode getCurrentNode() {
        return currentNode;
    }

    public void setCurrentNode(ImprintNode node) {
        this.currentNode = node;
    }

    public int getCurrentDepth() {
        return currentDepth;
    }

    public void incrementDepth() {
        this.currentDepth++;
    }

    public boolean isCompleted() {
        return completed;
    }

    public void markCompleted() {
        this.completed = true;
    }
}
