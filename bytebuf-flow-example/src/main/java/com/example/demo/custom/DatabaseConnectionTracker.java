/*
 * Copyright 2025 Justin Marsh
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.example.demo.custom;

import com.example.bytebuf.tracker.ObjectTrackerHandler;

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
