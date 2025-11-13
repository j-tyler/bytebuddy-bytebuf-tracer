/*
 * Copyright 2025 Justin Marsh
 * SPDX-License-Identifier: Apache-2.0
 */

package com.example.bytebuf.tracker.agent;

import com.example.bytebuf.tracker.ByteBufFlowTracker;
import com.example.bytebuf.api.tracker.ObjectTrackerHandler;
import com.example.bytebuf.tracker.ObjectTrackerRegistry;
import io.netty.buffer.ByteBuf;
import net.bytebuddy.asm.Advice;

/**
 * ByteBuddy advice specifically for tracking ByteBuf lifecycle methods.
 * This advice tracks release() and retain() calls to accurately monitor
 * when ByteBufs are actually deallocated.
 *
 * Key features:
 * - Tracks release() only when it causes refCnt to drop to 0
 * - Avoids muddling the tree with intermediate retain/release calls
 * - Provides clear indication of properly released vs leaked ByteBufs
 */
public class ByteBufLifecycleAdvice {

    // Re-entrance guard: prevents infinite recursion when tracking code triggers instrumented methods
    // Must be public for instrumented classes to access
    public static final ThreadLocal<Boolean> IS_TRACKING =
        ThreadLocal.withInitial(() -> false);

    // Stores refCnt before lifecycle method call to detect when release() drops refCnt to 0
    // Must be public for instrumented classes to access
    public static final ThreadLocal<Integer> BEFORE_REF_COUNT =
        ThreadLocal.withInitial(() -> 0);

    /**
     * Captures refCnt before lifecycle method executes (to detect when release() deallocates).
     */
    @Advice.OnMethodEnter
    public static void onMethodEnter(
            @Advice.This Object thiz,
            @Advice.Origin("#m") String methodName) {

        if (IS_TRACKING.get()) {
            return;
        }

        try {
            IS_TRACKING.set(true);
            ObjectTrackerHandler handler = ObjectTrackerRegistry.getHandler();

            if (handler.shouldTrack(thiz)) {
                int refCount = handler.getMetric(thiz);
                BEFORE_REF_COUNT.set(refCount);
            }
        } finally {
            IS_TRACKING.set(false);
        }
    }

    /**
     * Tracks lifecycle methods only when meaningful:
     * - release(): only when it drops refCnt to 0 (actual deallocation)
     * - retain(): always (shows refCnt increases)
     * Skips intermediate release() calls to keep Trie focused on final deallocation.
     */
    @Advice.OnMethodExit(onThrowable = Throwable.class)
    public static void onMethodExit(
            @Advice.This Object thiz,
            @Advice.Origin("#t") String originClassName,
            @Advice.Origin("#m") String methodName,
            @Advice.Origin("#t.#m") String methodSignature,
            @Advice.Thrown Throwable thrown) {

        if (IS_TRACKING.get()) {
            return;
        }

        try {
            IS_TRACKING.set(true);
            ObjectTrackerHandler handler = ObjectTrackerRegistry.getHandler();
            ByteBufFlowTracker tracker = ByteBufFlowTracker.getInstance();

            if (!handler.shouldTrack(thiz)) {
                return;
            }

            int beforeRefCount = BEFORE_REF_COUNT.get();
            int afterRefCount = handler.getMetric(thiz);

            boolean shouldTrack = false;

            if (methodName.equals("release")) {
                if (afterRefCount == 0) {
                    shouldTrack = true;
                }
            } else if (methodName.equals("retain")) {
                shouldTrack = true;
            }

            if (shouldTrack) {
                // Only track if object is already in a flow (prevents lifecycle methods from creating unwanted roots)
                if (!tracker.isTracking(thiz)) {
                    return;
                }

                // Use the instrumentation-time class name and method signature
                // OPTIMIZATION: Avoid runtime getClass().getSimpleName() allocation (40-80 bytes)
                // The originClassName from @Advice.Origin is pre-computed at instrumentation time
                tracker.recordMethodCall(
                    thiz,
                    originClassName,
                    methodName,
                    methodSignature,
                    afterRefCount
                );
            }
        } finally {
            IS_TRACKING.set(false);
            BEFORE_REF_COUNT.remove();
        }
    }
}
