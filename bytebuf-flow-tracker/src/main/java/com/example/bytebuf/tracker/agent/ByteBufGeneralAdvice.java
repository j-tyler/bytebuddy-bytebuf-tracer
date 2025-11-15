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

import java.util.Set;

/**
 * Optimized advice for methods with THREE OR MORE ByteBuf parameters.
 * Uses hybrid fast-path array with HashSet fallback for rare overflow.
 *
 * <p><b>Performance:</b>
 * <ul>
 *   <li>Still uses @AllArguments (allocation), but only for 3+ param methods (rare)</li>
 *   <li>Hybrid array/HashSet: zero allocations for ≤8 params, HashSet only for 9+</li>
 *   <li>No Integer boxing - stores primitive ints</li>
 *   <li>ThreadLocal reuse - HashSet allocated once per thread</li>
 * </ul>
 *
 * <p><b>Tradeoff:</b> This is slower than One/TwoParamAdvice but still better than
 * the original implementation. Methods with 3+ ByteBuf parameters are rare (~5% of calls).
 */
public class ByteBufGeneralAdvice {

    /**
     * Hybrid storage: fixed array for ≤8 items, HashSet overflow for 9+.
     * Avoids boxing and HashSet overhead for the common case.
     * Must be public for ByteBuddy inline advice access.
     */
    public static class TrackedParams {
        private final int[] fastPath = new int[8];
        private int fastPathSize = 0;
        private Set<Integer> overflow = null;

        void clear() {
            fastPathSize = 0;
            if (overflow != null) {
                overflow.clear();
            }
        }

        void add(int hash) {
            if (fastPathSize < 8) {
                fastPath[fastPathSize++] = hash;
            } else {
                if (overflow == null) {
                    overflow = new java.util.HashSet<>();
                }
                overflow.add(hash);
            }
        }

        boolean contains(int hash) {
            // Check fast path first (linear search, but only 0-8 items)
            for (int i = 0; i < fastPathSize; i++) {
                if (fastPath[i] == hash) {
                    return true;
                }
            }
            // Check overflow only if it exists
            return overflow != null && overflow.contains(hash);
        }
    }

    // Must be public for ByteBuddy inline advice access
    public static final ThreadLocal<TrackedParams> TRACKED_PARAMS =
        ThreadLocal.withInitial(TrackedParams::new);

    /**
     * Tracks all ByteBuf parameters.
     * Uses @AllArguments for simplicity since these methods are rare.
     */
    @Advice.OnMethodEnter
    public static void onMethodEnter(
            @Advice.Origin("#t") String className,
            @Advice.Origin("#m") String methodName,
            @Advice.Origin("#t.#m") String methodSignature,
            @Advice.AllArguments Object[] arguments) {

        if (ByteBufTrackingAdvice.IS_TRACKING.get()) {
            return;
        }

        if (arguments == null || arguments.length == 0) {
            return;
        }

        try {
            ByteBufTrackingAdvice.IS_TRACKING.set(true);
            ObjectTrackerHandler handler = ObjectTrackerRegistry.getByteBufHandler();
            ByteBufFlowTracker tracker = ByteBufFlowTracker.getInstance();

            TrackedParams tracked = TRACKED_PARAMS.get();
            tracked.clear();

            for (Object arg : arguments) {
                if (handler.shouldTrack(arg)) {
                    int metric = handler.getMetric(arg);
                    tracker.recordMethodCall(
                        arg, className, methodName, methodSignature, metric
                    );
                    tracked.add(System.identityHashCode(arg));
                }
            }
        } finally {
            ByteBufTrackingAdvice.IS_TRACKING.set(false);
        }
    }

    /**
     * Tracks ByteBuf return value if different from any parameter.
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
                TrackedParams tracked = TRACKED_PARAMS.get();

                if (!tracked.contains(returnHash)) {
                    int metric = handler.getMetric(returnValue);

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
            TRACKED_PARAMS.get().clear();
        }
    }
}
