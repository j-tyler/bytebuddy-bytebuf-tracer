/*
 * Copyright 2025 Justin Marsh
 * SPDX-License-Identifier: Apache-2.0
 */
package com.example.bytebuf.benchmarks;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.UnpooledByteBufAllocator;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import java.util.concurrent.TimeUnit;

/**
 * JMH benchmark demonstrating the performance benefits of trackDirectOnly flag.
 *
 * This benchmark simulates a realistic mixed workload:
 * - 80% heap buffer allocations (temporary data, will GC)
 * - 20% direct buffer allocations (critical I/O, off-heap memory)
 *
 * The benchmark demonstrates that trackDirectOnly=true provides zero overhead
 * for the 80% heap allocations while still tracking the critical 20% direct buffers.
 *
 * Run scenarios:
 *
 * BASELINE (no agent):
 *   java -jar target/benchmarks.jar DirectMemoryFilteringBenchmark -prof gc
 *
 * TRACK ALL (default behavior):
 *   java -javaagent:../bytebuf-flow-tracker/target/bytebuf-flow-tracker-1.0.0-SNAPSHOT-agent.jar=include=com.example.bytebuf.benchmarks \
 *     -jar target/benchmarks.jar DirectMemoryFilteringBenchmark -prof gc
 *
 * TRACK DIRECT ONLY (zero overhead for heap):
 *   java -javaagent:../bytebuf-flow-tracker/target/bytebuf-flow-tracker-1.0.0-SNAPSHOT-agent.jar=include=com.example.bytebuf.benchmarks;trackDirectOnly=true \
 *     -jar target/benchmarks.jar DirectMemoryFilteringBenchmark -prof gc
 *
 * Expected results:
 * - Baseline: Best performance (no tracking)
 * - Track all: Some overhead from tracking 100% of allocations
 * - trackDirectOnly=true: Near-baseline performance (heap allocations not instrumented)
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Fork(value = 1)
@Warmup(iterations = 2, time = 5, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 3, time = 10, timeUnit = TimeUnit.SECONDS)
public class DirectMemoryFilteringBenchmark {

    private ByteBufAllocator allocator;

    /**
     * Setup method - called once before all benchmark iterations.
     * ENFORCES that GC profiler is enabled (mandatory for meaningful results).
     */
    @Setup(Level.Trial)
    public void setup() {
        // MANDATORY CHECK: GC profiler must be enabled
        validateGcProfilerEnabled();
        System.out.println("=== Direct Memory Filtering Benchmark Starting ===");
        System.out.println("Workload: 80% heap allocations, 20% direct allocations");
        allocator = UnpooledByteBufAllocator.DEFAULT;
    }

    /**
     * Validates that GC profiler is enabled via environment variable acknowledgment.
     * Fails fast with clear error message if not set.
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
            "  JMH_GC_PROF=true java -jar target/benchmarks.jar -prof gc\n" +
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
        System.out.println("=== Direct Memory Filtering Benchmark Complete ===");
    }

    /**
     * Benchmark 1: Realistic mixed workload - 80% heap, 20% direct
     *
     * Simulates a typical application where most temporary data uses heap buffers
     * but critical I/O operations use direct buffers.
     *
     * With trackDirectOnly=true, the 80% heap allocations have ZERO overhead.
     */
    @Benchmark
    @OperationsPerInvocation(10)
    public void realisticMixedWorkload(Blackhole bh) {
        // Simulate 10 operations: 8 heap + 2 direct
        for (int i = 0; i < 10; i++) {
            ByteBuf buffer;

            if (i < 8) {
                // 80% of operations: heap buffers for temporary data
                buffer = allocateHeapBuffer();
                processTemporaryData(buffer);
            } else {
                // 20% of operations: direct buffers for I/O
                buffer = allocateDirectBuffer();
                processNetworkData(buffer);
            }

            bh.consume(buffer);
            buffer.release();
        }
    }

    /**
     * Benchmark 2: High-frequency heap allocations (80% scenario)
     *
     * This benchmark focuses on the common case - heap buffer allocations.
     * With trackDirectOnly=true, this has ZERO instrumentation overhead.
     */
    @Benchmark
    @OperationsPerInvocation(100)
    public void highFrequencyHeapAllocations(Blackhole bh) {
        for (int i = 0; i < 100; i++) {
            ByteBuf buffer = allocateHeapBuffer();
            processTemporaryData(buffer);
            bh.consume(buffer);
            buffer.release();
        }
    }

    /**
     * Benchmark 3: Critical direct buffer operations (20% scenario)
     *
     * This benchmark focuses on the critical path - direct buffer allocations.
     * These are always tracked regardless of mode, as they represent critical
     * memory leaks (never GC'd).
     */
    @Benchmark
    @OperationsPerInvocation(100)
    public void criticalDirectBufferOperations(Blackhole bh) {
        for (int i = 0; i < 100; i++) {
            ByteBuf buffer = allocateDirectBuffer();
            processNetworkData(buffer);
            bh.consume(buffer);
            buffer.release();
        }
    }

    /**
     * Benchmark 4: Ambiguous allocations with buffer() method
     *
     * Uses allocator.buffer() which could return either heap or direct depending
     * on allocator implementation. When trackDirectOnly=true, the tracker calls
     * isDirect() at runtime to determine if this buffer should be tracked.
     *
     * With UnpooledByteBufAllocator.DEFAULT, buffer() returns direct buffers,
     * so they will be tracked when trackDirectOnly=true.
     */
    @Benchmark
    @OperationsPerInvocation(100)
    public void ambiguousBufferAllocations(Blackhole bh) {
        for (int i = 0; i < 100; i++) {
            // Ambiguous allocation - could be heap or direct
            ByteBuf buffer = allocator.buffer(256);
            processData(buffer);
            bh.consume(buffer);
            buffer.release();
        }
    }

    /**
     * Benchmark 5: Method call chain overhead
     *
     * Measures the overhead of tracking ByteBuf flow through multiple methods.
     * With trackDirectOnly=true on heap buffers, the method tracking also has
     * zero overhead since the buffer isn't tracked at all.
     */
    @Benchmark
    public void methodChainOverhead(Blackhole bh) {
        // 80% heap buffer through method chain
        ByteBuf heapBuffer = allocateHeapBuffer();
        heapBuffer = chainStep1(heapBuffer);
        heapBuffer = chainStep2(heapBuffer);
        heapBuffer = chainStep3(heapBuffer);
        bh.consume(heapBuffer);
        heapBuffer.release();

        // 20% direct buffer through method chain
        ByteBuf directBuffer = allocateDirectBuffer();
        directBuffer = chainStep1(directBuffer);
        directBuffer = chainStep2(directBuffer);
        directBuffer = chainStep3(directBuffer);
        bh.consume(directBuffer);
        directBuffer.release();
    }

    // ========== Helper Methods ==========
    // These methods simulate realistic application patterns

    /**
     * Allocates a heap buffer (80% of operations).
     * With trackDirectOnly=true, this method is NOT instrumented.
     */
    public ByteBuf allocateHeapBuffer() {
        return allocator.heapBuffer(256);
    }

    /**
     * Allocates a direct buffer (20% of operations).
     * Always instrumented - critical for leak detection.
     */
    public ByteBuf allocateDirectBuffer() {
        return allocator.directBuffer(256);
    }

    /**
     * Processes temporary data (heap buffers).
     * Simulates parsing, validation, transformation.
     */
    public ByteBuf processTemporaryData(ByteBuf buffer) {
        // Simulate work: write some data
        buffer.writeLong(System.nanoTime());
        buffer.writeInt(buffer.capacity());
        return buffer;
    }

    /**
     * Processes network data (direct buffers).
     * Simulates socket I/O, serialization.
     */
    public ByteBuf processNetworkData(ByteBuf buffer) {
        // Simulate I/O work: write some data
        buffer.writeLong(System.currentTimeMillis());
        buffer.writeInt(buffer.capacity());
        buffer.writeByte(0xFF);
        return buffer;
    }

    /**
     * Generic processing (ambiguous buffer type).
     */
    public ByteBuf processData(ByteBuf buffer) {
        buffer.writeInt(42);
        return buffer;
    }

    /**
     * Method chain step 1 - simulates data flow through application.
     */
    public ByteBuf chainStep1(ByteBuf buffer) {
        buffer.markReaderIndex();
        return buffer;
    }

    /**
     * Method chain step 2 - simulates data flow through application.
     */
    public ByteBuf chainStep2(ByteBuf buffer) {
        buffer.markWriterIndex();
        return buffer;
    }

    /**
     * Method chain step 3 - simulates data flow through application.
     */
    public ByteBuf chainStep3(ByteBuf buffer) {
        buffer.resetReaderIndex();
        return buffer;
    }
}
