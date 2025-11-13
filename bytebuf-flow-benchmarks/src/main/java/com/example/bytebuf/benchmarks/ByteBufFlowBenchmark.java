/*
 * Copyright 2025 Justin Marsh
 * SPDX-License-Identifier: Apache-2.0
 */
package com.example.bytebuf.benchmarks;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import java.util.concurrent.TimeUnit;

/**
 * JMH benchmarks for ByteBuf Flow Tracker performance overhead.
 *
 * These benchmarks measure the cost of tracking ByteBuf allocations and method calls.
 * Run with and without the agent to measure overhead:
 *
 * WITHOUT AGENT (baseline):
 *   java -jar target/benchmarks.jar -prof gc
 *
 * WITH AGENT (measure overhead):
 *   java -javaagent:../bytebuf-flow-tracker/target/bytebuf-flow-tracker-1.0.0-SNAPSHOT-agent.jar=include=com.example.bytebuf.benchmarks \
 *     -jar target/benchmarks.jar -prof gc
 *
 * Default configuration:
 * - Mode: Throughput
 * - Forks: 1
 * - Warmup: 5 seconds
 * - Iterations: 3 at 5 seconds each
 * - GC Profiling: Enabled
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Fork(value = 1)
@Warmup(iterations = 1, time = 5, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 3, time = 5, timeUnit = TimeUnit.SECONDS)
public class ByteBufFlowBenchmark {

    /**
     * Setup method - called once before all benchmark iterations.
     * ENFORCES that GC profiler is enabled (mandatory for meaningful results).
     */
    @Setup(Level.Trial)
    public void setup() {
        // MANDATORY CHECK: GC profiler must be enabled
        validateGcProfilerEnabled();
        System.out.println("=== ByteBuf Flow Tracker Benchmark Starting ===");
    }

    /**
     * Validates that GC profiler is enabled via environment variable acknowledgment.
     * Fails fast with clear error message if not set.
     *
     * This enforces that users explicitly acknowledge they are running with GC profiling
     * by setting the JMH_GC_PROF environment variable. This prevents accidental runs
     * without -prof gc which would produce useless results.
     */
    private void validateGcProfilerEnabled() {
        String gcProfEnv = System.getenv("JMH_GC_PROF");
        if ("true".equalsIgnoreCase(gcProfEnv) || "1".equals(gcProfEnv)) {
            return; // User explicitly acknowledged GC profiling is enabled
        }

        // GC profiler NOT acknowledged - FAIL FAST
        throw new IllegalStateException(
            "\n\n" +
            "================================================================================\n" +
            "FATAL ERROR: GC PROFILER NOT ACKNOWLEDGED\n" +
            "================================================================================\n" +
            "GC profiling (-prof gc) is MANDATORY for all benchmarks in this module.\n" +
            "You MUST set the JMH_GC_PROF environment variable to confirm you are using it.\n" +
            "\n" +
            "WHY THIS MATTERS:\n" +
            "  This project optimizes memory allocations. Without GC profiling, allocation\n" +
            "  rates (B/op) cannot be measured. Results are COMPLETELY USELESS without it.\n" +
            "\n" +
            "CORRECT USAGE:\n" +
            "  export JMH_GC_PROF=true\n" +
            "  java -jar target/benchmarks.jar -prof gc\n" +
            "\n" +
            "OR (one-liner):\n" +
            "  JMH_GC_PROF=true java -jar target/benchmarks.jar -prof gc\n" +
            "\n" +
            "WITH AGENT:\n" +
            "  JMH_GC_PROF=true java \"-javaagent:../bytebuf-flow-tracker/target/bytebuf-flow-tracker-1.0.0-SNAPSHOT-agent.jar=include=com.example.bytebuf.benchmarks\" \\\n" +
            "    -jar target/benchmarks.jar -prof gc\n" +
            "\n" +
            "This environment variable forces you to explicitly acknowledge that you are\n" +
            "running with GC profiling. If you see this error, you forgot -prof gc.\n" +
            "\n" +
            "See README.md lines 22, 124, 163 for details.\n" +
            "================================================================================\n"
        );
    }

    /**
     * Teardown method - called once after all benchmark iterations.
     */
    @TearDown(Level.Trial)
    public void teardown() {
        System.out.println("=== ByteBuf Flow Tracker Benchmark Complete ===");
    }

    /**
     * Benchmark 1: Simple allocate and release.
     * Measures the overhead of tracking a single allocation and release.
     */
    @Benchmark
    public void simpleAllocateAndRelease(Blackhole bh) {
        ByteBuf buffer = Unpooled.buffer(256);
        bh.consume(buffer);
        buffer.release();
    }

    /**
     * Benchmark 2: Allocate, pass to method, return from method, and release.
     * Measures the overhead of tracking method calls with ByteBuf parameters and returns.
     */
    @Benchmark
    public void allocatePassToMethodAndRelease(Blackhole bh) {
        ByteBuf buffer = allocate();
        buffer = processBuffer(buffer);
        bh.consume(buffer);
        buffer.release();
    }

    /**
     * Benchmark 3: Allocate, pass through chain of methods, and release.
     * Measures overhead of tracking multiple method calls in a flow.
     */
    @Benchmark
    public void allocatePassThroughChainAndRelease(Blackhole bh) {
        ByteBuf buffer = allocate();
        buffer = processStep1(buffer);
        buffer = processStep2(buffer);
        buffer = processStep3(buffer);
        bh.consume(buffer);
        buffer.release();
    }

    /**
     * Benchmark 4: Allocate and release in tight loop (stress test).
     * Measures throughput under high allocation/release pressure.
     */
    @Benchmark
    @OperationsPerInvocation(100)
    public void tightLoopAllocateAndRelease(Blackhole bh) {
        for (int i = 0; i < 100; i++) {
            ByteBuf buffer = Unpooled.buffer(256);
            bh.consume(buffer);
            buffer.release();
        }
    }

    // ========== Helper Methods ==========
    // These methods will be instrumented when the agent is attached

    /**
     * Allocates a ByteBuf. This method will be tracked by the agent.
     */
    public ByteBuf allocate() {
        return Unpooled.buffer(256);
    }

    /**
     * Processes a ByteBuf and returns it. This method will be tracked by the agent.
     */
    public ByteBuf processBuffer(ByteBuf buffer) {
        // Simulate some work
        buffer.writeByte(42);
        return buffer;
    }

    /**
     * Processing step 1. This method will be tracked by the agent.
     */
    public ByteBuf processStep1(ByteBuf buffer) {
        buffer.writeByte(1);
        return buffer;
    }

    /**
     * Processing step 2. This method will be tracked by the agent.
     */
    public ByteBuf processStep2(ByteBuf buffer) {
        buffer.writeByte(2);
        return buffer;
    }

    /**
     * Processing step 3. This method will be tracked by the agent.
     */
    public ByteBuf processStep3(ByteBuf buffer) {
        buffer.writeByte(3);
        return buffer;
    }
}
