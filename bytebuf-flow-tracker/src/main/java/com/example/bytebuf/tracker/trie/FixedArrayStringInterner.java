/*
 * Copyright 2025 Justin Marsh
 * SPDX-License-Identifier: Apache-2.0
 */

package com.example.bytebuf.tracker.trie;

/**
 * Fixed-size string interner with bounded memory guarantees.
 * Uses hash-based lookup with limited probing for collision resolution.
 *
 * <p><b>Performance Focus:</b> This class prioritizes performance over thread-safety.
 * Race conditions may occur but do not affect correctness - at worst, a string may be
 * stored in multiple cells, which only impacts memory efficiency, not correctness.
 *
 * <p><b>Memory Bounds:</b> The interner has a fixed capacity and will not grow
 * beyond this size. When no empty cell is found, the original hash cell is replaced.
 *
 * <p><b>Probabilistic Tradeoff:</b>
 * <ul>
 *   <li>Hash-based lookup with limited probing (max 8 cells) reduces CPU overhead</li>
 *   <li>Replacement strategy prevents unbounded probing</li>
 *   <li>Fixed capacity prevents unbounded memory growth</li>
 *   <li>Cache-friendly: only probes up to 8 adjacent cells</li>
 *   <li>No atomic operations for maximum performance</li>
 * </ul>
 *
 * @see BoundedImprintTrie
 */
public class FixedArrayStringInterner {

    // Maximum number of cells to probe to the right (cache-friendly limit)
    private static final int MAX_PROBE_COUNT = 8;

    private final String[] table;
    private final int capacity;

    /**
     * Create an interner with specified capacity.
     * @param capacity the maximum number of strings to intern (will be rounded to power of 2)
     */
    public FixedArrayStringInterner(int capacity) {
        // Round up to next power of 2 for efficient modulo via bitwise AND
        this.capacity = nextPowerOfTwo(capacity);
        this.table = new String[this.capacity];
    }

    /**
     * Intern a string using hash-based lookup with limited probing.
     *
     * <p><b>Algorithm:</b>
     * <ol>
     *   <li>Hash the string and look at that cell in the array</li>
     *   <li>If the string in that cell equals the input, return it (cache hit)</li>
     *   <li>If not equal, probe right up to 8 cells</li>
     *   <li>If an empty cell is found during probing, insert there and return</li>
     *   <li>If no empty cell found in 8 probes, replace the original hash cell and return</li>
     * </ol>
     *
     * <p>This provides a probabilistic CPU/Memory tradeoff:
     * limited probing reduces CPU overhead, replacement ensures bounded behavior.
     *
     * @param s the string to intern
     * @return the interned string (either existing match or newly stored)
     */
    public String intern(String s) {
        if (s == null) {
            return null;
        }

        int hash = s.hashCode();
        int originalIndex = indexFor(hash, capacity);

        // Check original hash cell first
        String existing = table[originalIndex];
        if (existing != null && existing.equals(s)) {
            return existing;  // Cache hit at hash position
        }

        // Probe right up to MAX_PROBE_COUNT cells
        for (int probe = 1; probe <= MAX_PROBE_COUNT; probe++) {
            int index = (originalIndex + probe) & (capacity - 1);
            existing = table[index];

            if (existing == null) {
                // Empty slot found - insert here
                table[index] = s;
                return s;
            }

            // Check if this cell contains an equal string
            if (existing.equals(s)) {
                return existing;  // Cache hit during probing
            }
        }

        // No empty cell found in MAX_PROBE_COUNT probes
        // Replace the string in the original hash cell
        table[originalIndex] = s;
        return s;
    }

    /**
     * Get the capacity of the interner.
     */
    public int capacity() {
        return capacity;
    }

    /**
     * Count occupied cells (on-demand calculation for testing).
     * This walks the entire table, so should only be used for testing/debugging.
     *
     * @return the number of non-null cells in the table
     */
    public int countOccupied() {
        int count = 0;
        for (int i = 0; i < capacity; i++) {
            if (table[i] != null) {
                count++;
            }
        }
        return count;
    }

    /**
     * Get the load factor (occupied cells / capacity) - on-demand calculation.
     * This walks the entire table, so should only be used for testing/debugging.
     */
    public double loadFactor() {
        return (double) countOccupied() / capacity;
    }

    /**
     * Clear all interned strings.
     */
    public void clear() {
        for (int i = 0; i < capacity; i++) {
            table[i] = null;
        }
    }

    /**
     * Compute table index from hash code.
     * Uses bitwise AND instead of modulo for efficiency (requires power-of-2 capacity).
     */
    private static int indexFor(int hash, int capacity) {
        // Spread hash bits to reduce collisions
        hash ^= (hash >>> 16);
        return hash & (capacity - 1);
    }

    /**
     * Round up to next power of 2.
     */
    private static int nextPowerOfTwo(int n) {
        if (n <= 0) {
            return 1;
        }
        // Handle overflow
        if (n >= (1 << 30)) {
            return 1 << 30;
        }
        n--;
        n |= n >>> 1;
        n |= n >>> 2;
        n |= n >>> 4;
        n |= n >>> 8;
        n |= n >>> 16;
        return n + 1;
    }
}
