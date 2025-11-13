/*
 * Copyright 2025 Justin Marsh
 * SPDX-License-Identifier: Apache-2.0
 */

package com.example.bytebuf.tracker.agent;

import com.example.bytebuf.tracker.ByteBufFlowTracker;
import com.example.bytebuf.api.tracker.ObjectTrackerHandler;
import com.example.bytebuf.tracker.ObjectTrackerRegistry;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.implementation.bytecode.assign.Assigner;

/**
 * ByteBuddy advice for tracking ByteBuf construction/allocation.
 * This advice intercepts ByteBuf factory methods to make allocation sites
 * the root nodes in the flow trie.
 *
 * Key features:
 * - Makes ByteBuf construction deterministic roots (vs first-touch)
 * - Provides immediate visibility into allocation type (heap, direct, wrapped, etc.)
 * - Improves leak diagnosis by showing allocation method
 * - Supports high-performance filtering of direct-only buffers
 *
 * Instrumented methods include:
 * - Unpooled.buffer/directBuffer/wrappedBuffer/copiedBuffer/compositeBuffer
 * - ByteBufAllocator.buffer/directBuffer/ioBuffer/heapBuffer/compositeBuffer
 */
public class ByteBufConstructionAdvice {

    // Re-entrance guard to prevent infinite recursion
    // Must be public for instrumented classes to access
    public static final ThreadLocal<Boolean> IS_TRACKING =
        ThreadLocal.withInitial(() -> false);

    /**
     * Runtime filtering flag for direct-only tracking.
     *
     * WHY: trackDirectOnly skips heapBuffer instrumentation at compile-time, but ambiguous
     * methods (buffer, wrappedBuffer, compositeBuffer) still get instrumented because we
     * can't know their type until runtime. This flag enables runtime filtering for those
     * ambiguous methods using ByteBuf.isDirect().
     *
     * Set once by ByteBufFlowAgent.premain() before application starts.
     *
     * WHY NOT volatile: The flag is written once during agent initialization (premain())
     * and read by application threads after main() starts. The JMM guarantees a happens-before
     * relationship from premain() completion to main() start, ensuring visibility. Since the
     * value never changes after initialization, volatile is unnecessary overhead.
     */
    public static boolean FILTER_DIRECT_ONLY = false;

    /**
     * Method exit advice - tracks newly constructed ByteBuf objects.
     * This runs after the factory method completes and returns a ByteBuf.
     */
    @Advice.OnMethodExit(onThrowable = Throwable.class)
    public static void onConstructionExit(
            @Advice.Origin Class<?> clazz,
            @Advice.Origin("#m") String methodName,
            @Advice.Return(typing = Assigner.Typing.DYNAMIC) Object returnValue,
            @Advice.Thrown Throwable thrown) {

        // Fast exit guards (minimize overhead on hot path)
        if (IS_TRACKING.get() || thrown != null || returnValue == null) {
            return;
        }

        try {
            IS_TRACKING.set(true);

            // OPTIMIZATION: Fast-path filtering for direct-only mode
            //
            // INSTRUMENTED METHODS (only terminal allocation methods):
            // - ByteBufAllocator.directBuffer(int, int) -> methodName = "directBuffer"
            // - ByteBufAllocator.heapBuffer(int, int) -> methodName = "heapBuffer"
            // - Unpooled.wrappedBuffer(...) -> methodName = "wrappedBuffer"
            // - Unpooled.copiedBuffer(...) -> methodName = "copiedBuffer"
            // - Unpooled.compositeBuffer(...) -> methodName = "compositeBuffer"
            //
            // NOT INSTRUMENTED (delegation methods that call terminal methods):
            // - ByteBufAllocator.buffer() -> delegates to directBuffer/heapBuffer
            // - ByteBufAllocator.ioBuffer() -> delegates to directBuffer/heapBuffer
            // - Unpooled.buffer()/directBuffer() -> delegates to allocator methods
            //
            // WHY method name heuristics: Terminal allocation methods (directBuffer/heapBuffer)
            // have known return types, so we can skip the isDirect() call for these. In typical
            // Netty applications, these account for the majority of allocations, with wrappedBuffer
            // and compositeBuffer being less common.
            //
            // WHY switch statement: JIT compiler optimizes switch to a jump table (single
            // indirect jump) which is faster than if-else chains (multiple branches).
            if (FILTER_DIRECT_ONLY) {
                switch (methodName) {
                    case "heapBuffer":
                        // Safety guard: heapBuffer should not be instrumented during compile-time
                        // transformation when trackDirectOnly=true, but we check defensively here
                        // in case of instrumentation bugs or configuration changes.
                        return;

                    case "directBuffer":
                        // Known direct buffer method - always track without isDirect() check
                        break;

                    default:
                        // Ambiguous methods: wrappedBuffer, copiedBuffer, compositeBuffer
                        // These can return either heap or direct depending on inputs.
                        // We MUST check isDirect() at runtime because:
                        // 1. Unpooled.wrappedBuffer(directBuf) returns a direct buffer slice
                        // 2. Unpooled.wrappedBuffer(byte[]) returns a heap buffer
                        // 3. The actual type is only known after the method executes

                        // Type assertion: bytecode matcher ensures returnValue is ByteBuf
                        // This assert would only fail if there's a bug in the instrumentation
                        assert returnValue instanceof io.netty.buffer.ByteBuf
                            : "Instrumentation bug: returnValue should be ByteBuf but is "
                              + (returnValue == null ? "null" : returnValue.getClass());

                        io.netty.buffer.ByteBuf buf = (io.netty.buffer.ByteBuf) returnValue;
                        if (!buf.isDirect()) {
                            return;  // Heap buffer - skip tracking
                        }
                }
            }

            // Standard tracking continues for direct buffers (or all buffers if not filtering)
            ObjectTrackerHandler handler = ObjectTrackerRegistry.getHandler();
            ByteBufFlowTracker tracker = ByteBufFlowTracker.getInstance();

            // Check if this is a trackable object (ByteBuf)
            if (handler.shouldTrack(returnValue)) {
                int metric = handler.getMetric(returnValue);

                // Record this as the first touch (construction becomes root)
                tracker.recordMethodCall(
                    returnValue,
                    clazz.getSimpleName(),
                    methodName,
                    metric
                );
            }
        } finally {
            IS_TRACKING.set(false);
        }
    }
}
