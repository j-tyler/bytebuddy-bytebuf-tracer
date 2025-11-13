/*
 * Copyright 2025 Justin Marsh
 * SPDX-License-Identifier: Apache-2.0
 */

package com.example.bytebuf.tracker.agent;

import com.example.bytebuf.tracker.ByteBufFlowTracker;
import com.example.bytebuf.api.tracker.ObjectTrackerHandler;
import com.example.bytebuf.tracker.ObjectTrackerRegistry;
import net.bytebuddy.asm.Advice;

import java.util.concurrent.ConcurrentHashMap;

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

    // OPTIMIZATION: Cache for "_return" suffixes to avoid string concatenation on hot path
    // @Advice.Origin provides constants, so we can safely cache the concatenated results
    // Initial capacity 128 assumes ~85-100 unique tracked constructors (load factor 0.75)
    private static final ConcurrentHashMap<String, String> CONSTRUCTOR_SIGNATURE_RETURN_CACHE = new ConcurrentHashMap<>(128);

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
            ObjectTrackerHandler handler = ObjectTrackerRegistry.getHandler();
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
            ObjectTrackerHandler handler = ObjectTrackerRegistry.getHandler();
            ByteBufFlowTracker tracker = ByteBufFlowTracker.getInstance();

            if (arguments != null) {
                // OPTIMIZATION: Use cached "_return" suffix to avoid allocation
                // Double-checked pattern: get() first (lock-free), then putIfAbsent() if needed
                // This avoids holding locks during string concatenation
                String constructorSignatureReturn = CONSTRUCTOR_SIGNATURE_RETURN_CACHE.get(constructorSignature);
                if (constructorSignatureReturn == null) {
                    String computed = constructorSignature + "_return";
                    constructorSignatureReturn = CONSTRUCTOR_SIGNATURE_RETURN_CACHE.putIfAbsent(constructorSignature, computed);
                    if (constructorSignatureReturn == null) constructorSignatureReturn = computed;
                }

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
