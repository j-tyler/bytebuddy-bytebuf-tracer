/*
 * Copyright 2025 Justin Marsh
 * SPDX-License-Identifier: Apache-2.0
 */

package com.example.bytebuf.tracker.agent;

import com.example.bytebuf.tracker.ByteBufFlowTracker;
import io.netty.buffer.ByteBuf;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.implementation.bytecode.assign.Assigner;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Optimized ByteBuddy advice for methods with ByteBuf at both positions 0 and 1.
 *
 * <p><b>Method Signature Pattern:</b> {@code method(ByteBuf buffer1, ByteBuf buffer2)}
 *
 * <p><b>Performance Optimization:</b> Eliminates Object[] array allocation by accessing
 * parameters directly via @Advice.Argument(index). Saves ~100-120 bytes per operation
 * compared to general advice with Object[] allocation. Tracks both ByteBuf parameters.
 *
 * <p><b>Usage:</b> Applied only to methods matching:
 * <ul>
 *   <li>Exactly 2 parameters</li>
 *   <li>Position 0: ByteBuf (or subclass)</li>
 *   <li>Position 1: ByteBuf (or subclass)</li>
 *   <li>Public or protected visibility</li>
 * </ul>
 *
 * @see TwoParamByteBufAt0Advice
 * @see TwoParamByteBufAt1Advice
 * @see SingleByteBufParamAdvice
 */
public class TwoParamBothByteBufAdvice {

    // Shared infrastructure with other advice classes
    public static final ThreadLocal<Boolean> IS_TRACKING = ByteBufTrackingAdvice.IS_TRACKING;
    private static final ConcurrentHashMap<String, String> METHOD_NAME_RETURN_CACHE =
        ByteBufTrackingAdvice.METHOD_NAME_RETURN_CACHE;
    private static final ConcurrentHashMap<String, String> METHOD_SIGNATURE_RETURN_CACHE =
        ByteBufTrackingAdvice.METHOD_SIGNATURE_RETURN_CACHE;

    // ThreadLocal for storing tracked parameter hashes (two ByteBufs to track)
    // Using simple int array instead of HashSet for efficiency
    public static final ThreadLocal<int[]> TRACKED_PARAM_HASHES =
        ThreadLocal.withInitial(() -> new int[2]);

    /**
     * Tracks both ByteBuf parameters as they enter the method.
     *
     * <p><b>Optimization:</b> Direct parameter access eliminates Object[2] array allocation.
     * Tracks both buffers to build complete flow tree.
     */
    @Advice.OnMethodEnter
    public static void onMethodEnter(
            @Advice.Argument(0) ByteBuf buffer0,    // ByteBuf at position 0
            @Advice.Argument(1) ByteBuf buffer1,    // ByteBuf at position 1
            @Advice.Origin("#t") String className,
            @Advice.Origin("#m") String methodName,
            @Advice.Origin("#t.#m") String methodSignature) {

        if (IS_TRACKING.get()) {
            return;
        }

        try {
            IS_TRACKING.set(true);
            ByteBufFlowTracker tracker = ByteBufFlowTracker.getInstance();
            int[] hashes = TRACKED_PARAM_HASHES.get();

            // Track first ByteBuf (position 0)
            if (buffer0 != null) {
                int refCount = buffer0.refCnt();
                tracker.recordMethodCall(
                    buffer0,
                    className,
                    methodName,
                    methodSignature,
                    refCount
                );
                hashes[0] = System.identityHashCode(buffer0);
            } else {
                hashes[0] = 0;  // Mark as null
            }

            // Track second ByteBuf (position 1)
            if (buffer1 != null) {
                int refCount = buffer1.refCnt();
                tracker.recordMethodCall(
                    buffer1,
                    className,
                    methodName,
                    methodSignature,
                    refCount
                );
                hashes[1] = System.identityHashCode(buffer1);
            } else {
                hashes[1] = 0;  // Mark as null
            }

        } finally {
            IS_TRACKING.set(false);
        }
    }

    /**
     * Tracks ByteBuf return value if different from both parameters.
     */
    @Advice.OnMethodExit(onThrowable = Throwable.class)
    public static void onMethodExit(
            @Advice.Argument(0) ByteBuf buffer0,
            @Advice.Argument(1) ByteBuf buffer1,
            @Advice.Origin("#t") String className,
            @Advice.Origin("#m") String methodName,
            @Advice.Origin("#t.#m") String methodSignature,
            @Advice.Return(typing = Assigner.Typing.DYNAMIC) Object returnValue,
            @Advice.Thrown Throwable thrown) {

        if (IS_TRACKING.get()) {
            return;
        }

        try {
            IS_TRACKING.set(true);

            if (returnValue instanceof ByteBuf) {
                ByteBuf returnBuf = (ByteBuf) returnValue;
                int hashCode = System.identityHashCode(returnBuf);

                // Check if different from both parameters
                int[] hashes = TRACKED_PARAM_HASHES.get();
                if (hashCode != hashes[0] && hashCode != hashes[1]) {
                    ByteBufFlowTracker tracker = ByteBufFlowTracker.getInstance();

                    // Use cached "_return" suffixes
                    String methodNameReturn = METHOD_NAME_RETURN_CACHE.get(methodName);
                    if (methodNameReturn == null) {
                        String computed = methodName + "_return";
                        methodNameReturn = METHOD_NAME_RETURN_CACHE.putIfAbsent(methodName, computed);
                        if (methodNameReturn == null) methodNameReturn = computed;
                    }

                    String methodSignatureReturn = METHOD_SIGNATURE_RETURN_CACHE.get(methodSignature);
                    if (methodSignatureReturn == null) {
                        String computed = methodSignature + "_return";
                        methodSignatureReturn = METHOD_SIGNATURE_RETURN_CACHE.putIfAbsent(methodSignature, computed);
                        if (methodSignatureReturn == null) methodSignatureReturn = computed;
                    }

                    int refCount = returnBuf.refCnt();
                    tracker.recordMethodCall(
                        returnBuf,
                        className,
                        methodNameReturn,
                        methodSignatureReturn,
                        refCount
                    );
                }
            }
        } finally {
            IS_TRACKING.set(false);
            // Clear hashes for next invocation
            int[] hashes = TRACKED_PARAM_HASHES.get();
            hashes[0] = 0;
            hashes[1] = 0;
        }
    }
}
