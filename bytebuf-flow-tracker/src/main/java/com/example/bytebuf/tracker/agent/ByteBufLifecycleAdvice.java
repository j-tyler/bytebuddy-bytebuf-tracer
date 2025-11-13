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

    // Re-entrance guard to prevent infinite recursion
    // Must be public for instrumented classes to access
    public static final ThreadLocal<Boolean> IS_TRACKING =
        ThreadLocal.withInitial(() -> false);

    // ThreadLocal to store refCnt before the method call
    // Must be public for instrumented classes to access
    public static final ThreadLocal<Integer> BEFORE_REF_COUNT =
        ThreadLocal.withInitial(() -> 0);

    /**
     * Method entry advice - captures refCnt before release/retain
     */
    @Advice.OnMethodEnter
    public static void onMethodEnter(
            @Advice.This Object thiz,
            @Advice.Origin("#m") String methodName) {

        // Prevent re-entrant calls
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
     * Method exit advice - tracks only if refCnt changed to 0 (for release)
     * or tracks retain calls
     */
    @Advice.OnMethodExit(onThrowable = Throwable.class)
    public static void onMethodExit(
            @Advice.This Object thiz,
            @Advice.Origin("#t") String originClassName,
            @Advice.Origin("#m") String methodName,
            @Advice.Origin("#t.#m") String methodSignature,
            @Advice.Thrown Throwable thrown) {

        // Prevent re-entrant calls
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

            // Determine if we should track this call
            boolean shouldTrack = false;

            if (methodName.equals("release")) {
                // Only track release if it drops refCnt to 0
                if (afterRefCount == 0) {
                    shouldTrack = true;
                }
                // Note: We skip intermediate release calls that don't deallocate
            } else if (methodName.equals("retain")) {
                // Track retain calls to show refCnt increases
                // This helps understand the lifecycle
                shouldTrack = true;
            }

            if (shouldTrack) {
                // Only track lifecycle methods if the ByteBuf is already part of a flow
                // This prevents release() or retain() from creating unwanted roots
                if (!tracker.isTracking(thiz)) {
                    // ByteBuf not in any flow - skip tracking this lifecycle event
                    return;
                }

                // Get the actual ByteBuf class name if available
                // For lifecycle methods, we want to use the actual runtime class for better diagnostics
                String className = originClassName;
                String actualSignature = methodSignature;
                if (thiz instanceof ByteBuf) {
                    String actualClassName = thiz.getClass().getSimpleName();
                    className = actualClassName;
                    actualSignature = actualClassName + "." + methodName;
                }

                tracker.recordMethodCall(
                    thiz,
                    className,
                    methodName,
                    actualSignature,
                    afterRefCount
                );
            }
        } finally {
            IS_TRACKING.set(false);
            BEFORE_REF_COUNT.remove();
        }
    }
}
