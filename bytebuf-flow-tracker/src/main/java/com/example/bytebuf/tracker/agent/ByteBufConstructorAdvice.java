package com.example.bytebuf.tracker.agent;

import com.example.bytebuf.tracker.ByteBufFlowTracker;
import com.example.bytebuf.tracker.ObjectTrackerHandler;
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
    private static final ThreadLocal<Boolean> IS_TRACKING =
        ThreadLocal.withInitial(() -> false);

    /**
     * Constructor entry advice - tracks objects in parameters
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

            for (Object arg : arguments) {
                if (handler.shouldTrack(arg)) {
                    int metric = handler.getMetric(arg);
                    tracker.recordMethodCall(
                        arg,
                        clazz.getSimpleName(),
                        "<init>",
                        metric
                    );
                }
            }
        } finally {
            IS_TRACKING.set(false);
        }
    }

    /**
     * Constructor exit advice - tracks final state of objects in parameters
     *
     * IMPORTANT: Does NOT use onThrowable parameter because:
     * - Constructors cannot have exception handlers before super() call
     * - JVM bytecode verifier would reject the instrumented class
     * - This is a necessary trade-off for constructor tracking
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

            // Check if any tracked objects in parameters have changed state
            if (arguments != null) {
                for (Object arg : arguments) {
                    if (handler.shouldTrack(arg)) {
                        int metric = handler.getMetric(arg);
                        // Record the exit state
                        tracker.recordMethodCall(
                            arg,
                            clazz.getSimpleName(),
                            "<init>_exit",
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
