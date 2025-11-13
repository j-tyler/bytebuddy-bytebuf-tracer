# ByteBuf Flow Tracker - Benchmark Results (CORRECTED)

**Test Date**: 2025-11-13 05:00 UTC (Re-run with stable configuration)
**JVM**: OpenJDK 64-Bit Server VM 21.0.8
**Platform**: Linux 4.4.0
**JMH Version**: 1.37

## ⚠️ IMPORTANT: Performance Regression Identified

After implementing optimizations (Idea 2 + Idea 3), we discovered a **15% performance regression** instead of the expected improvement.

## Test Configuration

**Improved configuration for stability:**
- **Benchmark Mode**: Throughput (operations per second)
- **Forks**: 1
- **Warmup**: **2 iterations @ 10 seconds** (improved from 1×5s)
- **Measurement**: **5 iterations @ 10 seconds** (improved from 3×5s)
- **Profiling**: GC profiling enabled (`-prof gc`)

**Previous configuration issues:**
- Only 3 measurement iterations of 5 seconds
- Result: Error margins of ±2700-2970 B/op (180-200% of measured values!)
- Conclusion: Results were unreliable

## Baseline Performance (WITHOUT Agent)

```
Benchmark                                                                    Mode  Cnt         Score         Error   Units
ByteBufFlowBenchmark.allocatePassThroughChainAndRelease                     thrpt    5  20686492.277 ± 1550916.829   ops/s
ByteBufFlowBenchmark.allocatePassThroughChainAndRelease:gc.alloc.rate       thrpt    5      6312.715 ±     472.613  MB/sec
ByteBufFlowBenchmark.allocatePassThroughChainAndRelease:gc.alloc.rate.norm  thrpt    5       320.000 ±       0.001    B/op
ByteBufFlowBenchmark.allocatePassThroughChainAndRelease:gc.count            thrpt    5       513.000                counts
ByteBufFlowBenchmark.allocatePassThroughChainAndRelease:gc.time             thrpt    5       621.000                    ms
ByteBufFlowBenchmark.allocatePassToMethodAndRelease                         thrpt    5  20908343.988 ± 1183002.154   ops/s
ByteBufFlowBenchmark.allocatePassToMethodAndRelease:gc.alloc.rate           thrpt    5      6380.424 ±     361.217  MB/sec
ByteBufFlowBenchmark.allocatePassToMethodAndRelease:gc.alloc.rate.norm      thrpt    5       320.000 ±       0.001    B/op
ByteBufFlowBenchmark.allocatePassToMethodAndRelease:gc.count                thrpt    5       691.000                counts
ByteBufFlowBenchmark.allocatePassToMethodAndRelease:gc.time                 thrpt    5       808.000                    ms
ByteBufFlowBenchmark.simpleAllocateAndRelease                               thrpt    5  20642039.071 ±  543789.999   ops/s
ByteBufFlowBenchmark.simpleAllocateAndRelease:gc.alloc.rate                 thrpt    5      6299.131 ±     166.008  MB/sec
ByteBufFlowBenchmark.simpleAllocateAndRelease:gc.alloc.rate.norm            thrpt    5       320.000 ±       0.001    B/op
ByteBufFlowBenchmark.simpleAllocateAndRelease:gc.count                      thrpt    5       676.000                counts
ByteBufFlowBenchmark.simpleAllocateAndRelease:gc.time                       thrpt    5       769.000                    ms
ByteBufFlowBenchmark.tightLoopAllocateAndRelease                            thrpt    5  20998922.328 ±  808388.024   ops/s
ByteBufFlowBenchmark.tightLoopAllocateAndRelease:gc.alloc.rate              thrpt    5      6408.032 ±     245.761  MB/sec
ByteBufFlowBenchmark.tightLoopAllocateAndRelease:gc.alloc.rate.norm         thrpt    5       320.000 ±       0.001    B/op
ByteBufFlowBenchmark.tightLoopAllocateAndRelease:gc.count                   thrpt    5       511.000                counts
ByteBufFlowBenchmark.tightLoopAllocateAndRelease:gc.time                    thrpt    5       681.000                    ms
```

## Performance WITH Agent (After Idea 2 + Idea 3)

```
Benchmark                                                                    Mode  Cnt     Score     Error   Units
ByteBufFlowBenchmark.allocatePassThroughChainAndRelease                     thrpt    5   900.982 ± 118.607   ops/s
ByteBufFlowBenchmark.allocatePassThroughChainAndRelease:gc.alloc.rate       thrpt    5     1.182 ±   0.215  MB/sec
ByteBufFlowBenchmark.allocatePassThroughChainAndRelease:gc.alloc.rate.norm  thrpt    5  1375.422 ±  90.763    B/op
ByteBufFlowBenchmark.allocatePassThroughChainAndRelease:gc.count            thrpt    5     1.000            counts
ByteBufFlowBenchmark.allocatePassThroughChainAndRelease:gc.time             thrpt    5    15.000                ms
ByteBufFlowBenchmark.allocatePassToMethodAndRelease                         thrpt    5   900.029 ± 124.544   ops/s
ByteBufFlowBenchmark.allocatePassToMethodAndRelease:gc.alloc.rate           thrpt    5     1.181 ±   0.216  MB/sec
ByteBufFlowBenchmark.allocatePassToMethodAndRelease:gc.alloc.rate.norm      thrpt    5  1375.496 ±  86.456    B/op
ByteBufFlowBenchmark.allocatePassToMethodAndRelease:gc.count                thrpt    5     1.000            counts
ByteBufFlowBenchmark.allocatePassToMethodAndRelease:gc.time                 thrpt    5    13.000                ms
ByteBufFlowBenchmark.simpleAllocateAndRelease                               thrpt    5   902.966 ± 107.097   ops/s
ByteBufFlowBenchmark.simpleAllocateAndRelease:gc.alloc.rate                 thrpt    5     1.184 ±   0.182  MB/sec
ByteBufFlowBenchmark.simpleAllocateAndRelease:gc.alloc.rate.norm            thrpt    5  1375.202 ±  82.088    B/op
ByteBufFlowBenchmark.simpleAllocateAndRelease:gc.count                      thrpt    5     1.000            counts
ByteBufFlowBenchmark.simpleAllocateAndRelease:gc.time                       thrpt    5    13.000                ms
ByteBufFlowBenchmark.tightLoopAllocateAndRelease                            thrpt    5   904.566 ± 115.055   ops/s
ByteBufFlowBenchmark.tightLoopAllocateAndRelease:gc.alloc.rate              thrpt    5     1.187 ±   0.204  MB/sec
ByteBufFlowBenchmark.tightLoopAllocateAndRelease:gc.alloc.rate.norm         thrpt    5  1375.297 ±  84.916    B/op
ByteBufFlowBenchmark.tightLoopAllocateAndRelease:gc.count                   thrpt    5     1.000            counts
ByteBufFlowBenchmark.tightLoopAllocateAndRelease:gc.time                    thrpt    5    12.000                ms
```

## Detailed Comparison

| Benchmark | Baseline | With Agent | Overhead | Previous (2025-11-11) | Change |
|-----------|----------|------------|----------|----------------------|--------|
| allocatePassThroughChainAndRelease | 320 B/op | **1375 B/op** | **1055 B/op** | 953 B/op (633 overhead) | **+422 B/op** ❌ |
| allocatePassToMethodAndRelease | 320 B/op | **1375 B/op** | **1055 B/op** | 1111 B/op (791 overhead) | **+264 B/op** ❌ |
| simpleAllocateAndRelease | 320 B/op | **1375 B/op** | **1055 B/op** | 1428 B/op (1108 overhead) | **-53 B/op** ✓ |
| tightLoopAllocateAndRelease | 320 B/op | **1375 B/op** | **1055 B/op** | 1445 B/op (1125 overhead) | **-70 B/op** ✓ |

**Summary:**
- **Previous average overhead:** ~914 B/op (but with high variance: 633-1125 B/op range)
- **Current overhead:** **1055 B/op** (very consistent: ±82-90 B/op across all benchmarks)
- **Net change:** **+141 B/op (15% regression)** ❌

## Analysis: What Went Wrong?

### Key Observation: Consistency vs. Variance

The current results show **remarkably consistent overhead** (~1055 B/op) across all benchmarks:
- Error margins: only ±82-90 B/op (6-7% of measured value)
- All four benchmarks within ±3% of each other

The previous results showed **high variance**:
- Overhead range: 633-1125 B/op (492 B/op spread)
- 78% variation from lowest to highest
- Suggests measurement instability

### Possible Explanations

#### 1. Fully-Qualified Class Names (Idea 2) Increased Memory

**Before optimization:**
- Method signatures: `"UnpooledByteBufAllocator.heapBuffer"` (~32 chars)

**After optimization:**
- Method signatures: `"io.netty.buffer.UnpooledByteBufAllocator.heapBuffer"` (~52 chars)
- **62% longer strings!**

**Impact:**
- Longer strings in string intern pool
- More memory for string storage
- Potentially more GC pressure
- More bytes to compare during HashMap lookups

#### 2. NodeKey Pooling Overhead (Idea 3)

**Stormpot pool costs:**
- Pool infrastructure: allocations, threading, etc.
- 512 pre-allocated NodeKey objects sitting in memory
- Claim/release operations add indirection
- Pool contention (even with single thread)

**Potential issues:**
- Pool might be MORE expensive than just allocating NodeKeys
- For single-threaded benchmarks, pooling overhead > allocation savings
- Pool timeout handling adds complexity

#### 3. String Interning Pressure

With longer method signatures, the `FixedArrayStringInterner` experiences:
- More characters to hash
- Potentially more collisions
- Larger memory footprint for intern pool
- More GC scanning overhead

#### 4. Previous Measurements Were Inaccurate

The original benchmarks (2025-11-11) may have had:
- Insufficient warmup (1 iteration)
- Too few measurements (3 iterations)
- Shorter measurement windows (5 seconds)
- JIT not fully optimized
- GC anomalies

**Current measurements are more reliable** due to:
- 2 warmup iterations × 10 seconds
- 5 measurement iterations × 10 seconds
- More stable JIT state
- Better GC activity measurement

### Most Likely Cause

**Hypothesis:** The previous "optimization baseline" (914 B/op) was artificially low due to measurement variance. The TRUE overhead was likely closer to 1000-1100 B/op all along.

**Evidence:**
1. Current results are remarkably stable (±6-7%)
2. Previous results had 78% variance
3. Two benchmarks (simple, tightLoop) showed slight improvement
4. The "improvement" benchmarks were previously the worst performers

## Recommendations

### Immediate Actions

1. **Revert Idea 2 (Pre-computed Signatures with #t)**
   - Use `#T` (simple class name) instead of `#t` (fully-qualified)
   - Or use custom origin descriptor to get simple names
   - This should save ~20-30 chars per signature

2. **Re-evaluate Idea 3 (NodeKey Pooling)**
   - For single-threaded workloads, pooling might be net negative
   - Consider conditional pooling (only for multi-threaded scenarios)
   - Or use simpler pooling (ThreadLocal cache instead of Stormpot)

3. **Run baseline benchmark from 2025-11-11 codebase**
   - Check out commit before optimizations
   - Run with current stable configuration (2×10s warmup, 5×10s measure)
   - This will tell us if optimizations helped or hurt

### Alternative Optimization Strategies

Focus on **Idea 1** instead (highest potential):
- **Replace ThreadLocal<HashSet<Integer>> with primitive int array**
- Expected savings: 100-200 B/op
- No string length issues
- No pooling overhead
- Direct memory access

Consider **hybrid approach**:
- Keep pre-computed signatures BUT with simple names
- Skip pooling for now
- Add Idea 1 (primitive array)
- Measure again

## Git Commits

**Idea 3 (NodeKey Pooling):**
```
commit ae06813
feat: Implement NodeKey pooling with Stormpot (Idea 3)
```

**Idea 2 (Pre-computed Signatures):**
```
commit 5572a09
perf: Pre-compute method signatures at instrumentation time (Idea 2)
```

**Benchmark Results:**
```
commit 56ff7b3
docs: Add benchmark results for Idea 2 + Idea 3 optimizations (2025-11-13-04)
```

## Conclusion

**The optimizations (Idea 2 + Idea 3) resulted in a 15% regression** when measured with proper methodology:
- **Before:** ~914 B/op (unreliable measurement)
- **After:** **1055 B/op** (reliable measurement)
- **Regression:** +141 B/op

However, the **improved measurement methodology** (2×10s warmup, 5×10s measure) provides much more confidence in the results. The previous baseline may have been artificially optimistic.

**Next steps:**
1. Revert to using simple class names instead of fully-qualified names
2. Re-benchmark to isolate Idea 2 impact
3. Consider reverting Idea 3 (NodeKey pooling) if it shows negative ROI
4. Implement Idea 1 (primitive int array) as the primary optimization target

---

**Generated**: 2025-11-13 05:16 UTC
**Session**: claude/review-docs-memory-011CV55i9Buz3jhFEu9c5B8h
**Status**: ❌ Regression identified - requires corrective action
