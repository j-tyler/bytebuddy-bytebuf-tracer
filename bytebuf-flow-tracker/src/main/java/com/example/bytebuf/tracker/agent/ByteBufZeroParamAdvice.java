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
 * Optimized advice for methods with ZERO ByteBuf parameters (return value only).
 * Absolute minimal overhead - no OnMethodEnter, no ThreadLocal, only tracks return.
 *
 * <p><b>Performance:</b>
 * <ul>
 *   <li>No @OnMethodEnter execution</li>
 *   <li>No ThreadLocal allocation</li>
 *   <li>No parameter checking</li>
 *   <li>Only tracks return value with "_return" suffix</li>
 * </ul>
 *
 * <p><b>Use Case:</b> Factory methods and allocators:
 * <ul>
 *   <li>ByteBuf create()</li>
 *   <li>ByteBuf allocate(int size)</li>
 *   <li>ByteBuf buffer()</li>
 * </ul>
 */
public class ByteBufZeroParamAdvice {

    /**
     * No entry tracking needed - method has no ByteBuf parameters.
     * This method is intentionally empty but kept for symmetry.
     * ByteBuddy will optimize this away or inline it to nothing.
     */
    @Advice.OnMethodEnter
    public static void onMethodEnter() {
        // Intentionally empty - no ByteBuf parameters to track
        // ByteBuddy may optimize this away entirely
    }

    /**
     * Tracks ByteBuf return value only.
     * No duplicate checking needed since there are no parameters.
     */
    @Advice.OnMethodExit(onThrowable = Throwable.class)
    public static void onMethodExit(
            @Advice.Origin("#t") String className,
            @Advice.Origin("#m") String methodName,
            @Advice.Origin("#t.#m") String methodSignature,
            @Advice.Return(typing = Assigner.Typing.DYNAMIC) Object returnValue,
            @Advice.Thrown Throwable thrown) {

        if (ByteBufTrackingAdvice.IS_TRACKING.get()) {
            return;
        }

        // Null or exception - nothing to track
        if (returnValue == null || thrown != null) {
            return;
        }

        try {
            ByteBufTrackingAdvice.IS_TRACKING.set(true);
            ObjectTrackerHandler handler = ObjectTrackerRegistry.getByteBufHandler();

            if (handler.shouldTrack(returnValue)) {
                int metric = handler.getMetric(returnValue);

                // Cache "_return" suffix
                String methodNameReturn = AdviceCacheAccess.getOrComputeMethodNameReturn(methodName);
                String methodSignatureReturn = AdviceCacheAccess.getOrComputeMethodSignatureReturn(methodSignature);

                ByteBufFlowTracker.getInstance().recordMethodCall(
                    returnValue, className, methodNameReturn,
                    methodSignatureReturn, metric
                );
            }
        } finally {
            ByteBufTrackingAdvice.IS_TRACKING.set(false);
        }
    }
}
