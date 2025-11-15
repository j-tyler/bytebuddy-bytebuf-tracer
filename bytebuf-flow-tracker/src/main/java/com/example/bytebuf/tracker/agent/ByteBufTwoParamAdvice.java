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
 * Optimized advice for methods with exactly TWO ByteBuf parameters.
 * Zero allocations - no Object[] array, no HashSet, uses packed long for two hashes.
 *
 * <p><b>Performance:</b>
 * <ul>
 *   <li>No Object[] allocation via @AllArguments</li>
 *   <li>No HashSet allocation for tracking params</li>
 *   <li>Single long in ThreadLocal packs both identity hashes</li>
 *   <li>Direct parameter access via @Argument(index, optional=true)</li>
 * </ul>
 *
 * <p><b>Hash Packing:</b> Two 32-bit identity hashes packed into one 64-bit long:
 * <ul>
 *   <li>High 32 bits: first ByteBuf hash</li>
 *   <li>Low 32 bits: second ByteBuf hash</li>
 * </ul>
 */
public class ByteBufTwoParamAdvice {

    // Pack two identity hashes into a single long (no boxing!)
    // High 32 bits: first hash, Low 32 bits: second hash
    // Must be public for ByteBuddy inline advice access
    public static final ThreadLocal<Long> PARAM_HASHES =
        ThreadLocal.withInitial(() -> 0L);

    /**
     * Tracks both ByteBuf parameters as they enter the method.
     * Searches positions 0-3 for exactly two ByteBuf parameters.
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

            // Fast path: Only check ByteBuf handler
            ObjectTrackerHandler handler = ObjectTrackerRegistry.getByteBufHandler();
            ByteBufFlowTracker tracker = ByteBufFlowTracker.getInstance();

            // Find exactly two ByteBuf params
            int hash1 = 0;
            int hash2 = 0;
            int found = 0;

            // Check param0
            if (handler.shouldTrack(param0)) {
                int metric = handler.getMetric(param0);
                tracker.recordMethodCall(param0, className, methodName, methodSignature, metric);
                hash1 = System.identityHashCode(param0);
                found++;
            }

            // Check param1
            if (handler.shouldTrack(param1)) {
                int metric = handler.getMetric(param1);
                tracker.recordMethodCall(param1, className, methodName, methodSignature, metric);
                if (found == 0) {
                    hash1 = System.identityHashCode(param1);
                } else {
                    hash2 = System.identityHashCode(param1);
                }
                found++;
            }

            // Check param2 (only if we haven't found 2 yet)
            if (found < 2 && handler.shouldTrack(param2)) {
                int metric = handler.getMetric(param2);
                tracker.recordMethodCall(param2, className, methodName, methodSignature, metric);
                if (found == 0) {
                    hash1 = System.identityHashCode(param2);
                } else {
                    hash2 = System.identityHashCode(param2);
                }
                found++;
            }

            // Check param3 (only if we haven't found 2 yet)
            if (found < 2 && handler.shouldTrack(param3)) {
                int metric = handler.getMetric(param3);
                tracker.recordMethodCall(param3, className, methodName, methodSignature, metric);
                if (found == 0) {
                    hash1 = System.identityHashCode(param3);
                } else {
                    hash2 = System.identityHashCode(param3);
                }
                found++;
            }

            // Pack both hashes into a long (high 32: hash1, low 32: hash2)
            long packed = ((long) hash1 << 32) | (hash2 & 0xFFFFFFFFL);
            PARAM_HASHES.set(packed);

        } finally {
            ByteBufTrackingAdvice.IS_TRACKING.set(false);
        }
    }

    /**
     * Tracks ByteBuf return value if different from both parameters.
     * Unpacks the long to check against both hashes.
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

                // Unpack both hashes
                long packed = PARAM_HASHES.get();
                int hash1 = (int) (packed >>> 32);
                int hash2 = (int) (packed & 0xFFFFFFFFL);

                // Check against both param hashes
                if (returnHash != hash1 && returnHash != hash2) {
                    int metric = handler.getMetric(returnValue);

                    // Cache "_return" suffix
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
            PARAM_HASHES.set(0L);  // Clear for next call
        }
    }
}
