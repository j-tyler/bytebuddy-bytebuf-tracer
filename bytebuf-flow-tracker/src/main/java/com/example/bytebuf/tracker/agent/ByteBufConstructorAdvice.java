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

    // Re-entrance guard to prevent infinite recursion when tracking code
    // triggers other instrumented methods
    // Must be public for instrumented classes to access
    public static final ThreadLocal<Boolean> IS_TRACKING =
        ThreadLocal.withInitial(() -> false);

    /**
     * Constructor entry advice - tracks objects in parameters
     *
     * <p><b>Memory optimization:</b> Uses runtime {@code getSimpleName()} to generate
     * short method signatures for constructor calls.
     */
    @Advice.OnMethodEnter
    public static void onConstructorEnter(
            @Advice.Origin Class<?> clazz,
            @Advice.AllArguments Object[] arguments) {

        // Prevent re-entrant calls
        if (IS_TRACKING.get()) {
            return;
        }

        if (arguments == null || arguments.length == 0) {
            return;
        }

        try {
            IS_TRACKING.set(true);
            ObjectTrackerHandler handler = ObjectTrackerRegistry.getHandler();
            ByteBufFlowTracker tracker = ByteBufFlowTracker.getInstance();

            // Build short method signature at runtime
            String methodSignature = clazz.getSimpleName() + ".<init>";

            for (Object arg : arguments) {
                if (handler.shouldTrack(arg)) {
                    int metric = handler.getMetric(arg);
                    tracker.recordMethodCall(
                        arg,
                        methodSignature,  // Short signature (simple class name)
                        metric
                    );
                }
            }
        } finally {
            IS_TRACKING.set(false);
        }
    }

    /**
     * Constructor exit advice - tracks parameter state after constructor completes
     * Uses _return suffix consistently (exit = return)
     *
     * <p><b>Memory optimization:</b> Uses runtime {@code getSimpleName()} to generate
     * short method signatures with "_return" suffix.
     */
    @Advice.OnMethodExit
    public static void onConstructorExit(
            @Advice.Origin Class<?> clazz,
            @Advice.AllArguments Object[] arguments) {

        // Prevent re-entrant calls
        if (IS_TRACKING.get()) {
            return;
        }

        try {
            IS_TRACKING.set(true);
            ObjectTrackerHandler handler = ObjectTrackerRegistry.getHandler();
            ByteBufFlowTracker tracker = ByteBufFlowTracker.getInstance();

            // Build short method signature at runtime with _return suffix
            String methodSignatureReturn = clazz.getSimpleName() + ".<init>_return";

            // Track parameter state on exit with _return suffix (exit = return)
            if (arguments != null) {
                for (Object arg : arguments) {
                    if (handler.shouldTrack(arg)) {
                        int metric = handler.getMetric(arg);
                        tracker.recordMethodCall(
                            arg,
                            methodSignatureReturn,  // Short signature (simple class name)
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
