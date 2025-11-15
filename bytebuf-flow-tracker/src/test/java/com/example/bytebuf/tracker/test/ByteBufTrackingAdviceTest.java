/*
 * Copyright 2025 Justin Marsh
 * SPDX-License-Identifier: Apache-2.0
 */

package com.example.bytebuf.tracker.test;

import com.example.bytebuf.tracker.agent.ByteBufTrackingAdvice;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for ByteBufTrackingAdvice, focusing on the toSimpleName() method.
 */
public class ByteBufTrackingAdviceTest {

    @Test
    public void testToSimpleName_fullyQualifiedName() {
        String fqn = "io.netty.buffer.UnpooledByteBufAllocator.directBuffer";
        String result = ByteBufTrackingAdvice.toSimpleName(fqn);

        assertEquals("UnpooledByteBufAllocator.directBuffer", result,
                     "Should extract simple class name from fully qualified name");
    }

    @Test
    public void testToSimpleName_deepPackageHierarchy() {
        String fqn = "com.example.project.module.submodule.MyClass.myMethod";
        String result = ByteBufTrackingAdvice.toSimpleName(fqn);

        assertEquals("MyClass.myMethod", result,
                     "Should extract simple name from deep package hierarchy");
    }

    @Test
    public void testToSimpleName_singleLevelPackage() {
        String fqn = "example.ClassName.method";
        String result = ByteBufTrackingAdvice.toSimpleName(fqn);

        assertEquals("ClassName.method", result,
                     "Should handle single-level package correctly");
    }

    @Test
    public void testToSimpleName_defaultPackage() {
        String fqn = "ClassName.method";
        String result = ByteBufTrackingAdvice.toSimpleName(fqn);

        assertEquals("ClassName.method", result,
                     "Should return input as-is for default package (no package)");
    }

    @Test
    public void testToSimpleName_innerClass() {
        String fqn = "com.example.Outer$Inner.method";
        String result = ByteBufTrackingAdvice.toSimpleName(fqn);

        assertEquals("Outer$Inner.method", result,
                     "Should handle inner class with $ separator");
    }

    @Test
    public void testToSimpleName_nestedInnerClass() {
        String fqn = "com.example.Outer$Middle$Inner.method";
        String result = ByteBufTrackingAdvice.toSimpleName(fqn);

        assertEquals("Outer$Middle$Inner.method", result,
                     "Should handle nested inner classes");
    }

    @Test
    public void testToSimpleName_methodWithDollarSign() {
        String fqn = "com.example.ClassName.lambda$main$0";
        String result = ByteBufTrackingAdvice.toSimpleName(fqn);

        assertEquals("ClassName.lambda$main$0", result,
                     "Should handle lambda method names with $ signs");
    }

    @Test
    public void testToSimpleName_constructor() {
        String fqn = "io.netty.buffer.UnpooledByteBufAllocator.<init>";
        String result = ByteBufTrackingAdvice.toSimpleName(fqn);

        assertEquals("UnpooledByteBufAllocator.<init>", result,
                     "Should handle constructor method name");
    }

    @Test
    public void testToSimpleName_staticInitializer() {
        String fqn = "com.example.MyClass.<clinit>";
        String result = ByteBufTrackingAdvice.toSimpleName(fqn);

        assertEquals("MyClass.<clinit>", result,
                     "Should handle static initializer");
    }

    @Test
    public void testToSimpleName_noDots() {
        String fqn = "method";
        String result = ByteBufTrackingAdvice.toSimpleName(fqn);

        assertEquals("method", result,
                     "Should return as-is when no dots present (malformed input)");
    }

    @Test
    public void testToSimpleName_trailingDot() {
        String fqn = "com.example.ClassName.";
        String result = ByteBufTrackingAdvice.toSimpleName(fqn);

        // Last dot is at the end, second-last dot is before ClassName
        // Should extract from second-last dot onwards
        assertEquals("ClassName.", result,
                     "Should handle trailing dot");
    }

    @Test
    public void testToSimpleName_emptyString() {
        String fqn = "";
        String result = ByteBufTrackingAdvice.toSimpleName(fqn);

        assertEquals("", result,
                     "Should handle empty string gracefully");
    }

    @Test
    public void testToSimpleName_alreadySimpleName() {
        String fqn = "ByteBuf.allocate";
        String result = ByteBufTrackingAdvice.toSimpleName(fqn);

        assertEquals("ByteBuf.allocate", result,
                     "Should return as-is when already in simple name format");
    }

    @Test
    public void testToSimpleName_nettyPooledAllocator() {
        String fqn = "io.netty.buffer.PooledByteBufAllocator.directBuffer";
        String result = ByteBufTrackingAdvice.toSimpleName(fqn);

        assertEquals("PooledByteBufAllocator.directBuffer", result,
                     "Should extract simple name from Netty pooled allocator");
    }

    @Test
    public void testToSimpleName_unpagedHeapBuffer() {
        String fqn = "io.netty.buffer.UnpooledHeapByteBuf.release";
        String result = ByteBufTrackingAdvice.toSimpleName(fqn);

        assertEquals("UnpooledHeapByteBuf.release", result,
                     "Should extract simple name from ByteBuf implementation");
    }

    @Test
    public void testToSimpleName_consistency() {
        // Test that multiple calls return same result
        String fqn = "com.example.test.MyClass.myMethod";

        String result1 = ByteBufTrackingAdvice.toSimpleName(fqn);
        String result2 = ByteBufTrackingAdvice.toSimpleName(fqn);
        String result3 = ByteBufTrackingAdvice.toSimpleName(fqn);

        assertEquals(result1, result2, "Multiple calls should return same result");
        assertEquals(result2, result3, "Multiple calls should return same result");
    }

    @Test
    public void testToSimpleName_differentPackagesSameClass() {
        String fqn1 = "com.example.foo.MyClass.method";
        String fqn2 = "com.example.bar.MyClass.method";

        String result1 = ByteBufTrackingAdvice.toSimpleName(fqn1);
        String result2 = ByteBufTrackingAdvice.toSimpleName(fqn2);

        assertEquals("MyClass.method", result1, "Should extract simple name from first FQN");
        assertEquals("MyClass.method", result2, "Should extract simple name from second FQN");
        assertEquals(result1, result2, "Same class name from different packages should yield same simple name");
    }

    @Test
    public void testToSimpleName_realWorldExample_Unpooled() {
        String fqn = "io.netty.buffer.Unpooled.buffer";
        String result = ByteBufTrackingAdvice.toSimpleName(fqn);

        assertEquals("Unpooled.buffer", result,
                     "Should extract simple name from Unpooled utility class");
    }

    @Test
    public void testToSimpleName_realWorldExample_release() {
        String fqn = "io.netty.buffer.AbstractReferenceCountedByteBuf.release";
        String result = ByteBufTrackingAdvice.toSimpleName(fqn);

        assertEquals("AbstractReferenceCountedByteBuf.release", result,
                     "Should extract simple name from release method");
    }
}
