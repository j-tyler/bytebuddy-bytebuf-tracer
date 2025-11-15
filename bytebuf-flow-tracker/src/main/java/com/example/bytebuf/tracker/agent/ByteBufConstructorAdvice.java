/*
 * Copyright 2025 Justin Marsh
 * SPDX-License-Identifier: Apache-2.0
 */

package com.example.bytebuf.tracker.agent;

import com.example.bytebuf.tracker.ByteBufFlowTracker;
import com.example.bytebuf.api.tracker.ObjectTrackerHandler;
import com.example.bytebuf.tracker.ObjectTrackerRegistry;
import net.bytebuddy.asm.Advice;

/**
 * ByteBuddy advice for tracking object flow through constructors.
 * This is separate from ByteBufTrackingAdvice because constructors have special constraints:
 *
 * - Cannot use onThrowable = Throwable.class (would wrap code before super() call)
 * - JVM bytecode verifier requires super()/this() to be called first
 * - Exception handlers can only exist AFTER super/this call completes
 *
 * Trade-off: We don't track exceptions during construction, but this is acceptable because:
 * - Exceptions during construction are rare and typically fatal
 * - We still track entry and exit states, which is the primary goal
 */
public class ByteBufConstructorAdvice {

    // Re-entrance guard: prevents infinite recursion when tracking code triggers instrumented methods
    // Must be public for instrumented classes to access
    public static final ThreadLocal<Boolean> IS_TRACKING =
        ThreadLocal.withInitial(() -> false);

    // WHY AdviceCacheAccess: ByteBuddy inlines this advice code directly into instrumented
    // classes. Inlined code executes in the target class's security context, requiring
    // public access to any fields. AdviceCacheAccess provides controlled public methods
    // instead of exposing raw ConcurrentHashMap collections (which could be corrupted via
    // external .clear() or .put() calls). See AdviceCacheAccess javadoc for details.

    /**
     * Tracks objects flowing into constructors (as constructor arguments).
     */
    @Advice.OnMethodEnter
    public static void onConstructorEnter(
            @Advice.Origin("#t") String className,
            @Advice.Origin("#t.<init>") String constructorSignature,
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

            for (Object arg : arguments) {
                if (handler.shouldTrack(arg)) {
                    int metric = handler.getMetric(arg);
                    tracker.recordMethodCall(
                        arg,
                        className,
                        "<init>",
                        constructorSignature,
                        metric
                    );
                }
            }
        } finally {
            IS_TRACKING.set(false);
        }
    }

    /**
     * Tracks constructor arguments after construction completes.
     * The _return suffix maintains consistency with regular method exit tracking.
     */
    @Advice.OnMethodExit
    public static void onConstructorExit(
            @Advice.Origin("#t") String className,
            @Advice.Origin("#t.<init>") String constructorSignature,
            @Advice.AllArguments Object[] arguments) {

        if (IS_TRACKING.get()) {
            return;
        }

        try {
            IS_TRACKING.set(true);
            ObjectTrackerHandler handler = ObjectTrackerRegistry.getByteBufHandler();
            ByteBufFlowTracker tracker = ByteBufFlowTracker.getInstance();

            if (arguments != null) {
                // Cache "_return" suffix to avoid string concatenation on hot path
                String constructorSignatureReturn = AdviceCacheAccess.getOrComputeConstructorSignatureReturn(constructorSignature);

                for (Object arg : arguments) {
                    if (handler.shouldTrack(arg)) {
                        int metric = handler.getMetric(arg);
                        tracker.recordMethodCall(
                            arg,
                            className,
                            "<init>_return",
                            constructorSignatureReturn,
                            metric
                        );
                    }
                }
            }
        } finally {
            IS_TRACKING.set(false);
        }
    }
}
