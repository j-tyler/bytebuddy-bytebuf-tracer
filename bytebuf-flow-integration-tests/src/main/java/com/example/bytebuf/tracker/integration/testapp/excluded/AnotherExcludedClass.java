/*
 * Copyright 2025 Justin Marsh
 * SPDX-License-Identifier: Apache-2.0
 */

package com.example.bytebuf.tracker.integration.testapp.excluded;

import io.netty.buffer.ByteBuf;

/**
 * Another class in the excluded package.
 * Demonstrates that all classes in the package are excluded.
 */
public class AnotherExcludedClass {

    public static ByteBuf process(ByteBuf buffer) {
        System.out.println("AnotherExcludedClass.process: This should NOT be tracked when package is excluded");
        return buffer;
    }
}
