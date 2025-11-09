/*
 * Copyright 2025 Justin Marsh
 * SPDX-License-Identifier: Apache-2.0
 */

package com.example.demo;

import io.netty.buffer.ByteBuf;

/**
 * Service that leaks DIRECT buffers (CRITICAL - never GC'd!)
 * This demonstrates the difference between direct and heap buffer leaks.
 */
public class DirectLeakyService {

    /**
     * Forgets to release a direct buffer - CRITICAL leak!
     * Direct memory is NEVER garbage collected, so this is a permanent leak.
     * Will show as: 🚨 LEAK
     */
    public void forgetsToReleaseDirect(ByteBuf buffer) {
        // Read some data from the buffer
        int readable = buffer.readableBytes();
        System.out.println("Processing " + readable + " bytes from direct buffer...");

        // BUG: Forgot to call buffer.release()!
        // This is CRITICAL because direct memory is never GC'd
        // The tracker will show this with 🚨 LEAK emoji
    }
}
