/*
 * Copyright 2025 Justin Marsh
 * SPDX-License-Identifier: Apache-2.0
 */

package com.example.demo.custom;

import com.example.bytebuf.api.tracker.ObjectTrackerHandler;

import java.sql.Connection;

/**
 * Example custom handler for tracking database connections.
 *
 * This demonstrates how to track Connection objects to detect
 * connection leaks - connections that are never closed.
 */
public class DatabaseConnectionTracker implements ObjectTrackerHandler {

    @Override
    public boolean shouldTrack(Object obj) {
        // Track JDBC Connection instances
        return obj instanceof Connection;
    }

    @Override
    public int getMetric(Object obj) {
        if (obj instanceof Connection) {
            Connection conn = (Connection) obj;
            try {
                return conn.isClosed() ? 0 : 1;
            } catch (Exception e) {
                // If we can't check, assume closed
                return 0;
            }
        }
        return 0;
    }

    @Override
    public String getObjectType() {
        return "DatabaseConnection";
    }
}
