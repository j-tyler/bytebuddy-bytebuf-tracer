# ByteBuf Flow Tracker Benchmarks

JMH benchmarks for measuring ByteBuf Flow Tracker performance overhead.

## Quick Start

**Run ALL benchmarks with GC profiling (recommended):**

```bash
# 1. Build the benchmark JAR
mvn clean package -pl bytebuf-flow-benchmarks

# 2. Run WITHOUT agent (baseline)
cd bytebuf-flow-benchmarks
JMH_GC_PROF=true java -jar target/benchmarks.jar -prof gc

# 3. Run WITH agent (measure overhead)
JMH_GC_PROF=true java "-javaagent:../bytebuf-flow-tracker/target/bytebuf-flow-tracker-1.0.0-SNAPSHOT-agent.jar=include=com.example.bytebuf.benchmarks" \
  -jar target/benchmarks.jar -prof gc
```

**IMPORTANT:**
- GC profiling (`-prof gc`) is **MANDATORY** for all benchmark runs. Memory allocation overhead is a critical metric that cannot be measured without it.
- The `JMH_GC_PROF=true` environment variable is **REQUIRED** to acknowledge you are using GC profiling. The benchmark code validates this is set.

## Benchmark Scenarios

### 1. `simpleAllocateAndRelease`
Measures overhead of tracking a single `Unpooled.buffer()` allocation and `release()`.

### 2. `allocatePassToMethodAndRelease`
Measures overhead when ByteBuf is allocated, passed to a method, returned, and released.
Tests the cost of tracking method entry and exit with `_return` suffix.

### 3. `allocatePassThroughChainAndRelease`
Measures overhead when ByteBuf flows through multiple methods (chain of 3 methods).
Tests accumulated overhead across multiple method calls.

### 4. `tightLoopAllocateAndRelease`
Stress test with 100 allocations and releases in a tight loop.
Tests throughput under high allocation pressure.

### 5. `RandomWalkBenchmark.randomWalk` (NEW)
**Stress test for complex flow patterns** - This benchmark simulates realistic, varied object flows:

- **50 different methods** that accept and return ByteBuf
- **Random path length** (5-50 method calls per iteration)
- **Random method selection** - each iteration takes a unique path
- **ThreadLocal Random** for thread-safe randomization

This pushes the tracker's internal optimizations to their limits by testing:
- Trie depth handling with variable-length paths
- Path diversity with thousands of unique flows
- Hash map performance under varied access patterns
- Memory efficiency with highly branching Trie structures

**Why this matters**: Real applications don't follow predictable paths. This benchmark represents worst-case complexity where every execution takes a different route through your code, maximizing the Trie's memory and lookup costs.

**Running the Random Walk Benchmark:**

```bash
# WITHOUT agent (baseline)
JMH_GC_PROF=true java -jar target/benchmarks.jar ".*RandomWalkBenchmark.randomWalk" -prof gc

# WITH agent (measure overhead under complex flows)
JMH_GC_PROF=true java "-javaagent:../bytebuf-flow-tracker/target/bytebuf-flow-tracker-1.0.0-SNAPSHOT-agent.jar=include=com.example.bytebuf.benchmarks" \
  -jar target/benchmarks.jar ".*RandomWalkBenchmark.randomWalk" -prof gc
```

**What this tests**:
- Tracker behavior with diverse execution paths
- Trie structure handling of path explosion
- Memory scaling characteristics with branching flows

### 6. `DirectMemoryFilteringBenchmark` (NEW - trackDirectOnly Performance)
**Realistic workload demonstrating direct-only tracking benefits**:

- **80% heap allocations** - temporary data, will eventually GC
- **20% direct allocations** - critical I/O, off-heap memory (never GC'd)
- **Multiple scenarios**:
  - `realisticMixedWorkload` - 80/20 mix simulating typical applications
  - `highFrequencyHeapAllocations` - Pure heap (shows zero overhead with trackDirectOnly)
  - `criticalDirectBufferOperations` - Pure direct (always tracked)
  - `ambiguousBufferAllocations` - Uses `buffer()` method (requires isDirect() check)
  - `methodChainOverhead` - Tracks flow through method chains

**Why this matters**: In production, direct memory leaks are critical (never GC'd) while heap leaks eventually clean up. Tracking only direct buffers eliminates overhead for the common case (heap) while catching critical leaks (direct).

**Running the Direct Memory Filtering Benchmark:**

```bash
# BASELINE (no agent)
JMH_GC_PROF=true java -jar target/benchmarks.jar DirectMemoryFilteringBenchmark -prof gc

# TRACK ALL (default - tracks both heap and direct)
JMH_GC_PROF=true java "-javaagent:../bytebuf-flow-tracker/target/bytebuf-flow-tracker-1.0.0-SNAPSHOT-agent.jar=include=com.example.bytebuf.benchmarks" \
  -jar target/benchmarks.jar DirectMemoryFilteringBenchmark -prof gc

# TRACK DIRECT ONLY (zero overhead for 80% heap allocations)
JMH_GC_PROF=true java "-javaagent:../bytebuf-flow-tracker/target/bytebuf-flow-tracker-1.0.0-SNAPSHOT-agent.jar=include=com.example.bytebuf.benchmarks;trackDirectOnly=true" \
  -jar target/benchmarks.jar DirectMemoryFilteringBenchmark -prof gc

# FILTER DIRECT ONLY (runtime filtering with fast path)
JMH_GC_PROF=true java "-javaagent:../bytebuf-flow-tracker/target/bytebuf-flow-tracker-1.0.0-SNAPSHOT-agent.jar=include=com.example.bytebuf.benchmarks;filterDirectOnly=true" \
  -jar target/benchmarks.jar DirectMemoryFilteringBenchmark -prof gc
```

**Expected results**:
- **Baseline**: Best performance (no tracking)
- **Track all**: Some overhead from tracking 100% of allocations
- **trackDirectOnly=true**: Near-baseline performance (heap not instrumented)
- **filterDirectOnly=true**: Slightly slower than trackDirectOnly (runtime checks)

**What this tests**:
- Performance benefit of skipping heap buffer instrumentation
- Fast-path filtering with method name heuristics
- isDirect() overhead for ambiguous allocations (buffer(), compositeBuffer())
- Real-world 80/20 workload patterns

## Default Configuration

- **Mode**: Throughput (operations per second)
- **Forks**: 1
- **Warmup**: 1 iteration @ 5 seconds
- **Measurement**: 3 iterations @ 5 seconds each
- **Profiling**: **GC profiling ALWAYS enabled** (memory allocation is critical for overhead measurement)

## Building

```bash
# From project root
mvn clean package -pl bytebuf-flow-benchmarks

# Or build all modules
mvn clean install
```

This creates an executable JAR: `target/benchmarks.jar` (~8MB)

## Running Benchmarks

### IMPORTANT: Agent vs No Agent

To measure the overhead of the ByteBuf Flow Tracker, you should run benchmarks **twice**:

1. **WITHOUT agent** (baseline performance)
2. **WITH agent** (performance with tracking enabled)

The difference shows the actual overhead of the tracker.

### Baseline Performance (NO AGENT)

Run benchmarks without the agent to get baseline throughput:

```bash
cd bytebuf-flow-benchmarks

# Direct JAR execution (recommended)
JMH_GC_PROF=true java -jar target/benchmarks.jar -prof gc

# Or via Maven (environment variable and GC profiling pre-configured)
mvn exec:exec@run-benchmarks
```

**Note:**
- GC profiling (`-prof gc`) is MANDATORY for meaningful results. Memory allocation is a key overhead metric.
- When using direct JAR execution, the `JMH_GC_PROF=true` environment variable is REQUIRED.
- When using Maven, the environment variable is automatically set in pom.xml.

**Expected output:**
```
Benchmark                                                    Mode  Cnt        Score   Error  Units
ByteBufFlowBenchmark.simpleAllocateAndRelease               thrpt    3  1234567.890 ± ...  ops/s
ByteBufFlowBenchmark.allocatePassToMethodAndRelease         thrpt    3  1234567.890 ± ...  ops/s
ByteBufFlowBenchmark.allocatePassThroughChainAndRelease     thrpt    3  1234567.890 ± ...  ops/s
ByteBufFlowBenchmark.tightLoopAllocateAndRelease            thrpt    3  1234567.890 ± ...  ops/s
```

### With Agent (MEASURE OVERHEAD)

Run benchmarks with the agent attached to measure overhead:

```bash
cd bytebuf-flow-benchmarks

# Ensure agent JAR exists
ls -lh ../bytebuf-flow-tracker/target/bytebuf-flow-tracker-*-agent.jar

# Run with agent
JMH_GC_PROF=true java "-javaagent:../bytebuf-flow-tracker/target/bytebuf-flow-tracker-1.0.0-SNAPSHOT-agent.jar=include=com.example.bytebuf.benchmarks" \
  -jar target/benchmarks.jar \
  -prof gc
```

**Note:**
- The `JMH_GC_PROF=true` environment variable is **REQUIRED**
- The agent argument must be quoted to prevent shell interpretation of semicolons
- The `include=com.example.bytebuf.benchmarks` tells the agent to instrument benchmark classes
- **GC profiling (`-prof gc`) is MANDATORY** - it shows memory allocation overhead which is critical for understanding the tracker's impact

**Expected output:**
```
Benchmark                                                    Mode  Cnt      Score   Error  Units
ByteBufFlowBenchmark.simpleAllocateAndRelease               thrpt    3  123456.789 ± ...  ops/s  (SLOWER than baseline)
ByteBufFlowBenchmark.allocatePassToMethodAndRelease         thrpt    3  123456.789 ± ...  ops/s  (SLOWER than baseline)
ByteBufFlowBenchmark.allocatePassThroughChainAndRelease     thrpt    3  123456.789 ± ...  ops/s  (SLOWER than baseline)
ByteBufFlowBenchmark.tightLoopAllocateAndRelease            thrpt    3  123456.789 ± ...  ops/s  (SLOWER than baseline)
```

### Calculating Overhead

```
Overhead % = ((Baseline Score - Agent Score) / Baseline Score) × 100

Example:
  Baseline: 1,000,000 ops/s
  With Agent: 950,000 ops/s
  Overhead: (1,000,000 - 950,000) / 1,000,000 × 100 = 5%
```

### Custom Configuration

Run specific benchmarks with custom settings:

```bash
# Run ONLY the simple allocate/release benchmark
JMH_GC_PROF=true java -jar target/benchmarks.jar ".*simpleAllocateAndRelease" -prof gc

# Run with agent for comparison
JMH_GC_PROF=true java "-javaagent:../bytebuf-flow-tracker/target/bytebuf-flow-tracker-1.0.0-SNAPSHOT-agent.jar=include=com.example.bytebuf.benchmarks" \
  -jar target/benchmarks.jar ".*simpleAllocateAndRelease" -prof gc

# Custom settings: 2 forks, longer warmup/iterations
JMH_GC_PROF=true java -jar target/benchmarks.jar \
  -f 2 \
  -wi 3 -w 10s \
  -i 5 -r 10s \
  -prof gc

# List all available benchmarks (no GC profiling needed for listing)
java -jar target/benchmarks.jar -l

# Show help (no GC profiling needed for help)
java -jar target/benchmarks.jar -h
```

## Common Profilers

```bash
# GC profiling (memory allocation & collection)
-prof gc

# Stack profiling (hotspots)
-prof stack

# Async profiling (requires async-profiler installed)
-prof async:libPath=/path/to/libasyncProfiler.so

# Multiple profilers
-prof gc -prof stack
```

## Adding More Benchmarks

To add new benchmarks, edit `src/main/java/com/example/bytebuf/benchmarks/ByteBufFlowBenchmark.java`:

```java
@Benchmark
public void myNewBenchmark(Blackhole bh) {
    ByteBuf buffer = Unpooled.buffer(256);
    // ... your benchmark logic ...
    bh.consume(buffer);  // Prevents dead code elimination
    buffer.release();
}
```

**Important:**
- Always use `Blackhole.consume()` to prevent JIT from eliminating code
- Always release ByteBufs to avoid memory leaks
- Use `@OperationsPerInvocation(N)` if you perform N operations in a loop
- Public methods with ByteBuf parameters/returns will be tracked by the agent

## Interpreting Results

### Throughput Comparison

Example output comparing baseline vs agent:

**Without Agent (Baseline):**
```
Benchmark                                                Mode  Cnt        Score      Error  Units
ByteBufFlowBenchmark.simpleAllocateAndRelease           thrpt    3  5000000.000 ±  50000  ops/s
ByteBufFlowBenchmark.allocatePassToMethodAndRelease     thrpt    3  4000000.000 ±  40000  ops/s
ByteBufFlowBenchmark.allocatePassThroughChainAndRelease thrpt    3  2000000.000 ±  20000  ops/s
ByteBufFlowBenchmark.tightLoopAllocateAndRelease        thrpt    3   100000.000 ±   1000  ops/s
```

**With Agent:**
```
Benchmark                                                Mode  Cnt        Score      Error  Units
ByteBufFlowBenchmark.simpleAllocateAndRelease           thrpt    3  4750000.000 ±  47500  ops/s  (5% overhead)
ByteBufFlowBenchmark.allocatePassToMethodAndRelease     thrpt    3  3600000.000 ±  36000  ops/s  (10% overhead)
ByteBufFlowBenchmark.allocatePassThroughChainAndRelease thrpt    3  1700000.000 ±  17000  ops/s  (15% overhead)
ByteBufFlowBenchmark.tightLoopAllocateAndRelease        thrpt    3    95000.000 ±    950  ops/s  (5% overhead)
```

### GC Profiling Metrics

GC profiling shows memory allocation overhead:

```
Benchmark                                                      Mode  Cnt    Score    Error   Units
ByteBufFlowBenchmark.simpleAllocateAndRelease:·gc.alloc.rate   thrpt    3  1500.000 ± 15.000  MB/sec
ByteBufFlowBenchmark.simpleAllocateAndRelease:·gc.count        thrpt    3    50.000            counts
ByteBufFlowBenchmark.simpleAllocateAndRelease:·gc.time         thrpt    3   100.000            ms
```

- **Score**: Operations per second (higher is better)
- **Error**: Margin of error (±)
- **gc.alloc.rate**: Memory allocated per second (lower is better)
- **gc.count**: Number of GC collections (lower is better)
- **gc.time**: Time spent in GC (lower is better)

## Tips for Accurate Overhead Measurement

1. **Always compare**: Run baseline (no agent) and with agent for the same benchmark
2. **Use GC profiling** (`-prof gc`) to see memory allocation overhead
3. **Increase forks** (`-f 2` or more) for more reliable results when publishing numbers
4. **Longer iterations** (`-i 5 -r 10s`) reduce noise and improve accuracy
5. **Warmup is critical** - JIT needs time to optimize (default 5s is usually sufficient)
6. **Close other applications** to reduce system noise
7. **Run multiple times** - Results can vary between runs, especially with agent attached
8. **Watch for GC**: High GC activity indicates memory pressure from tracking
9. **Start simple**: Run `simpleAllocateAndRelease` first to verify setup
10. **Check logs**: Agent logs `[ByteBufFlowAgent]` messages when attached

## Expected Overhead Ranges

**IMPORTANT**: Overhead varies dramatically between microbenchmarks and production applications.

### Microbenchmark Overhead (This Module)

**Measured overhead: ~96% slowdown** (see BENCHMARK-RESULTS.md for details)

These benchmarks measure **pure ByteBuf operations** with zero business logic, representing the **worst-case scenario**. The high overhead is expected because:
- Operations complete in nanoseconds
- Instrumentation overhead dominates execution time
- No real work (I/O, computation, network) to amortize the cost

### Production Application Overhead

**Expected overhead: 5-20%** (based on ARCHITECTURE.md)

In real applications with substantial business logic:
- **Simple operations**: 5-10% (allocate/release with I/O)
- **Method chains**: 10-20% (with actual computation)
- **High throughput**: 5-15% (with network/database calls)

The instrumentation cost is amortized over actual work (network I/O, database queries, serialization), making the relative overhead much lower than in microbenchmarks.

**Key takeaway**: The 96% microbenchmark overhead does NOT represent production performance. Use these benchmarks to understand maximum possible overhead, not typical overhead.
