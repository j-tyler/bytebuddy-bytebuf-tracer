/*
 * Copyright 2025 Justin Marsh
 * SPDX-License-Identifier: Apache-2.0
 */

package com.example.bytebuf.tracker.integration.testapp;

import io.netty.buffer.ByteBuf;

/**
 * Helper class that can optionally be excluded by specific class name.
 * When excluded via agent config, methods in this class should NOT appear in the flow tree.
 * When not excluded, methods will be tracked normally.
 */
public class OptionallyExcludedHelper {

    public static ByteBuf processInExcludedClass(ByteBuf buffer) {
        System.out.println("OptionallyExcludedHelper.processInExcludedClass: This should NOT be tracked when excluded");
        byte[] data = new byte[buffer.readableBytes()];
        buffer.getBytes(0, data);
        return buffer;
    }

    public static ByteBuf anotherExcludedMethod(ByteBuf buffer) {
        System.out.println("OptionallyExcludedHelper.anotherExcludedMethod: This should NOT be tracked when excluded");
        return buffer;
    }
}
