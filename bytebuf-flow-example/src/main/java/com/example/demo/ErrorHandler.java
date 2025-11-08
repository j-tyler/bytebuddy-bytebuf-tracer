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
 * Example error handler that may receive ByteBufs during error handling.
 * This class will be instrumented by the ByteBuddy agent.
 */
public class ErrorHandler {

    /**
     * Handle an error that occurred while processing a ByteBuf
     */
    public void handleError(ByteBuf message, Exception error) {
        // Log the error with message details
        logError(message, error);

        // Optionally save error context
        saveErrorContext(message, error);
    }

    /**
     * Log error details
     */
    private void logError(ByteBuf message, Exception error) {
        String details = String.format(
            "Error processing message: %s (readable bytes: %d, refCnt: %d)",
            error.getMessage(),
            message.readableBytes(),
            message.refCnt()
        );
        System.err.println(details);
    }

    /**
     * Save error context for debugging
     */
    private void saveErrorContext(ByteBuf message, Exception error) {
        // In real application, might save to error tracking system
        // For demo, we just track the call
    }
}
