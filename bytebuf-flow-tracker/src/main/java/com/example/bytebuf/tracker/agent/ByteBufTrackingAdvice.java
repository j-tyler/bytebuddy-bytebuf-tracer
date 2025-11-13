/*
 * Copyright 2025 Justin Marsh
 * SPDX-License-Identifier: Apache-2.0
 */

package com.example.bytebuf.tracker.agent;

import com.example.bytebuf.api.tracker.ObjectTrackerHandler;
import com.example.bytebuf.tracker.ByteBufFlowTracker;
import com.example.bytebuf.tracker.ObjectTrackerRegistry;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.implementation.bytecode.assign.Assigner;

/**
 * ByteBuddy advice for tracking object flow through methods.
 * Originally designed for ByteBuf, but now supports any object via ObjectTrackerHandler.
 */
public class ByteBufTrackingAdvice {

    // Re-entrance guard to prevent infinite recursion when tracking code
    // triggers other instrumented methods
    // Must be public for instrumented classes to access
    public static final ThreadLocal<Boolean> IS_TRACKING =
        ThreadLocal.withInitial(() -> false);

    // ThreadLocal to store identity hash codes of parameters tracked during method entry
    // This prevents duplicate tracking when the same object is both a parameter and return value
    // Must be public for instrumented classes to access
    //
    // Optimization (Idea 1): Use primitive int array instead of HashSet<Integer>
    // - Eliminates boxing overhead (Integer object allocation)
    // - Faster for small parameter counts (most methods have < 10 params)
    // - Expected savings: 100-200 B/op
    private static final int MAX_TRACKED_PARAMS = 32; // Sufficient for most methods

    public static class TrackedParamsArray {
        final int[] hashCodes = new int[MAX_TRACKED_PARAMS];
        int count = 0;

        public void clear() {
            count = 0;
        }

        public void add(int hashCode) {
            if (count < MAX_TRACKED_PARAMS) {
                hashCodes[count++] = hashCode;
            }
            // If overflow, silently ignore (rare case, acceptable trade-off)
        }

        public boolean contains(int hashCode) {
            for (int i = 0; i < count; i++) {
                if (hashCodes[i] == hashCode) {
                    return true;
                }
            }
            return false;
        }
    }

    public static final ThreadLocal<TrackedParamsArray> TRACKED_PARAMS =
        ThreadLocal.withInitial(TrackedParamsArray::new);

    /**
     * Method entry advice - tracks objects in parameters
     *
     * <p><b>Memory optimization:</b> Extracts simple class name from pre-computed
     * fully-qualified signature. The full signature is embedded at instrumentation time
     * by ByteBuddy, then we extract the simple name at advice entry using cheap string
     * operations. This avoids both runtime concatenation and getSimpleName() overhead.
     */
    @Advice.OnMethodEnter
    public static void onMethodEnter(
            @Advice.Origin("#t.#m") String fullSignature,  // Pre-computed: "io.netty.buffer.UnpooledByteBufAllocator.heapBuffer"
            @Advice.AllArguments Object[] arguments) {

        // Prevent re-entrant calls
        if (IS_TRACKING.get()) {
            return;
        }

        if (arguments == null || arguments.length == 0) {
            return;
        }

        try {
            IS_TRACKING.set(true);
            ObjectTrackerHandler handler = ObjectTrackerRegistry.getHandler();
            ByteBufFlowTracker tracker = ByteBufFlowTracker.getInstance();

            // Clear the tracked params set for this invocation
            TRACKED_PARAMS.get().clear();

            // Extract simple class name from pre-computed signature
            // "io.netty.buffer.UnpooledByteBufAllocator.heapBuffer" -> "UnpooledByteBufAllocator.heapBuffer"
            int lastMethodDot = fullSignature.lastIndexOf('.');
            int lastPackageDot = fullSignature.lastIndexOf('.', lastMethodDot - 1);
            String simpleSignature = fullSignature.substring(lastPackageDot + 1);

            for (Object arg : arguments) {
                if (handler.shouldTrack(arg)) {
                    int metric = handler.getMetric(arg);
                    tracker.recordMethodCall(
                        arg,
                        simpleSignature,  // Short signature extracted from pre-computed string
                        metric
                    );
                    // Record that we tracked this object
                    TRACKED_PARAMS.get().add(System.identityHashCode(arg));
                }
            }
        } finally {
            IS_TRACKING.set(false);
        }
    }

    /**
     * Method exit advice - tracks return values with _return suffix
     * This shows when objects "go up the stack" (returned from methods)
     *
     * <p><b>Memory optimization:</b> Extracts simple class name from pre-computed
     * signature and appends "_return" suffix.
     */
    @Advice.OnMethodExit(onThrowable = Throwable.class)
    public static void onMethodExit(
            @Advice.Origin("#t.#m") String fullSignature,  // Pre-computed
            @Advice.AllArguments Object[] arguments,
            @Advice.Return(typing = Assigner.Typing.DYNAMIC) Object returnValue,
            @Advice.Thrown Throwable thrown) {

        // Prevent re-entrant calls
        if (IS_TRACKING.get()) {
            return;
        }

        try {
            IS_TRACKING.set(true);
            ObjectTrackerHandler handler = ObjectTrackerRegistry.getHandler();
            ByteBufFlowTracker tracker = ByteBufFlowTracker.getInstance();

            // Extract simple class name from pre-computed signature and add _return suffix
            // "io.netty.buffer.UnpooledByteBufAllocator.heapBuffer" -> "UnpooledByteBufAllocator.heapBuffer_return"
            int lastMethodDot = fullSignature.lastIndexOf('.');
            int lastPackageDot = fullSignature.lastIndexOf('.', lastMethodDot - 1);
            String simpleSignature = fullSignature.substring(lastPackageDot + 1) + "_return";

            // Track return values with _return suffix
            // Only track if it wasn't already tracked as a parameter (to avoid duplicates)
            if (handler.shouldTrack(returnValue)) {
                int hashCode = System.identityHashCode(returnValue);
                if (!TRACKED_PARAMS.get().contains(hashCode)) {
                    int metric = handler.getMetric(returnValue);
                    tracker.recordMethodCall(
                        returnValue,
                        simpleSignature,  // Short signature extracted from pre-computed string
                        metric
                    );
                }
            }
        } finally {
            IS_TRACKING.set(false);
            // Clear the tracked params for this thread
            TRACKED_PARAMS.get().clear();
        }
    }
}
