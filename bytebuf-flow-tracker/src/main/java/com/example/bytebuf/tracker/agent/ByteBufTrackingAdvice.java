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
    public static final ThreadLocal<java.util.Set<Integer>> TRACKED_PARAMS =
        ThreadLocal.withInitial(java.util.HashSet::new);

    /**
     * Method entry advice - tracks objects in parameters
     *
     * <p><b>Optimization (Idea 2):</b> Uses {@code @Advice.Origin("#T.#m")} to inject
     * pre-computed method signature at instrumentation time. This eliminates runtime
     * string concatenation overhead (100-200 B/op savings).
     */
    @Advice.OnMethodEnter
    public static void onMethodEnter(
            @Advice.Origin("#t.#m") String methodSignature,
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

            for (Object arg : arguments) {
                if (handler.shouldTrack(arg)) {
                    int metric = handler.getMetric(arg);
                    tracker.recordMethodCall(
                        arg,
                        methodSignature,  // Pre-computed at instrumentation time
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
     * <p><b>Optimization (Idea 2):</b> Uses pre-computed method signature.
     * The "_return" suffix is concatenated once at instrumentation time via
     * {@code @Advice.Origin("#t.#m_return")}.
     */
    @Advice.OnMethodExit(onThrowable = Throwable.class)
    public static void onMethodExit(
            @Advice.Origin("#t.#m_return") String methodSignatureReturn,
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

            // Track return values with _return suffix
            // Only track if it wasn't already tracked as a parameter (to avoid duplicates)
            if (handler.shouldTrack(returnValue)) {
                int hashCode = System.identityHashCode(returnValue);
                if (!TRACKED_PARAMS.get().contains(hashCode)) {
                    int metric = handler.getMetric(returnValue);
                    tracker.recordMethodCall(
                        returnValue,
                        methodSignatureReturn,  // Pre-computed at instrumentation time
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
