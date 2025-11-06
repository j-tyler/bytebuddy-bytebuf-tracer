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
