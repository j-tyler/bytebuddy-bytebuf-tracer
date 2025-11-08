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

import java.io.RandomAccessFile;

/**
 * Example custom handler for tracking file handles.
 *
 * This demonstrates how to track objects other than ByteBuf.
 * The tracker will monitor RandomAccessFile instances and report
 * if any are not properly closed (leak detection).
 */
public class FileHandleTracker implements ObjectTrackerHandler {

    @Override
    public boolean shouldTrack(Object obj) {
        // Track RandomAccessFile instances
        return obj instanceof RandomAccessFile;
    }

    @Override
    public int getMetric(Object obj) {
        if (obj instanceof RandomAccessFile) {
            RandomAccessFile file = (RandomAccessFile) obj;
            try {
                // Try to get file descriptor - if this throws, file is closed
                file.getFD();
                return 1; // File is open
            } catch (Exception e) {
                return 0; // File is closed
            }
        }
        return 0;
    }

    @Override
    public String getObjectType() {
        return "FileHandle";
    }
}
