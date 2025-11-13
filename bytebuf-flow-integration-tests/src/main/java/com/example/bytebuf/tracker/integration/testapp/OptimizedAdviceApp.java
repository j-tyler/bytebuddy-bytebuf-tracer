/*
 * Copyright 2025 Justin Marsh
 * SPDX-License-Identifier: Apache-2.0
 */

package com.example.bytebuf.tracker.integration.testapp;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

/**
 * Test application that exercises all optimized advice classes.
 * This validates that specialized advice works correctly for:
 * - 0-param methods returning ByteBuf (ZeroParamByteBufReturnAdvice)
 * - 1-param methods with ByteBuf (SingleByteBufParamAdvice)
 * - 2-param methods (ByteBuf, X) (TwoParamByteBufAt0Advice)
 * - 2-param methods (X, ByteBuf) (TwoParamByteBufAt1Advice)
 * - 2-param methods (ByteBuf, ByteBuf) (TwoParamBothByteBufAdvice)
 */
public class OptimizedAdviceApp {

    public static void main(String[] args) {
        System.out.println("=== Optimized Advice Test App ===");

        OptimizedAdviceApp app = new OptimizedAdviceApp();
        app.runTest();

        System.out.println("=== Test Complete ===");
    }

    public void runTest() {
        // Test 0-param advice: factory method returning ByteBuf
        ByteBuf buffer = createBuffer();
        System.out.println("Created buffer via 0-param method");

        // Test 1-param advice: single ByteBuf parameter
        processBuffer(buffer);
        System.out.println("Processed buffer via 1-param method");

        // Test 2-param advice (ByteBuf, X): ByteBuf at position 0
        logBuffer(buffer, "test message");
        System.out.println("Logged buffer via 2-param method (ByteBuf, String)");

        // Test 2-param advice (X, ByteBuf): ByteBuf at position 1
        handleError(new RuntimeException("test error"), buffer);
        System.out.println("Handled error via 2-param method (Exception, ByteBuf)");

        // Test 2-param advice (ByteBuf, ByteBuf): both parameters are ByteBuf
        ByteBuf buffer2 = createBuffer();
        mergeBuffers(buffer, buffer2);
        System.out.println("Merged buffers via 2-param method (ByteBuf, ByteBuf)");

        // Clean up
        if (buffer.refCnt() > 0) {
            buffer.release();
        }
        if (buffer2.refCnt() > 0) {
            buffer2.release();
        }
        System.out.println("Released all buffers");
    }

    /**
     * 0-param method returning ByteBuf.
     * Should use ZeroParamByteBufReturnAdvice.
     */
    public ByteBuf createBuffer() {
        return Unpooled.buffer(256);
    }

    /**
     * 1-param method with ByteBuf.
     * Should use SingleByteBufParamAdvice.
     */
    public void processBuffer(ByteBuf buffer) {
        buffer.writeByte(42);
    }

    /**
     * 2-param method: (ByteBuf, X) - ByteBuf at position 0.
     * Should use TwoParamByteBufAt0Advice.
     */
    public void logBuffer(ByteBuf buffer, String message) {
        int readable = buffer.readableBytes();
        System.out.println("Buffer " + message + " has " + readable + " readable bytes");
    }

    /**
     * 2-param method: (X, ByteBuf) - ByteBuf at position 1.
     * Should use TwoParamByteBufAt1Advice.
     */
    public void handleError(Exception error, ByteBuf buffer) {
        System.out.println("Error: " + error.getMessage() + " with buffer refCnt=" + buffer.refCnt());
    }

    /**
     * 2-param method: (ByteBuf, ByteBuf) - both parameters are ByteBuf.
     * Should use TwoParamBothByteBufAdvice.
     */
    public void mergeBuffers(ByteBuf buffer1, ByteBuf buffer2) {
        buffer1.writeBytes(buffer2, buffer2.readerIndex(), buffer2.readableBytes());
    }
}
