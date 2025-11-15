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
 * <ul>
 *   <li>ByteBuf handler is always available (default)</li>
 *   <li>Custom handlers must be registered during agent premain() via system property</li>
 *   <li>System property: {@code -Dobject.tracker.handlers=com.example.Handler1,com.example.Handler2}</li>
 * </ul>
 *
 * <p><b>Thread Safety:</b> This class is thread-safe. Handlers are stored in a CopyOnWriteArrayList.
 */
public class ObjectTrackerRegistry {

    // ByteBuf handler (always active, fast path)
    private static final ObjectTrackerHandler BYTEBUF_HANDLER = new ByteBufObjectHandler();

    // Custom handlers (rare, slow path is acceptable)
    private static final CopyOnWriteArrayList<ObjectTrackerHandler> CUSTOM_HANDLERS =
        new CopyOnWriteArrayList<>();

    // Legacy single-handler support (deprecated but maintained for compatibility)
    private static volatile ObjectTrackerHandler legacyHandler = null;
    private static final Object LOCK = new Object();

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
     * This should be called during agent premain() before instrumentation.
     *
     * <p><b>Important:</b> Handlers must be registered BEFORE classes are instrumented.
     * The agent analyzes methods at instrumentation time to determine which advice to apply.
     *
     * @param handler the custom handler to register
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
     * Find the appropriate handler for a given object.
     * Checks ByteBuf handler first (fast path), then custom handlers.
     *
     * @param obj the object to find a handler for
     * @return the matching handler, or null if no handler matches
     */
    public static ObjectTrackerHandler findHandler(Object obj) {
        if (obj == null) {
            return null;
        }

        // Try ByteBuf first (fast path, most common)
        if (BYTEBUF_HANDLER.shouldTrack(obj)) {
            return BYTEBUF_HANDLER;
        }

        // Try custom handlers
        for (ObjectTrackerHandler handler : CUSTOM_HANDLERS) {
            if (handler.shouldTrack(obj)) {
                return handler;
            }
        }

        // Try legacy handler for backwards compatibility
        if (legacyHandler != null && legacyHandler.shouldTrack(obj)) {
            return legacyHandler;
        }

        return null;
    }

    /**
     * Get the current handler, creating default if none set.
     * <b>DEPRECATED:</b> Use {@link #getByteBufHandler()} or {@link #getCustomHandlers()} instead.
     * Maintained for backwards compatibility with existing advice code.
     */
    public static ObjectTrackerHandler getHandler() {
        // If legacy handler is set, use it (backwards compatibility)
        if (legacyHandler != null) {
            return legacyHandler;
        }

        // Otherwise use ByteBuf handler as default
        if (legacyHandler == null) {
            synchronized (LOCK) {
                if (legacyHandler == null) {
                    // Try to load from system property first (old single-handler mode)
                    String handlerClassName = System.getProperty("object.tracker.handler");
                    if (handlerClassName != null && !handlerClassName.isEmpty()) {
                        try {
                            Class<?> handlerClass = Class.forName(handlerClassName);
                            legacyHandler = (ObjectTrackerHandler) handlerClass.getDeclaredConstructor().newInstance();
                            System.out.println("[ObjectTrackerRegistry] Loaded legacy custom handler: " + handlerClassName);
                        } catch (Exception e) {
                            System.err.println("[ObjectTrackerRegistry] Failed to load legacy handler: " + handlerClassName);
                            e.printStackTrace();
                            legacyHandler = BYTEBUF_HANDLER; // Fall back to default
                        }
                    } else {
                        // Use default ByteBuf handler
                        legacyHandler = BYTEBUF_HANDLER;
                    }
                }
            }
        }
        return legacyHandler;
    }

    /**
     * Set a custom handler for tracking objects.
     * <b>DEPRECATED:</b> Use {@link #registerCustomHandler(ObjectTrackerHandler)} instead.
     * Maintained for backwards compatibility.
     *
     * @param customHandler Your custom handler implementation
     */
    public static void setHandler(ObjectTrackerHandler customHandler) {
        synchronized (LOCK) {
            legacyHandler = customHandler;
            System.out.println("[ObjectTrackerRegistry] Legacy custom handler set: " +
                customHandler.getClass().getName() + " tracking " + customHandler.getObjectType());
        }
    }

    /**
     * Reset to default handler (mainly for testing).
     */
    public static void resetToDefault() {
        synchronized (LOCK) {
            legacyHandler = BYTEBUF_HANDLER;
            CUSTOM_HANDLERS.clear();
        }
    }

    /**
     * Clear all custom handlers (for testing).
     */
    public static void clearCustomHandlers() {
        CUSTOM_HANDLERS.clear();
    }
}
