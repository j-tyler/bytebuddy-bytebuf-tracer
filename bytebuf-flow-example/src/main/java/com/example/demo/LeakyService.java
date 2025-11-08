/*
 * Copyright 2025 Justin Marsh
 * SPDX-License-Identifier: Apache-2.0
 */

package com.example.demo;

import io.netty.buffer.ByteBuf;

/**
 * Example of a service that leaks ByteBufs.
 * This demonstrates how the tracker can detect memory leaks.
 */
public class LeakyService {

    /**
     * This method receives a ByteBuf but forgets to release it.
     * The ByteBuf Flow Tracker will detect this as a leak.
     */
    public void forgetsToRelease(ByteBuf buffer) {
        // Read some data
        processData(buffer);

        // BUG: We should release the buffer here, but we forget!
        // buffer.release(); // <-- This line is missing!

        // The tracker will show this method as a leaf node with refCount=1
        // indicating a memory leak
    }

    /**
     * Process data from the buffer
     */
    private void processData(ByteBuf buffer) {
        // Simulate reading data
        if (buffer.readableBytes() > 0) {
            byte[] data = new byte[buffer.readableBytes()];
            buffer.getBytes(0, data);
            // Do something with data
        }
    }
}
