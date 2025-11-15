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
     * Extract simple class name from fully qualified method signature.
     * Converts "io.netty.buffer.UnpooledByteBufAllocator.directBuffer" to "UnpooledByteBufAllocator.directBuffer"
     * Must be public for ByteBuddy inlining (instrumented classes need access)
     */
    public static String toSimpleName(String fqnMethodSignature) {
        int lastDot = fqnMethodSignature.lastIndexOf('.');
        if (lastDot == -1) {
            return fqnMethodSignature;  // No package, return as-is
        }
        int secondLastDot = fqnMethodSignature.lastIndexOf('.', lastDot - 1);
        if (secondLastDot == -1) {
            return fqnMethodSignature;  // Already simple name format
        }
        return fqnMethodSignature.substring(secondLastDot + 1);
    }

    /**
     * Method entry advice - tracks objects in parameters
     */
    @Advice.OnMethodEnter
    public static void onMethodEnter(
            @Advice.Origin("#t.#m") String fqnMethodSignature,
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

            // Convert to simple class name for memory efficiency
            String methodSignature = toSimpleName(fqnMethodSignature);

            for (Object arg : arguments) {
                if (handler.shouldTrack(arg)) {
                    int metric = handler.getMetric(arg);
                    tracker.recordMethodCall(
                        arg,
                        methodSignature,
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
     */
    @Advice.OnMethodExit(onThrowable = Throwable.class)
    public static void onMethodExit(
            @Advice.Origin("#t.#m") String fqnMethodSignature,
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

            // Convert to simple class name for memory efficiency
            String methodSignature = toSimpleName(fqnMethodSignature);

            // Track return values with _return suffix
            // Only track if it wasn't already tracked as a parameter (to avoid duplicates)
            if (handler.shouldTrack(returnValue)) {
                int hashCode = System.identityHashCode(returnValue);
                if (!TRACKED_PARAMS.get().contains(hashCode)) {
                    int metric = handler.getMetric(returnValue);
                    tracker.recordMethodCall(
                        returnValue,
                        methodSignature + "_return",
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
