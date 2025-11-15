/*
 * Copyright 2025 Justin Marsh
 * SPDX-License-Identifier: Apache-2.0
 */

package com.example.bytebuf.tracker;

import com.example.bytebuf.api.tracker.ObjectTrackerHandler;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Registry for object tracker handlers with multi-handler support.
 *
 * <p><b>Design:</b> ByteBuf tracking uses optimized fast-path advice with zero allocations.
 * Custom object tracking uses generic advice with Object[] allocation (acceptable for rare calls).
 *
 * <p><b>Handler Types:</b>
 * <ul>
 *   <li><b>ByteBuf Handler:</b> Always active, uses optimized advice (no allocations)</li>
 *   <li><b>Custom Handlers:</b> Optional, use generic advice (allocates Object[])</li>
 * </ul>
 *
 * <p><b>Registration:</b>
 * Handlers must be registered at build/launch time only:
 * <ul>
 *   <li>ByteBuf handler is always available (default)</li>
 *   <li>Custom handlers registered during agent premain() via system property</li>
 *   <li>System property: {@code -Dobject.tracker.handlers=com.example.Handler1,com.example.Handler2}</li>
 *   <li><b>No runtime registration allowed</b> - handlers must be known before instrumentation</li>
 * </ul>
 *
 * <p><b>Rationale:</b> The agent analyzes methods at instrumentation time to determine
 * which advice to apply. Runtime registration would be too late - classes would already
 * be instrumented with the wrong advice.
 *
 * <p><b>Thread Safety:</b> This class is thread-safe. Handlers are stored in a CopyOnWriteArrayList.
 */
public class ObjectTrackerRegistry {

    // ByteBuf handler (always active, fast path)
    private static final ObjectTrackerHandler BYTEBUF_HANDLER = new ByteBufObjectHandler();

    // Custom handlers (rare, slow path is acceptable)
    // Registered during agent premain() only
    private static final CopyOnWriteArrayList<ObjectTrackerHandler> CUSTOM_HANDLERS =
        new CopyOnWriteArrayList<>();

    /**
     * Get the ByteBuf handler (optimized fast path).
     * This handler is always available and uses zero-allocation advice.
     *
     * @return the ByteBuf handler (never null)
     */
    public static ObjectTrackerHandler getByteBufHandler() {
        return BYTEBUF_HANDLER;
    }

    /**
     * Get all custom handlers (generic slow path).
     * Custom handlers use Object[] allocation, but this is acceptable since custom
     * object tracking is rare compared to ByteBuf operations.
     *
     * @return unmodifiable list of custom handlers (never null, may be empty)
     */
    public static List<ObjectTrackerHandler> getCustomHandlers() {
        return Collections.unmodifiableList(CUSTOM_HANDLERS);
    }

    /**
     * Register a custom handler for tracking additional object types.
     *
     * <p><b>IMPORTANT:</b> This must be called during agent premain() BEFORE instrumentation.
     * Runtime registration is not supported because the agent analyzes methods at
     * instrumentation time to determine which advice to apply.
     *
     * <p>This method is called by {@code ByteBufFlowAgent.loadCustomHandlers()} when
     * processing the {@code -Dobject.tracker.handlers} system property.
     *
     * @param handler the custom handler to register
     * @throws IllegalArgumentException if handler is null
     */
    public static void registerCustomHandler(ObjectTrackerHandler handler) {
        if (handler == null) {
            throw new IllegalArgumentException("Handler cannot be null");
        }
        CUSTOM_HANDLERS.add(handler);
        System.out.println("[ObjectTrackerRegistry] Registered custom handler: " +
            handler.getClass().getName() + " tracking " + handler.getObjectType());
    }

    /**
     * Reset to default state (for testing only).
     * Clears all custom handlers but keeps the ByteBuf handler.
     */
    public static void resetToDefault() {
        CUSTOM_HANDLERS.clear();
    }

    /**
     * Clear all custom handlers (for testing only).
     * Alias for {@link #resetToDefault()}.
     */
    public static void clearCustomHandlers() {
        CUSTOM_HANDLERS.clear();
    }
}
