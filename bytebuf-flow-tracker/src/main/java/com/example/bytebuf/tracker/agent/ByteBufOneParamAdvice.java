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
 * Optimized advice for methods with exactly ONE ByteBuf parameter.
 * Zero allocations - no Object[] array, no HashSet, uses direct parameter access.
 *
 * <p><b>Performance:</b>
 * <ul>
 *   <li>No Object[] allocation via @AllArguments</li>
 *   <li>No HashSet allocation for tracking params</li>
 *   <li>Single int in ThreadLocal instead of HashSet&lt;Integer&gt;</li>
 *   <li>Direct parameter access via @Argument(index, optional=true)</li>
 * </ul>
 *
 * <p><b>Coverage:</b> Handles ByteBuf at any position (0-3) in method signature.
 * For methods with more than 4 total parameters where ByteBuf is at index 4+,
 * falls back to {@link ByteBufGeneralAdvice}.
 */
public class ByteBufOneParamAdvice {

    // Store single param identity hash (primitive, no boxing!)
    // Must be public for ByteBuddy inline advice access
    public static final ThreadLocal<Integer> PARAM_HASH =
        ThreadLocal.withInitial(() -> 0);

    /**
     * Tracks the single ByteBuf parameter as it enters the method.
     * Searches positions 0-3 for the ByteBuf parameter.
     */
    @Advice.OnMethodEnter
    public static void onMethodEnter(
            @Advice.Origin("#t") String className,
            @Advice.Origin("#m") String methodName,
            @Advice.Origin("#t.#m") String methodSignature,
            @Advice.Argument(value = 0, optional = true) Object param0,
            @Advice.Argument(value = 1, optional = true) Object param1,
            @Advice.Argument(value = 2, optional = true) Object param2,
            @Advice.Argument(value = 3, optional = true) Object param3) {

        if (ByteBufTrackingAdvice.IS_TRACKING.get()) {
            return;
        }

        try {
            ByteBufTrackingAdvice.IS_TRACKING.set(true);

            // Fast path: Only check ByteBuf handler (no loop over custom handlers)
            ObjectTrackerHandler handler = ObjectTrackerRegistry.getByteBufHandler();
            ByteBufFlowTracker tracker = ByteBufFlowTracker.getInstance();

            // Find the one ByteBuf param (check positions 0-3)
            Object byteBuf = null;
            if (handler.shouldTrack(param0)) {
                byteBuf = param0;
            } else if (handler.shouldTrack(param1)) {
                byteBuf = param1;
            } else if (handler.shouldTrack(param2)) {
                byteBuf = param2;
            } else if (handler.shouldTrack(param3)) {
                byteBuf = param3;
            }

            if (byteBuf != null) {
                int metric = handler.getMetric(byteBuf);
                tracker.recordMethodCall(
                    byteBuf, className, methodName, methodSignature, metric
                );
                PARAM_HASH.set(System.identityHashCode(byteBuf));
            }
        } finally {
            ByteBufTrackingAdvice.IS_TRACKING.set(false);
        }
    }

    /**
     * Tracks ByteBuf return value if different from parameter.
     * Uses simple int comparison instead of HashSet.contains().
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

        try {
            ByteBufTrackingAdvice.IS_TRACKING.set(true);
            ObjectTrackerHandler handler = ObjectTrackerRegistry.getByteBufHandler();

            if (handler.shouldTrack(returnValue)) {
                int returnHash = System.identityHashCode(returnValue);

                // Simple int comparison - no HashSet!
                if (returnHash != PARAM_HASH.get()) {
                    int metric = handler.getMetric(returnValue);

                    // Cache "_return" suffix to avoid string concatenation
                    String methodNameReturn = AdviceCacheAccess.getOrComputeMethodNameReturn(methodName);
                    String methodSignatureReturn = AdviceCacheAccess.getOrComputeMethodSignatureReturn(methodSignature);

                    ByteBufFlowTracker.getInstance().recordMethodCall(
                        returnValue, className, methodNameReturn,
                        methodSignatureReturn, metric
                    );
                }
            }
        } finally {
            ByteBufTrackingAdvice.IS_TRACKING.set(false);
            PARAM_HASH.set(0);  // Clear for next call
        }
    }
}
