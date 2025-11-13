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
 * Optimized ByteBuddy advice for methods with zero parameters that return ByteBuf.
 *
 * <p><b>Method Signature Pattern:</b> {@code ByteBuf method()}
 *
 * <p><b>Performance Optimization:</b> Eliminates Object[] array allocation entirely.
 * Zero-parameter methods create Object[0] arrays in both OnMethodEnter and OnMethodExit
 * with general advice. This specialized advice saves ~32-48 bytes per operation.
 *
 * <p><b>Usage:</b> Applied only to methods matching:
 * <ul>
 *   <li>Exactly 0 parameters</li>
 *   <li>Return type: ByteBuf (or subclass)</li>
 *   <li>Public or protected visibility</li>
 * </ul>
 *
 * <p><b>Common Examples:</b>
 * <ul>
 *   <li>Factory methods: {@code ByteBuf allocate()}</li>
 *   <li>Builders: {@code ByteBuf build()}</li>
 *   <li>Accessors: {@code ByteBuf getBuffer()}</li>
 * </ul>
 *
 * @see SingleByteBufParamAdvice
 * @see TwoParamByteBufAt0Advice
 * @see TwoParamByteBufAt1Advice
 * @see TwoParamBothByteBufAdvice
 */
public class ZeroParamByteBufReturnAdvice {

    // Shared infrastructure with other advice classes
    public static final ThreadLocal<Boolean> IS_TRACKING = ByteBufTrackingAdvice.IS_TRACKING;
    private static final ConcurrentHashMap<String, String> METHOD_NAME_RETURN_CACHE =
        ByteBufTrackingAdvice.METHOD_NAME_RETURN_CACHE;
    private static final ConcurrentHashMap<String, String> METHOD_SIGNATURE_RETURN_CACHE =
        ByteBufTrackingAdvice.METHOD_SIGNATURE_RETURN_CACHE;

    /**
     * NO-OP for zero-parameter methods.
     *
     * <p><b>Optimization:</b> No parameters means no tracking needed on entry.
     * This method exists only to establish the advice structure - it performs no work.
     */
    @Advice.OnMethodEnter
    public static void onMethodEnter(
            @Advice.Origin("#t") String className,
            @Advice.Origin("#m") String methodName,
            @Advice.Origin("#t.#m") String methodSignature) {
        // NO-OP: Zero parameters means nothing to track on entry
        // This method body intentionally left minimal to avoid any overhead
    }

    /**
     * Tracks ByteBuf return value from zero-parameter methods.
     *
     * <p><b>Optimization:</b> No parameter array allocation, no duplicate checking needed.
     * Simply records the returned ByteBuf in the flow tree.
     */
    @Advice.OnMethodExit(onThrowable = Throwable.class)
    public static void onMethodExit(
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
        } finally {
            IS_TRACKING.set(false);
        }
    }
}
