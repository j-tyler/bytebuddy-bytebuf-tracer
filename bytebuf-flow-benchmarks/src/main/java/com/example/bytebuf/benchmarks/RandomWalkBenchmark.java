/*
 * Copyright 2025 Justin Marsh
 * SPDX-License-Identifier: Apache-2.0
 */
package com.example.bytebuf.benchmarks;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import java.util.Random;
import java.util.concurrent.TimeUnit;

/**
 * Random Walk Benchmark - Stress test for ByteBuf Flow Tracker.
 *
 * This benchmark simulates realistic object flow patterns by:
 * 1. Allocating a ByteBuf
 * 2. Randomly determining how many methods to call (5-50)
 * 3. Performing a random walk through 50 different methods
 * 4. Releasing the ByteBuf
 *
 * This pushes the internal optimizations to their limits, testing:
 * - Trie depth handling
 * - Path diversity
 * - Hash map performance under varied access patterns
 * - Memory efficiency with many different paths
 *
 * Run WITHOUT agent to establish baseline:
 *   java -jar target/benchmarks.jar RandomWalkBenchmark -prof gc
 *
 * Run WITH agent to measure overhead:
 *   java -javaagent:../bytebuf-flow-tracker/target/bytebuf-flow-tracker-1.0.0-SNAPSHOT-agent.jar=include=com.example.bytebuf.benchmarks \
 *     -jar target/benchmarks.jar RandomWalkBenchmark -prof gc
 *
 * The overhead measured here represents the worst-case scenario for the tracker
 * in real-world applications with complex, varied object flows.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Fork(value = 1)
@Warmup(iterations = 1, time = 5, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 3, time = 5, timeUnit = TimeUnit.SECONDS)
public class RandomWalkBenchmark {

    /**
     * ThreadLocal Random for thread-safe random number generation without contention.
     * Each benchmark thread gets its own Random instance.
     */
    private static final ThreadLocal<Random> THREAD_LOCAL_RANDOM =
        ThreadLocal.withInitial(() -> new Random(System.nanoTime()));

    /**
     * Minimum number of method calls per iteration.
     */
    private static final int MIN_HOPS = 5;

    /**
     * Maximum number of method calls per iteration.
     */
    private static final int MAX_HOPS = 50;

    /**
     * Total number of methods available for the random walk.
     */
    private static final int METHOD_COUNT = 50;

    @Setup(Level.Trial)
    public void setup() {
        System.out.println("=== Random Walk Benchmark Starting ===");
        System.out.println("Method count: " + METHOD_COUNT);
        System.out.println("Hops per iteration: " + MIN_HOPS + "-" + MAX_HOPS);
    }

    @TearDown(Level.Trial)
    public void teardown() {
        System.out.println("=== Random Walk Benchmark Complete ===");
    }

    /**
     * Main benchmark: Random walk through ByteBuf processing methods.
     *
     * Each iteration:
     * 1. Allocates a ByteBuf
     * 2. Randomly picks number of hops (5-50)
     * 3. Performs random walk through the 50 available methods
     * 4. Releases the ByteBuf
     *
     * This creates highly varied execution paths that stress the Trie structure
     * and test the tracker's ability to handle diverse flow patterns efficiently.
     */
    @Benchmark
    public void randomWalk(Blackhole bh) {
        Random random = THREAD_LOCAL_RANDOM.get();

        // Allocate ByteBuf
        ByteBuf buffer = Unpooled.buffer(256);

        // Determine how many method calls to make (random walk length)
        int hops = MIN_HOPS + random.nextInt(MAX_HOPS - MIN_HOPS + 1);

        // Perform random walk through methods
        for (int i = 0; i < hops; i++) {
            int methodIndex = random.nextInt(METHOD_COUNT);
            buffer = dispatchToMethod(methodIndex, buffer);
        }

        // Consume result and release
        bh.consume(buffer);
        buffer.release();
    }

    /**
     * Dispatcher method that routes to one of the 50 processing methods.
     * Using a switch statement for efficient dispatch.
     */
    private ByteBuf dispatchToMethod(int index, ByteBuf buffer) {
        switch (index) {
            case 0: return method0(buffer);
            case 1: return method1(buffer);
            case 2: return method2(buffer);
            case 3: return method3(buffer);
            case 4: return method4(buffer);
            case 5: return method5(buffer);
            case 6: return method6(buffer);
            case 7: return method7(buffer);
            case 8: return method8(buffer);
            case 9: return method9(buffer);
            case 10: return method10(buffer);
            case 11: return method11(buffer);
            case 12: return method12(buffer);
            case 13: return method13(buffer);
            case 14: return method14(buffer);
            case 15: return method15(buffer);
            case 16: return method16(buffer);
            case 17: return method17(buffer);
            case 18: return method18(buffer);
            case 19: return method19(buffer);
            case 20: return method20(buffer);
            case 21: return method21(buffer);
            case 22: return method22(buffer);
            case 23: return method23(buffer);
            case 24: return method24(buffer);
            case 25: return method25(buffer);
            case 26: return method26(buffer);
            case 27: return method27(buffer);
            case 28: return method28(buffer);
            case 29: return method29(buffer);
            case 30: return method30(buffer);
            case 31: return method31(buffer);
            case 32: return method32(buffer);
            case 33: return method33(buffer);
            case 34: return method34(buffer);
            case 35: return method35(buffer);
            case 36: return method36(buffer);
            case 37: return method37(buffer);
            case 38: return method38(buffer);
            case 39: return method39(buffer);
            case 40: return method40(buffer);
            case 41: return method41(buffer);
            case 42: return method42(buffer);
            case 43: return method43(buffer);
            case 44: return method44(buffer);
            case 45: return method45(buffer);
            case 46: return method46(buffer);
            case 47: return method47(buffer);
            case 48: return method48(buffer);
            case 49: return method49(buffer);
            default: return buffer;
        }
    }

    // ========== 50 ByteBuf Processing Methods ==========
    // Each method accepts a ByteBuf and returns it without modification.
    // These will all be instrumented by the agent, creating diverse flow paths.
    // No actual work is done - we're purely measuring tracking overhead.

    public ByteBuf method0(ByteBuf buffer) {
        return buffer;
    }

    public ByteBuf method1(ByteBuf buffer) {
        return buffer;
    }

    public ByteBuf method2(ByteBuf buffer) {
        return buffer;
    }

    public ByteBuf method3(ByteBuf buffer) {
        return buffer;
    }

    public ByteBuf method4(ByteBuf buffer) {
        return buffer;
    }

    public ByteBuf method5(ByteBuf buffer) {
        return buffer;
    }

    public ByteBuf method6(ByteBuf buffer) {
        return buffer;
    }

    public ByteBuf method7(ByteBuf buffer) {
        return buffer;
    }

    public ByteBuf method8(ByteBuf buffer) {
        return buffer;
    }

    public ByteBuf method9(ByteBuf buffer) {
        return buffer;
    }

    public ByteBuf method10(ByteBuf buffer) {
        return buffer;
    }

    public ByteBuf method11(ByteBuf buffer) {
        return buffer;
    }

    public ByteBuf method12(ByteBuf buffer) {
        return buffer;
    }

    public ByteBuf method13(ByteBuf buffer) {
        return buffer;
    }

    public ByteBuf method14(ByteBuf buffer) {
        return buffer;
    }

    public ByteBuf method15(ByteBuf buffer) {
        return buffer;
    }

    public ByteBuf method16(ByteBuf buffer) {
        return buffer;
    }

    public ByteBuf method17(ByteBuf buffer) {
        return buffer;
    }

    public ByteBuf method18(ByteBuf buffer) {
        return buffer;
    }

    public ByteBuf method19(ByteBuf buffer) {
        return buffer;
    }

    public ByteBuf method20(ByteBuf buffer) {
        return buffer;
    }

    public ByteBuf method21(ByteBuf buffer) {
        return buffer;
    }

    public ByteBuf method22(ByteBuf buffer) {
        return buffer;
    }

    public ByteBuf method23(ByteBuf buffer) {
        return buffer;
    }

    public ByteBuf method24(ByteBuf buffer) {
        return buffer;
    }

    public ByteBuf method25(ByteBuf buffer) {
        return buffer;
    }

    public ByteBuf method26(ByteBuf buffer) {
        return buffer;
    }

    public ByteBuf method27(ByteBuf buffer) {
        return buffer;
    }

    public ByteBuf method28(ByteBuf buffer) {
        return buffer;
    }

    public ByteBuf method29(ByteBuf buffer) {
        return buffer;
    }

    public ByteBuf method30(ByteBuf buffer) {
        return buffer;
    }

    public ByteBuf method31(ByteBuf buffer) {
        return buffer;
    }

    public ByteBuf method32(ByteBuf buffer) {
        return buffer;
    }

    public ByteBuf method33(ByteBuf buffer) {
        return buffer;
    }

    public ByteBuf method34(ByteBuf buffer) {
        return buffer;
    }

    public ByteBuf method35(ByteBuf buffer) {
        return buffer;
    }

    public ByteBuf method36(ByteBuf buffer) {
        return buffer;
    }

    public ByteBuf method37(ByteBuf buffer) {
        return buffer;
    }

    public ByteBuf method38(ByteBuf buffer) {
        return buffer;
    }

    public ByteBuf method39(ByteBuf buffer) {
        return buffer;
    }

    public ByteBuf method40(ByteBuf buffer) {
        return buffer;
    }

    public ByteBuf method41(ByteBuf buffer) {
        return buffer;
    }

    public ByteBuf method42(ByteBuf buffer) {
        return buffer;
    }

    public ByteBuf method43(ByteBuf buffer) {
        return buffer;
    }

    public ByteBuf method44(ByteBuf buffer) {
        return buffer;
    }

    public ByteBuf method45(ByteBuf buffer) {
        return buffer;
    }

    public ByteBuf method46(ByteBuf buffer) {
        return buffer;
    }

    public ByteBuf method47(ByteBuf buffer) {
        return buffer;
    }

    public ByteBuf method48(ByteBuf buffer) {
        return buffer;
    }

    public ByteBuf method49(ByteBuf buffer) {
        return buffer;
    }
}
