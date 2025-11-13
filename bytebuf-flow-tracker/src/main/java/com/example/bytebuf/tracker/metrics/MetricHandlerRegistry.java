/*
 * Copyright 2025 Justin Marsh
 * SPDX-License-Identifier: Apache-2.0
 */

package com.example.bytebuf.tracker.metrics;

import com.example.bytebuf.api.metrics.MetricHandler;
import com.example.bytebuf.api.metrics.MetricType;

import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Registry for metric handlers.
 * Thread-safe using CopyOnWriteArrayList (registration rare, iteration frequent).
 */
public class MetricHandlerRegistry {

    private static final List<MetricHandler> handlers = new CopyOnWriteArrayList<MetricHandler>();

    // Cache required metrics to avoid recomputation on every push (registration is rare, pushes are frequent)
    private static volatile Set<MetricType> cachedRequiredMetrics = Collections.emptySet();

    static {
        // Load handlers from system property
        loadFromSystemProperty();

        // Load handlers from ServiceLoader (SPI)
        loadFromServiceLoader();
    }

    /**
     * Register a handler programmatically.
     * @param handler Handler to register
     */
    public static synchronized void register(MetricHandler handler) {
        if (handler == null) {
            throw new IllegalArgumentException("Handler cannot be null");
        }
        invalidateCache();
        handlers.add(handler);
        System.out.println("[MetricHandlerRegistry] Registered: " + handler.getName());
    }

    /**
     * Unregister a handler.
     * @param handler Handler to remove
     */
    public static synchronized void unregister(MetricHandler handler) {
        invalidateCache();
        handlers.remove(handler);
    }

    /**
     * Get all registered handlers.
     * @return Immutable list of handlers
     */
    public static List<MetricHandler> getHandlers() {
        return Collections.unmodifiableList(handlers);
    }

    /**
     * Get aggregated required metrics across all handlers.
     * Cached for performance (cleared on registration changes).
     * @return Set of metric types needed by at least one handler
     */
    public static Set<MetricType> getRequiredMetrics() {
        Set<MetricType> cached = cachedRequiredMetrics;
        if (cached.isEmpty() && !handlers.isEmpty()) {
            // Rebuild cache
            synchronized (MetricHandlerRegistry.class) {
                cached = cachedRequiredMetrics;
                if (cached.isEmpty() && !handlers.isEmpty()) {
                    Set<MetricType> required = EnumSet.noneOf(MetricType.class);
                    for (MetricHandler handler : handlers) {
                        Set<MetricType> handlerMetrics = handler.getRequiredMetrics();
                        if (handlerMetrics != null) {
                            required.addAll(handlerMetrics);
                        }
                    }
                    cachedRequiredMetrics = Collections.unmodifiableSet(required);
                    cached = cachedRequiredMetrics;
                }
            }
        }
        return cached;
    }

    /**
     * Check if any handler is registered.
     * @return true if at least one handler is registered
     */
    public static boolean hasHandlers() {
        return !handlers.isEmpty();
    }

    private static void invalidateCache() {
        cachedRequiredMetrics = Collections.emptySet();
    }

    // Load from -Dmetric.handlers=com.example.Handler1,com.example.Handler2
    private static void loadFromSystemProperty() {
        String prop = System.getProperty("metric.handlers");
        if (prop == null || prop.trim().isEmpty()) {
            return;
        }

        for (String className : prop.split(",")) {
            try {
                Class<?> clazz = Class.forName(className.trim());
                Object instance = clazz.getDeclaredConstructor().newInstance();
                if (!(instance instanceof MetricHandler)) {
                    System.err.println("[MetricHandlerRegistry] Not a MetricHandler: " + className);
                    continue;
                }
                MetricHandler handler = (MetricHandler) instance;
                handlers.add(handler);
                System.out.println("[MetricHandlerRegistry] Loaded from system property: " + handler.getName());
            } catch (Exception e) {
                System.err.println("[MetricHandlerRegistry] Failed to load handler: " + className);
                e.printStackTrace();
            }
        }
    }

    // Load from META-INF/services/com.example.bytebuf.api.metrics.MetricHandler
    private static void loadFromServiceLoader() {
        try {
            ServiceLoader<MetricHandler> loader = ServiceLoader.load(MetricHandler.class);
            for (MetricHandler handler : loader) {
                handlers.add(handler);
                System.out.println("[MetricHandlerRegistry] Loaded via ServiceLoader: " + handler.getName());
            }
        } catch (Exception e) {
            System.err.println("[MetricHandlerRegistry] ServiceLoader failed");
            e.printStackTrace();
        }
    }
}
