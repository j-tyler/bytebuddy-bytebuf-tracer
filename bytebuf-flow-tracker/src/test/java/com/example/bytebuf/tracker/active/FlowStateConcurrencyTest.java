/*
 * Copyright 2025 Justin Marsh
 * SPDX-License-Identifier: Apache-2.0
 */

package com.example.bytebuf.tracker.active;

import com.example.bytebuf.tracker.trie.ImprintNode;
import org.junit.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

/**
 * Concurrency tests for FlowState to detect atomicity issues in read-modify-write operations.
 */
public class FlowStateConcurrencyTest {

    @Test
    public void testConcurrentIncrementDepth_DetectsLostUpdates() throws InterruptedException {
        // Create a FlowState
        ImprintNode rootNode = new ImprintNode("TestClass.testMethod", (byte) 1, null);
        FlowState state = new FlowState();
        state.reset(rootNode);

        // Spawn many threads that all increment depth concurrently
        final int threadCount = 50;
        final int incrementsPerThread = 100;
        final int expectedFinalDepth = threadCount * incrementsPerThread;

        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);

        Thread[] threads = new Thread[threadCount];
        for (int i = 0; i < threadCount; i++) {
            threads[i] = new Thread(() -> {
                try {
                    // Wait for all threads to be ready (maximize contention)
                    startLatch.await();

                    // Hammer incrementDepth()
                    for (int j = 0; j < incrementsPerThread; j++) {
                        state.incrementDepth();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    doneLatch.countDown();
                }
            });
            threads[i].start();
        }

        // Release all threads simultaneously
        startLatch.countDown();

        // Wait for all to complete
        doneLatch.await();

        // Check final depth
        int actualDepth = state.getCurrentDepth();

        System.out.println("Expected depth: " + Math.min(expectedFinalDepth, 127));
        System.out.println("Actual depth: " + actualDepth);

        // If atomicity is broken, actualDepth will be less than expected
        // Note: depth is capped at 127, so expected is min(5000, 127) = 127
        int expectedCapped = Math.min(expectedFinalDepth, 127);

        if (actualDepth < expectedCapped) {
            fail("ATOMICITY BUG DETECTED: Expected depth=" + expectedCapped +
                 ", but got depth=" + actualDepth +
                 ". Lost " + (expectedCapped - actualDepth) + " increments due to race condition.");
        }

        assertEquals("Depth should match expected (or be capped at 127)",
                     expectedCapped, actualDepth);
    }

    @Test
    public void testConcurrentIncrementAndMarkCompleted_DetectsLostFlags() throws InterruptedException {
        // Create a FlowState
        ImprintNode rootNode = new ImprintNode("TestClass.testMethod", (byte) 1, null);
        FlowState state = new FlowState();
        state.reset(rootNode);

        // Spawn threads: half increment depth, half mark completed
        final int threadCount = 100;
        final int incrementsPerThread = 50;

        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);
        AtomicInteger markedCount = new AtomicInteger(0);

        Thread[] threads = new Thread[threadCount];
        for (int i = 0; i < threadCount; i++) {
            final int threadId = i;
            threads[i] = new Thread(() -> {
                try {
                    startLatch.await();

                    if (threadId % 2 == 0) {
                        // Even threads: increment depth
                        for (int j = 0; j < incrementsPerThread; j++) {
                            state.incrementDepth();
                        }
                    } else {
                        // Odd threads: mark completed
                        state.markCompleted();
                        markedCount.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    doneLatch.countDown();
                }
            });
            threads[i].start();
        }

        // Release all threads simultaneously
        startLatch.countDown();

        // Wait for all to complete
        doneLatch.await();

        // Check results
        int finalDepth = state.getCurrentDepth();
        boolean isCompleted = state.isCompleted();

        System.out.println("Final depth: " + finalDepth);
        System.out.println("Is completed: " + isCompleted);
        System.out.println("Threads that called markCompleted: " + markedCount.get());

        // If even ONE thread called markCompleted(), the flag should be set
        if (markedCount.get() > 0 && !isCompleted) {
            fail("ATOMICITY BUG DETECTED: " + markedCount.get() +
                 " threads called markCompleted(), but isCompleted()=false. " +
                 "The completed flag was lost due to race with incrementDepth().");
        }

        assertTrue("After " + markedCount.get() + " threads called markCompleted(), " +
                   "isCompleted() should be true", isCompleted);
    }

    @Test
    public void testConcurrentIncrementDepth_StressTest_10Runs() throws InterruptedException {
        // Run the stress test multiple times to increase chance of catching race
        int failures = 0;
        int runs = 10;

        for (int run = 0; run < runs; run++) {
            ImprintNode rootNode = new ImprintNode("TestClass.testMethod", (byte) 1, null);
            FlowState state = new FlowState();
            state.reset(rootNode);

            final int threadCount = 20;
            final int incrementsPerThread = 50;
            final int expectedFinalDepth = Math.min(threadCount * incrementsPerThread, 127);

            CountDownLatch startLatch = new CountDownLatch(1);
            CountDownLatch doneLatch = new CountDownLatch(threadCount);

            Thread[] threads = new Thread[threadCount];
            for (int i = 0; i < threadCount; i++) {
                threads[i] = new Thread(() -> {
                    try {
                        startLatch.await();
                        for (int j = 0; j < incrementsPerThread; j++) {
                            state.incrementDepth();
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        doneLatch.countDown();
                    }
                });
                threads[i].start();
            }

            startLatch.countDown();
            doneLatch.await();

            int actualDepth = state.getCurrentDepth();
            if (actualDepth < expectedFinalDepth) {
                failures++;
                System.out.println("Run " + (run + 1) + "/" + runs +
                                   " FAILED: Expected " + expectedFinalDepth +
                                   ", got " + actualDepth +
                                   " (lost " + (expectedFinalDepth - actualDepth) + " increments)");
            } else {
                System.out.println("Run " + (run + 1) + "/" + runs + " passed");
            }
        }

        if (failures > 0) {
            fail("ATOMICITY BUG CONFIRMED: " + failures + "/" + runs +
                 " runs detected lost updates in incrementDepth()");
        }
    }

    @Test
    public void testConcurrentIncrementDepth_SmallNumbers_CatchesLostUpdates() throws InterruptedException {
        // Use small increments so we don't hit the 127 cap
        // This makes lost updates more visible
        ImprintNode rootNode = new ImprintNode("TestClass.testMethod", (byte) 1, null);
        FlowState state = new FlowState();
        state.reset(rootNode);

        // 10 threads, each increments 5 times = expected depth of 50
        final int threadCount = 10;
        final int incrementsPerThread = 5;
        final int expectedFinalDepth = threadCount * incrementsPerThread;

        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);

        Thread[] threads = new Thread[threadCount];
        for (int i = 0; i < threadCount; i++) {
            threads[i] = new Thread(() -> {
                try {
                    startLatch.await();
                    for (int j = 0; j < incrementsPerThread; j++) {
                        state.incrementDepth();
                        // Add tiny sleep to increase chance of interleaving
                        Thread.yield();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    doneLatch.countDown();
                }
            });
            threads[i].start();
        }

        startLatch.countDown();
        doneLatch.await();

        int actualDepth = state.getCurrentDepth();

        System.out.println("Small number test: Expected " + expectedFinalDepth + ", got " + actualDepth);

        if (actualDepth < expectedFinalDepth) {
            fail("ATOMICITY BUG DETECTED: Expected depth=" + expectedFinalDepth +
                 ", but got depth=" + actualDepth +
                 ". Lost " + (expectedFinalDepth - actualDepth) + " increments.");
        }

        assertEquals("All 50 increments should be visible", expectedFinalDepth, actualDepth);
    }
}
