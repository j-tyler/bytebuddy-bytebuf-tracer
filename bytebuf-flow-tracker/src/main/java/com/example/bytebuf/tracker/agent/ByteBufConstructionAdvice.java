package com.example.bytebuf.tracker.agent;

import com.example.bytebuf.tracker.ByteBufFlowTracker;
import com.example.bytebuf.tracker.ObjectTrackerHandler;
import com.example.bytebuf.tracker.ObjectTrackerRegistry;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.implementation.bytecode.assign.Assigner;

/**
 * ByteBuddy advice for tracking ByteBuf construction/allocation.
 * This advice intercepts ByteBuf factory methods to make allocation sites
 * the root nodes in the flow trie.
 *
 * Key features:
 * - Makes ByteBuf construction deterministic roots (vs first-touch)
 * - Provides immediate visibility into allocation type (heap, direct, wrapped, etc.)
 * - Improves leak diagnosis by showing allocation method
 *
 * Instrumented methods include:
 * - Unpooled.buffer/directBuffer/wrappedBuffer/copiedBuffer/compositeBuffer
 * - ByteBufAllocator.buffer/directBuffer/ioBuffer/heapBuffer/compositeBuffer
 */
public class ByteBufConstructionAdvice {

    // Re-entrance guard to prevent infinite recursion
    // Must be public for instrumented classes to access
    public static final ThreadLocal<Boolean> IS_TRACKING =
        ThreadLocal.withInitial(() -> false);

    /**
     * Method exit advice - tracks newly constructed ByteBuf objects
     * This runs after the factory method completes and returns a ByteBuf
     */
    @Advice.OnMethodExit(onThrowable = Throwable.class)
    public static void onConstructionExit(
            @Advice.Origin Class<?> clazz,
            @Advice.Origin("#m") String methodName,
            @Advice.Return(typing = Assigner.Typing.DYNAMIC) Object returnValue,
            @Advice.Thrown Throwable thrown) {

        // Prevent re-entrant calls
        if (IS_TRACKING.get()) {
            return;
        }

        // If the method threw an exception, don't track
        if (thrown != null) {
            return;
        }

        // If the method didn't return anything, don't track
        if (returnValue == null) {
            return;
        }

        try {
            IS_TRACKING.set(true);
            ObjectTrackerHandler handler = ObjectTrackerRegistry.getHandler();
            ByteBufFlowTracker tracker = ByteBufFlowTracker.getInstance();

            // Check if this is a trackable object (ByteBuf)
            if (handler.shouldTrack(returnValue)) {
                int metric = handler.getMetric(returnValue);

                // Record this as the first touch (construction becomes root)
                tracker.recordMethodCall(
                    returnValue,
                    clazz.getSimpleName(),
                    methodName,
                    metric
                );
            }
        } finally {
            IS_TRACKING.set(false);
        }
    }
}
