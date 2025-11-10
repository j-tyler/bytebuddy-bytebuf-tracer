/*
 * Copyright 2025 Justin Marsh
 * SPDX-License-Identifier: Apache-2.0
 */

package com.example.bytebuf.tracker.integration.testapp.excluded;

import io.netty.buffer.ByteBuf;

/**
 * Helper class in an excluded package.
 * When the package is excluded, methods in this class should NOT appear in the flow tree.
 */
public class ExcludedPackageHelper {

    public static ByteBuf processInExcludedPackage(ByteBuf buffer) {
        System.out.println("ExcludedPackageHelper.processInExcludedPackage: This should NOT be tracked when package is excluded");

        // Call another method in the same excluded package
        buffer = additionalProcessing(buffer);

        return buffer;
    }

    public static ByteBuf additionalProcessing(ByteBuf buffer) {
        System.out.println("ExcludedPackageHelper.additionalProcessing: This should NOT be tracked when package is excluded");
        return buffer;
    }
}
