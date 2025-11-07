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
