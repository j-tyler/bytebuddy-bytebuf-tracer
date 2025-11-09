/*
 * Copyright 2025 Justin Marsh
 * SPDX-License-Identifier: Apache-2.0
 */

package com.example.bytebuf.tracker.integration.testapp;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.UnpooledByteBufAllocator;

/**
 * Integration test application that demonstrates direct buffer leak highlighting.
 * This app creates both heap and direct buffer leaks to test emoji differentiation.
 */
public class DirectBufferLeakApp {

    private final ByteBufAllocator allocator = UnpooledByteBufAllocator.DEFAULT;

    public static void main(String[] args) {
        System.out.println("=== DirectBufferLeakApp Starting ===");

        DirectBufferLeakApp app = new DirectBufferLeakApp();

        // Scenario 1: Heap buffer leak (moderate - will GC)
        app.heapBufferLeak();

        // Scenario 2: Direct buffer leak (CRITICAL - never GC'd!)
        app.directBufferLeak();

        // Scenario 3: Properly released direct buffer (clean)
        app.properlyReleased();

        System.out.println("=== DirectBufferLeakApp Complete ===");
    }

    /**
     * Creates a heap buffer leak - moderate severity (⚠️)
     * Heap memory will eventually be GC'd, but still a bug
     */
    public void heapBufferLeak() {
        System.out.println("Creating heap buffer leak...");
        ByteBuf heapBuf = allocator.heapBuffer(256);
        processHeapBuffer(heapBuf);
        // BUG: Forgot to release! But it's heap, so it will GC eventually
    }

    public void processHeapBuffer(ByteBuf buf) {
        buf.writeBytes("Heap data that will eventually GC".getBytes());
        forgetToReleaseHeap(buf);
    }

    public void forgetToReleaseHeap(ByteBuf buf) {
        // Leak detected here - heap buffer
        System.out.println("  Heap buffer leaked (refCount=" + buf.refCnt() + ")");
    }

    /**
     * Creates a direct buffer leak - CRITICAL severity (🚨)
     * Direct memory will NEVER be GC'd - permanent leak!
     */
    public void directBufferLeak() {
        System.out.println("Creating CRITICAL direct buffer leak...");
        ByteBuf directBuf = allocator.directBuffer(1024);
        processDirectBuffer(directBuf);
        // BUG: Forgot to release! CRITICAL - this memory is NEVER reclaimed!
    }

    public void processDirectBuffer(ByteBuf buf) {
        buf.writeBytes("Direct memory that will NEVER GC".getBytes());
        forgetToReleaseDirect(buf);
    }

    public void forgetToReleaseDirect(ByteBuf buf) {
        // CRITICAL leak detected here - direct buffer
        System.out.println("  Direct buffer leaked (refCount=" + buf.refCnt() + ") - CRITICAL!");
    }

    /**
     * Properly releases a direct buffer - shows clean flow
     */
    public void properlyReleased() {
        System.out.println("Creating and properly releasing direct buffer...");
        ByteBuf directBuf = allocator.directBuffer(512);
        processAndRelease(directBuf);
    }

    public void processAndRelease(ByteBuf buf) {
        buf.writeBytes("Properly managed data".getBytes());
        cleanupProperly(buf);
    }

    public void cleanupProperly(ByteBuf buf) {
        buf.release();
        System.out.println("  Buffer properly released (refCount=" + buf.refCnt() + ")");
    }
}
