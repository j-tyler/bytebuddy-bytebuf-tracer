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
