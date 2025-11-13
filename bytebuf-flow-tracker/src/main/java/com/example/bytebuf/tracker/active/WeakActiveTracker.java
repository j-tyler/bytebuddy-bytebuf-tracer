/*
 * Copyright 2025 Justin Marsh
 * SPDX-License-Identifier: Apache-2.0
 */

package com.example.bytebuf.tracker.active;

import com.example.bytebuf.tracker.metrics.MetricEventSink;
import com.example.bytebuf.tracker.trie.BoundedImprintTrie;
import com.example.bytebuf.tracker.trie.ImprintNode;
import java.lang.ref.Reference;
import java.lang.ref.ReferenceQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Tracks active ByteBufs using weak references and a reference queue.
 * Automatically detects leaks when ByteBufs are GC'd without being released.
 *
 * <p><b>Thread Safety:</b> This class is thread-safe. All public methods can be
 * called concurrently from multiple threads. Internal state is protected using
 * {@link java.util.concurrent.ConcurrentHashMap} and atomic operations.
 *
 * <p>GC queue processing happens incrementally during normal operations to avoid
 * blocking. The batch size is tuned to balance throughput and latency.
 *
 * @see WeakActiveFlow
 * @see BoundedImprintTrie
 */
public class WeakActiveTracker {

    private final ConcurrentHashMap<Integer, WeakActiveFlow> activeFlows = new ConcurrentHashMap<>();
    private final ReferenceQueue<Object> gcQueue = new ReferenceQueue<>();
    private final BoundedImprintTrie trie;

    // Lazy GC processing - only every Nth call to reduce hot path overhead
    // Uses mutable CallCounter to avoid Integer boxing/unboxing overhead
    // Sentinel value -1 indicates first call on thread (process immediately)
    private static class CallCounter {
        int value = -1;  // -1 = first call, 0+ = regular counter
    }
    private static final ThreadLocal<CallCounter> CALL_COUNTER = ThreadLocal.withInitial(CallCounter::new);
    private static final int GC_PROCESS_INTERVAL = 100;

    // Statistics
    private final AtomicLong totalObjectsSeen = new AtomicLong(0);
    private final AtomicLong totalCleaned = new AtomicLong(0);
    private final AtomicLong totalLeaked = new AtomicLong(0);
    private final AtomicLong totalGCDetected = new AtomicLong(0);

    public WeakActiveTracker(BoundedImprintTrie trie) {
        this.trie = trie;
    }

    /**
     * Get or create an active flow for a tracked object.
     * Lazily processes GC'd objects from the queue every 100 calls <b>per thread</b>.
     *
     * <p>Each thread maintains an independent call counter via ThreadLocal. GC queue
     * processing happens:
     * <ul>
     *   <li><b>First call on a new thread:</b> Processed immediately for thread lifecycle safety</li>
     *   <li><b>Subsequent calls:</b> Every 100 calls for performance</li>
     * </ul>
     *
     * <p>This provides optimal behavior for both scenarios:
     * <ul>
     *   <li>Many short-lived threads: Each processes GC queue once immediately</li>
     *   <li>Few long-running threads: Process every 100 calls for low overhead</li>
     * </ul>
     *
     * <p><b>Thread Safety:</b> This method is thread-safe and can be called concurrently.
     * If multiple threads call this for the same object simultaneously, only one will
     * create the flow - the others will receive the already-created instance.
     *
     * <p><b>Side Effects:</b> May process up to 100 items from the GC queue every 100 calls,
     * marking them as leaks. Renderers should call {@link #ensureGCProcessed()} before
     * rendering to get current state.
     *
     * @param byteBuf the tracked object (must not be null)
     * @param className the class name for the root node (if creating)
     * @param methodName the method name for the root node (if creating)
     * @param methodSignature the pre-computed method signature (className.methodName)
     * @return the active flow for this object (never null)
     */
    public WeakActiveFlow getOrCreate(Object byteBuf, String className, String methodName,
                                      String methodSignature) {
        int objectId = System.identityHashCode(byteBuf);

        // GC processing strategy:
        // - First call on thread (value == -1): Process immediately, then start counter at 0
        // - Subsequent calls: Every 100 calls
        // This ensures short-lived threads process at least once while keeping overhead low
        CallCounter counter = CALL_COUNTER.get();
        if (counter.value == -1) {
            // First call on this thread - process immediately for thread lifecycle safety
            processGCQueue();
            counter.value = 0;
        } else if (++counter.value >= GC_PROCESS_INTERVAL) {
            // Every 100 calls thereafter
            processGCQueue();
            counter.value = 0;
        }

        WeakActiveFlow flow = activeFlows.get(objectId);
        if (flow == null) {
            // First time seeing this object - create root in Trie and acquire pooled state
            ImprintNode root = trie.getOrCreateRoot(className, methodName, methodSignature);
            boolean isDirect = isDirectBufferMethod(methodSignature);
            FlowStatePool.PooledFlowState pooledState = FlowStatePool.acquire(root);  // Acquire from pool
            flow = new WeakActiveFlow(byteBuf, objectId, pooledState, methodSignature, isDirect, gcQueue);

            WeakActiveFlow existing = activeFlows.putIfAbsent(objectId, flow);
            if (existing == null) {
                totalObjectsSeen.incrementAndGet();
                return flow;
            }
            // Lost race - return pooled state to pool and use existing flow
            FlowStatePool.release(pooledState);
            return existing;
        }

        return flow;
    }

    /**
     * Record that an object was cleanly released (metric reached 0, e.g. refCnt=0).
     * Marks the flow as completed but keeps it in activeFlows to prevent re-tracking.
     *
     * <p><b>Why keep in activeFlows?</b> If we remove immediately and the object's
     * {@code release()} method is itself tracked, it would create a new root node.
     * By keeping it marked as completed, we prevent this while allowing GC to clean
     * it up naturally.
     *
     * <p><b>Thread Safety:</b> This method is thread-safe and can be called concurrently.
     *
     * @param objectId the identity hash code of the released object
     */
    public void recordCleanRelease(int objectId) {
        WeakActiveFlow flow = activeFlows.get(objectId);
        if (flow != null && !flow.isCompleted() && flow.getCurrentNode() != null) {
            flow.getCurrentNode().recordOutcome(true);  // CLEAN
            flow.markCompleted();
            totalCleaned.incrementAndGet();

            // Keep in activeFlows but marked as completed
            // This prevents release() from creating a new root
            // GC will eventually remove it via processGCQueue()
        }
    }

    /**
     * Process the GC queue to detect leaked objects.
     * Called periodically during normal tracking operations.
     *
     * <p>Batch size of 100 is chosen to balance competing concerns:
     * <ul>
     *   <li>Smaller batches: More frequent ReferenceQueue.poll() calls, higher overhead</li>
     *   <li>Larger batches: Longer blocking periods on the tracking hot path</li>
     *   <li>Very small batches (e.g., 10): Require proportionally more calls for same throughput</li>
     * </ul>
     * The value 100 provides reasonable balance and can be tuned based on actual workload
     * characteristics. Typical processing time per batch is expected to be in the low
     * microsecond range for empty queues, scaling linearly with GC'd object count.
     */
    private void processGCQueue() {
        Reference<?> ref;
        int processed = 0;

        // Process up to 100 GC'd objects per call (prevent blocking)
        while (processed < 100 && (ref = gcQueue.poll()) != null) {
            WeakActiveFlow flow = (WeakActiveFlow) ref;
            activeFlows.remove(flow.getObjectId());

            // ByteBuf was GC'd without release() - it's a LEAK!
            // Skip completed flows (already marked as clean)
            if (!flow.isCompleted() && flow.getCurrentNode() != null) {
                flow.getCurrentNode().recordOutcome(false);
                totalLeaked.incrementAndGet();
                totalGCDetected.incrementAndGet();

                // Record for delta-based metrics (only if handlers exist)
                MetricEventSink sink = MetricEventSink.getInstance();
                if (sink.isRecording()) {
                    sink.recordLeak(
                        flow.getCurrentNode(),
                        flow.getRootMethod(),
                        flow.isDirect(),
                        System.currentTimeMillis()
                    );
                }
            }

            // Return PooledFlowState to pool for reuse
            FlowStatePool.release(flow.getPooledState());

            processed++;
        }
    }

    private static boolean isDirectBufferMethod(String rootMethod) {
        if (rootMethod == null) {
            return false;
        }
        return rootMethod.endsWith(".directBuffer") ||
               rootMethod.endsWith(".ioBuffer") ||
               rootMethod.contains(".directBuffer(") ||
               rootMethod.contains(".ioBuffer(");
    }

    /**
     * Ensure GC queue is processed to get current state.
     * Renderers should call this before rendering to ensure they see all leaked objects.
     */
    public void ensureGCProcessed() {
        processGCQueue();
    }

    /**
     * Force process all remaining GC'd objects (for shutdown).
     */
    public void processAllGCQueue() {
        Reference<?> ref;
        while ((ref = gcQueue.poll()) != null) {
            WeakActiveFlow flow = (WeakActiveFlow) ref;
            activeFlows.remove(flow.getObjectId());

            // Skip completed flows (already marked as clean)
            if (!flow.isCompleted() && flow.getCurrentNode() != null) {
                flow.getCurrentNode().recordOutcome(false);
                totalLeaked.incrementAndGet();
                totalGCDetected.incrementAndGet();

                // Record for delta-based metrics (only if handlers exist)
                MetricEventSink sink = MetricEventSink.getInstance();
                if (sink.isRecording()) {
                    sink.recordLeak(
                        flow.getCurrentNode(),
                        flow.getRootMethod(),
                        flow.isDirect(),
                        System.currentTimeMillis()
                    );
                }
            }

            // Return PooledFlowState to pool for reuse
            FlowStatePool.release(flow.getPooledState());
        }
    }

    /**
     * Mark all remaining active flows as leaks (for shutdown).
     */
    public void markRemainingAsLeaks() {
        MetricEventSink sink = MetricEventSink.getInstance();
        boolean recording = sink.isRecording();

        for (WeakActiveFlow flow : activeFlows.values()) {
            // Skip completed flows (already marked as clean)
            if (!flow.isCompleted() && flow.getCurrentNode() != null) {
                flow.getCurrentNode().recordOutcome(false);
                totalLeaked.incrementAndGet();

                // Record for delta-based metrics (only if handlers exist)
                if (recording) {
                    sink.recordLeak(
                        flow.getCurrentNode(),
                        flow.getRootMethod(),
                        flow.isDirect(),
                        System.currentTimeMillis()
                    );
                }
            }
            // Return PooledFlowState to pool for reuse
            FlowStatePool.release(flow.getPooledState());
        }
        activeFlows.clear();
    }

    // Statistics
    public int getActiveCount() {
        return activeFlows.size();
    }

    public long getTotalObjectsSeen() {
        return totalObjectsSeen.get();
    }

    public long getTotalCleaned() {
        return totalCleaned.get();
    }

    public long getTotalLeaked() {
        return totalLeaked.get();
    }

    public long getTotalGCDetected() {
        return totalGCDetected.get();
    }
}
