/*
 * Copyright 2025 Justin Marsh
 * SPDX-License-Identifier: Apache-2.0
 */

package com.example.bytebuf.tracker.trie;

import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

/**
 * Tests for FixedArrayStringInterner.
 */
public class FixedArrayStringInternerTest {

    private FixedArrayStringInterner interner;

    @Before
    public void setUp() {
        // Create interner with reasonable test capacity (16K)
        interner = new FixedArrayStringInterner(16384);
    }

    @Test
    public void testBasicInterning() {
        String s1 = new String("test");
        String s2 = new String("test");

        String i1 = interner.intern(s1);
        String i2 = interner.intern(s2);

        // Should return the same instance
        assertSame("Same strings should return same instance", i1, i2);
        assertEquals("test", i1);
    }

    @Test
    public void testNullHandling() {
        assertNull("Null should return null", interner.intern(null));
    }

    @Test
    public void testDifferentStrings() {
        String s1 = interner.intern("hello");
        String s2 = interner.intern("world");

        assertNotSame("Different strings should return different instances", s1, s2);
        assertEquals("hello", s1);
        assertEquals("world", s2);
        assertEquals(2, interner.countOccupied());
    }

    @Test
    public void testCapacityEnforcement() {
        // Create interner with small capacity
        FixedArrayStringInterner smallInterner = new FixedArrayStringInterner(4);

        // Should intern successfully
        String s1 = smallInterner.intern("a");
        String s2 = smallInterner.intern("b");
        String s3 = smallInterner.intern("c");

        assertTrue("Size should not exceed capacity",
            smallInterner.countOccupied() <= smallInterner.capacity());
    }

    @Test
    public void testLoadFactor() {
        interner.intern("test1");
        interner.intern("test2");
        interner.intern("test3");

        double loadFactor = interner.loadFactor();
        assertTrue("Load factor should be between 0 and 1",
            loadFactor >= 0.0 && loadFactor <= 1.0);
        assertTrue("Load factor should be positive after adding strings",
            loadFactor > 0.0);
    }

    @Test
    public void testClear() {
        interner.intern("test1");
        interner.intern("test2");
        assertEquals(2, interner.countOccupied());

        interner.clear();
        assertEquals(0, interner.countOccupied());

        // Should be able to intern again after clear
        String s = interner.intern("test1");
        assertEquals("test1", s);
        assertEquals(1, interner.countOccupied());
    }

    @Test
    public void testConcurrentInterning() throws InterruptedException {
        int threadCount = 10;
        int stringsPerThread = 100;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);

        for (int t = 0; t < threadCount; t++) {
            final int threadId = t;
            executor.submit(new Runnable() {
                @Override
                public void run() {
                    try {
                        startLatch.await();
                        for (int i = 0; i < stringsPerThread; i++) {
                            // Reuse same strings across threads
                            String s = "thread" + (threadId % 5) + "_string" + (i % 10);
                            interner.intern(s);
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        doneLatch.countDown();
                    }
                }
            });
        }

        startLatch.countDown(); // Start all threads
        doneLatch.await(10, TimeUnit.SECONDS); // Wait for completion
        executor.shutdown();

        // Verify no crashes and size is reasonable
        assertTrue("Should have interned some strings", interner.countOccupied() > 0);
        assertTrue("Size should not exceed capacity",
            interner.countOccupied() <= interner.capacity());
    }

    @Test
    public void testInterningIdenticalStrings() {
        List<String> internedStrings = new ArrayList<String>();

        // Intern the same string multiple times
        for (int i = 0; i < 100; i++) {
            String s = interner.intern("repeated");
            internedStrings.add(s);
        }

        // All should point to the same instance
        String first = internedStrings.get(0);
        for (int i = 0; i < internedStrings.size(); i++) {
            assertSame("All instances should be the same", first, internedStrings.get(i));
        }

        // Should only count as 1 in size
        assertEquals(1, interner.countOccupied());
    }

    @Test
    public void testCapacityRounding() {
        // Test that capacity is rounded to power of 2
        FixedArrayStringInterner interner1 = new FixedArrayStringInterner(100);
        assertEquals("Should round up to 128", 128, interner1.capacity());

        FixedArrayStringInterner interner2 = new FixedArrayStringInterner(1000);
        assertEquals("Should round up to 1024", 1024, interner2.capacity());

        FixedArrayStringInterner interner3 = new FixedArrayStringInterner(1024);
        assertEquals("Power of 2 should remain unchanged", 1024, interner3.capacity());
    }

    @Test
    public void testHashCollisionHandling() {
        // Create strings that will likely collide
        FixedArrayStringInterner smallInterner = new FixedArrayStringInterner(16);

        // Intern many strings to test collision handling
        for (int i = 0; i < 10; i++) {
            String s = smallInterner.intern("string_" + i);
            assertNotNull(s);
        }

        // Verify size
        assertTrue("Should have interned some strings", smallInterner.countOccupied() > 0);
    }

    @Test
    public void testOverflowBehavior() {
        // Create interner with very small capacity
        FixedArrayStringInterner tinyInterner = new FixedArrayStringInterner(2);

        // Try to intern more strings than capacity
        String s1 = tinyInterner.intern("a");
        String s2 = tinyInterner.intern("b");
        String s3 = tinyInterner.intern("c");
        String s4 = tinyInterner.intern("d");

        // Should not crash, strings are still returned
        assertNotNull(s1);
        assertNotNull(s2);
        assertNotNull(s3);
        assertNotNull(s4);

        // Size should not exceed capacity significantly
        assertTrue("Size should be close to capacity",
            tinyInterner.countOccupied() <= tinyInterner.capacity() + 1);
    }

    @Test
    public void testEmptyStringInterning() {
        String empty1 = interner.intern("");
        String empty2 = interner.intern("");

        assertSame("Empty strings should be interned", empty1, empty2);
        assertEquals("", empty1);
    }

    @Test
    public void testLongStringInterning() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 1000; i++) {
            sb.append("a");
        }
        String longString = sb.toString();

        String interned1 = interner.intern(longString);
        String interned2 = interner.intern(new String(longString));

        assertSame("Long strings should be interned", interned1, interned2);
    }
}
