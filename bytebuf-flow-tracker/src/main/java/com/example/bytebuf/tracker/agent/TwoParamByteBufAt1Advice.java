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
 * Optimized ByteBuddy advice for methods with non-ByteBuf at position 0 and ByteBuf at position 1.
 *
 * <p><b>Method Signature Pattern:</b> {@code method(X other, ByteBuf buffer)}
 *
 * <p><b>Performance Optimization:</b> Eliminates Object[] array allocation by accessing
 * parameters directly via @Advice.Argument(index). Saves ~100-120 bytes per operation
 * compared to general advice with Object[] allocation.
 *
 * <p><b>Usage:</b> Applied only to methods matching:
 * <ul>
 *   <li>Exactly 2 parameters</li>
 *   <li>Position 0: Any type except ByteBuf</li>
 *   <li>Position 1: ByteBuf (or subclass)</li>
 *   <li>Public or protected visibility</li>
 * </ul>
 *
 * @see TwoParamByteBufAt0Advice
 * @see TwoParamBothByteBufAdvice
 * @see SingleByteBufParamAdvice
 */
public class TwoParamByteBufAt1Advice {

    // Shared infrastructure with other advice classes
    public static final ThreadLocal<Boolean> IS_TRACKING = ByteBufTrackingAdvice.IS_TRACKING;

    // Must be public for instrumented classes to access
    public static final ConcurrentHashMap<String, String> METHOD_NAME_RETURN_CACHE =
        ByteBufTrackingAdvice.METHOD_NAME_RETURN_CACHE;
    public static final ConcurrentHashMap<String, String> METHOD_SIGNATURE_RETURN_CACHE =
        ByteBufTrackingAdvice.METHOD_SIGNATURE_RETURN_CACHE;

    // ThreadLocal for storing tracked parameter hash (just one ByteBuf to track)
    public static final ThreadLocal<Integer> TRACKED_PARAM_HASH = new ThreadLocal<>();

    /**
     * Tracks ByteBuf at position 1 as it enters the method.
     *
     * <p><b>Optimization:</b> Direct parameter access eliminates Object[2] array allocation.
     */
    @Advice.OnMethodEnter
    public static void onMethodEnter(
            @Advice.Argument(0) Object otherParam,  // Non-ByteBuf at position 0 (ignored)
            @Advice.Argument(1) ByteBuf buffer,     // Known ByteBuf at position 1
            @Advice.Origin("#t") String className,
            @Advice.Origin("#m") String methodName,
            @Advice.Origin("#t.#m") String methodSignature) {

        if (IS_TRACKING.get()) {
            return;
        }

        if (buffer == null) {
            return;
        }

        try {
            IS_TRACKING.set(true);
            ByteBufFlowTracker tracker = ByteBufFlowTracker.getInstance();

            int refCount = buffer.refCnt();
            tracker.recordMethodCall(
                buffer,
                className,
                methodName,
                methodSignature,
                refCount
            );

            // Store hash for return value duplicate check
            TRACKED_PARAM_HASH.set(System.identityHashCode(buffer));

        } finally {
            IS_TRACKING.set(false);
        }
    }

    /**
     * Tracks ByteBuf return value if different from parameter.
     */
    @Advice.OnMethodExit(onThrowable = Throwable.class)
    public static void onMethodExit(
            @Advice.Argument(0) Object otherParam,
            @Advice.Argument(1) ByteBuf buffer,
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

                // Check if different from parameter
                Integer trackedHash = TRACKED_PARAM_HASH.get();
                if (trackedHash == null || trackedHash.intValue() != hashCode) {
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
            TRACKED_PARAM_HASH.remove();
        }
    }
}
