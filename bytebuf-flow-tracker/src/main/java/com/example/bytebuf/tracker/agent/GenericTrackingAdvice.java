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

import java.util.List;
import java.util.Set;

/**
 * Generic advice for methods with CUSTOM tracked objects (and possibly ByteBuf).
 * Uses slower Object[] path since custom object tracking is rare.
 *
 * <p><b>Design Philosophy:</b>
 * <ul>
 *   <li>ByteBuf-only methods: Use optimized advice (90%+ of calls)</li>
 *   <li>Custom object methods: Use this generic advice (5-10% of calls)</li>
 *   <li>Acceptable tradeoff: Slower path for rare operations</li>
 * </ul>
 *
 * <p><b>Performance:</b>
 * <ul>
 *   <li>Uses @AllArguments (allocates Object[])</li>
 *   <li>Hybrid array/HashSet for param tracking</li>
 *   <li>Checks ALL handlers (ByteBuf + custom)</li>
 *   <li>Acceptable since custom object methods are rare</li>
 * </ul>
 *
 * <p><b>Multi-Handler Support:</b>
 * This advice supports tracking multiple object types simultaneously:
 * <ul>
 *   <li>ByteBuf via ByteBufHandler</li>
 *   <li>Connection via custom ConnectionHandler</li>
 *   <li>FileHandle via custom FileHandleHandler</li>
 *   <li>All in the same method if needed</li>
 * </ul>
 */
public class GenericTrackingAdvice {

    /**
     * Hybrid storage: fixed array for ≤8 items, HashSet overflow for 9+.
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
            for (int i = 0; i < fastPathSize; i++) {
                if (fastPath[i] == hash) {
                    return true;
                }
            }
            return overflow != null && overflow.contains(hash);
        }
    }

    // Must be public for ByteBuddy inline advice access
    public static final ThreadLocal<TrackedParams> TRACKED_PARAMS =
        ThreadLocal.withInitial(TrackedParams::new);

    /**
     * Tracks all parameters using ALL registered handlers.
     * Checks ByteBuf handler first (optimization), then custom handlers.
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
            ByteBufFlowTracker tracker = ByteBufFlowTracker.getInstance();
            TrackedParams tracked = TRACKED_PARAMS.get();
            tracked.clear();

            // Get all handlers
            ObjectTrackerHandler byteBufHandler = ObjectTrackerRegistry.getByteBufHandler();
            List<ObjectTrackerHandler> customHandlers = ObjectTrackerRegistry.getCustomHandlers();

            for (Object arg : arguments) {
                if (arg == null) {
                    continue;
                }

                ObjectTrackerHandler matchingHandler = null;

                // Try ByteBuf first (fast path, most common)
                if (byteBufHandler.shouldTrack(arg)) {
                    matchingHandler = byteBufHandler;
                } else {
                    // Try custom handlers
                    for (ObjectTrackerHandler handler : customHandlers) {
                        if (handler.shouldTrack(arg)) {
                            matchingHandler = handler;
                            break;  // First match wins
                        }
                    }
                }

                if (matchingHandler != null) {
                    int metric = matchingHandler.getMetric(arg);
                    tracker.recordMethodCall(
                        arg, className, methodName, methodSignature, metric
                    );
                    tracked.add(System.identityHashCode(arg));
                }
            }
        } catch (Exception e) {
            // Don't let tracking errors break the application
            System.err.println("[GenericTrackingAdvice] Error in onMethodEnter: " + e.getMessage());
            e.printStackTrace();
        } finally {
            ByteBufTrackingAdvice.IS_TRACKING.set(false);
        }
    }

    /**
     * Tracks return value using ALL registered handlers.
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

        if (returnValue == null) {
            return;
        }

        try {
            ByteBufTrackingAdvice.IS_TRACKING.set(true);

            // Get all handlers
            ObjectTrackerHandler byteBufHandler = ObjectTrackerRegistry.getByteBufHandler();
            List<ObjectTrackerHandler> customHandlers = ObjectTrackerRegistry.getCustomHandlers();

            ObjectTrackerHandler matchingHandler = null;

            // Try ByteBuf first
            if (byteBufHandler.shouldTrack(returnValue)) {
                matchingHandler = byteBufHandler;
            } else {
                // Try custom handlers
                for (ObjectTrackerHandler handler : customHandlers) {
                    if (handler.shouldTrack(returnValue)) {
                        matchingHandler = handler;
                        break;
                    }
                }
            }

            if (matchingHandler != null) {
                int returnHash = System.identityHashCode(returnValue);
                TrackedParams tracked = TRACKED_PARAMS.get();

                // Only track if not already tracked as a parameter
                if (!tracked.contains(returnHash)) {
                    int metric = matchingHandler.getMetric(returnValue);

                    String methodNameReturn = AdviceCacheAccess.getOrComputeMethodNameReturn(methodName);
                    String methodSignatureReturn = AdviceCacheAccess.getOrComputeMethodSignatureReturn(methodSignature);

                    ByteBufFlowTracker.getInstance().recordMethodCall(
                        returnValue, className, methodNameReturn,
                        methodSignatureReturn, metric
                    );
                }
            }
        } catch (Exception e) {
            // Don't let tracking errors break the application
            System.err.println("[GenericTrackingAdvice] Error in onMethodExit: " + e.getMessage());
            e.printStackTrace();
        } finally {
            ByteBufTrackingAdvice.IS_TRACKING.set(false);
            TRACKED_PARAMS.get().clear();
        }
    }
}
