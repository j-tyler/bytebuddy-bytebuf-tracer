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

    // Re-entrance guard: prevents infinite recursion when tracking code triggers instrumented methods
    // Must be public for instrumented classes to access
    public static final ThreadLocal<Boolean> IS_TRACKING =
        ThreadLocal.withInitial(() -> false);

    // Stores identity hash codes of parameters tracked at method entry
    // Prevents duplicate tracking when the same object is both a parameter and return value
    // Must be public for instrumented classes to access
    public static final ThreadLocal<java.util.Set<Integer>> TRACKED_PARAMS =
        ThreadLocal.withInitial(java.util.HashSet::new);

    // WHY AdviceCacheAccess: ByteBuddy inlines this advice code directly into instrumented
    // classes. Inlined code executes in the target class's security context, requiring
    // public access to any fields. AdviceCacheAccess provides controlled public methods
    // instead of exposing raw ConcurrentHashMap collections (which could be corrupted via
    // external .clear() or .put() calls). See AdviceCacheAccess javadoc for details.

    /**
     * Tracks objects as they enter methods (flow down the call stack).
     */
    @Advice.OnMethodEnter
    public static void onMethodEnter(
            @Advice.Origin("#t") String className,
            @Advice.Origin("#m") String methodName,
            @Advice.Origin("#t.#m") String methodSignature,
            @Advice.AllArguments Object[] arguments) {

        if (IS_TRACKING.get()) {
            return;
        }

        if (arguments == null || arguments.length == 0) {
            return;
        }

        try {
            IS_TRACKING.set(true);
            ObjectTrackerHandler handler = ObjectTrackerRegistry.getByteBufHandler();
            ByteBufFlowTracker tracker = ByteBufFlowTracker.getInstance();

            TRACKED_PARAMS.get().clear();

            for (Object arg : arguments) {
                if (handler.shouldTrack(arg)) {
                    int metric = handler.getMetric(arg);
                    tracker.recordMethodCall(
                        arg,
                        className,
                        methodName,
                        methodSignature,
                        metric
                    );
                    TRACKED_PARAMS.get().add(System.identityHashCode(arg));
                }
            }
        } finally {
            IS_TRACKING.set(false);
        }
    }

    /**
     * Tracks objects as they exit methods (flow up the call stack).
     * The _return suffix distinguishes upward flow from downward flow in the Trie.
     * Only tracks return values that weren't already tracked as parameters to avoid duplicates.
     */
    @Advice.OnMethodExit(onThrowable = Throwable.class)
    public static void onMethodExit(
            @Advice.Origin("#t") String className,
            @Advice.Origin("#m") String methodName,
            @Advice.Origin("#t.#m") String methodSignature,
            @Advice.AllArguments Object[] arguments,
            @Advice.Return(typing = Assigner.Typing.DYNAMIC) Object returnValue,
            @Advice.Thrown Throwable thrown) {

        if (IS_TRACKING.get()) {
            return;
        }

        try {
            IS_TRACKING.set(true);
            ObjectTrackerHandler handler = ObjectTrackerRegistry.getByteBufHandler();
            ByteBufFlowTracker tracker = ByteBufFlowTracker.getInstance();

            if (handler.shouldTrack(returnValue)) {
                int hashCode = System.identityHashCode(returnValue);
                if (!TRACKED_PARAMS.get().contains(hashCode)) {
                    int metric = handler.getMetric(returnValue);

                    // Cache "_return" suffix to avoid string concatenation on hot path
                    String methodNameReturn = AdviceCacheAccess.getOrComputeMethodNameReturn(methodName);
                    String methodSignatureReturn = AdviceCacheAccess.getOrComputeMethodSignatureReturn(methodSignature);

                    tracker.recordMethodCall(
                        returnValue,
                        className,
                        methodNameReturn,
                        methodSignatureReturn,
                        metric
                    );
                }
            }
        } finally {
            IS_TRACKING.set(false);
            TRACKED_PARAMS.remove();
        }
    }
}
