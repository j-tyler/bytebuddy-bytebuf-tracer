# ByteBuf Flow Tracker - Benchmark Results (Pre-computed + String Extraction)

**Test Date**: 2025-11-13 14:20 UTC
**JVM**: OpenJDK 64-Bit Server VM 21.0.8
**Platform**: Linux 4.4.0
**JMH Version**: 1.37

## Summary

**Result: 35% REGRESSION from pre-computed fully-qualified names, but 12% BETTER than runtime getSimpleName()**

This benchmark tests the CORRECT approach suggested by the user: using ByteBuddy's pre-computed fully-qualified signature and extracting the simple class name at advice entry time using string operations.

## Test Configuration

- **Benchmark Mode**: Throughput (operations per second)
- **Forks**: 1
- **Warmup**: 2 iterations @ 10 seconds
- **Measurement**: 5 iterations @ 10 seconds
- **Profiling**: GC profiling enabled (`-prof gc`)

## Implementation

```java
@Advice.OnMethodEnter
public static void onMethodEnter(
        @Advice.Origin("#t.#m") String fullSignature,  // Pre-computed by ByteBuddy
        @Advice.AllArguments Object[] arguments) {

    // Extract simple class name from pre-computed signature
    // "io.netty.buffer.UnpooledByteBufAllocator.heapBuffer" -> "UnpooledByteBufAllocator.heapBuffer"
    int lastMethodDot = fullSignature.lastIndexOf('.');
    int lastPackageDot = fullSignature.lastIndexOf('.', lastMethodDot - 1);
    String simpleSignature = fullSignature.substring(lastPackageDot + 1);

    tracker.recordMethodCall(arg, simpleSignature, metric);
}
```

## Baseline Performance (WITHOUT Agent)

```
ByteBufFlowBenchmark.allocatePassThroughChainAndRelease:gc.alloc.rate.norm    thrpt    5   320.000 B/op
ByteBufFlowBenchmark.allocatePassToMethodAndRelease:gc.alloc.rate.norm        thrpt    5   320.000 B/op
ByteBufFlowBenchmark.simpleAllocateAndRelease:gc.alloc.rate.norm              thrpt    5   320.000 B/op
ByteBufFlowBenchmark.tightLoopAllocateAndRelease:gc.alloc.rate.norm           thrpt    5   320.000 B/op
```

## Performance WITH Agent (Pre-computed + String Extraction)

```
ByteBufFlowBenchmark.allocatePassThroughChainAndRelease                       thrpt    5   911.839 ±  97.159   ops/s
ByteBufFlowBenchmark.allocatePassThroughChainAndRelease:gc.alloc.rate         thrpt    5     1.237 ±   0.177  MB/sec
ByteBufFlowBenchmark.allocatePassThroughChainAndRelease:gc.alloc.rate.norm    thrpt    5  1422.758 ±  78.734    B/op

ByteBufFlowBenchmark.allocatePassToMethodAndRelease                           thrpt    5   911.454 ± 109.005   ops/s
ByteBufFlowBenchmark.allocatePassToMethodAndRelease:gc.alloc.rate             thrpt    5     1.237 ±   0.188  MB/sec
ByteBufFlowBenchmark.allocatePassToMethodAndRelease:gc.alloc.rate.norm        thrpt    5  1422.729 ±  79.328    B/op

ByteBufFlowBenchmark.simpleAllocateAndRelease                                 thrpt    5   911.072 ± 167.866   ops/s
ByteBufFlowBenchmark.simpleAllocateAndRelease:gc.alloc.rate                   thrpt    5     1.235 ±   0.294  MB/sec
ByteBufFlowBenchmark.simpleAllocateAndRelease:gc.alloc.rate.norm              thrpt    5  1421.193 ±  97.613    B/op

ByteBufFlowBenchmark.tightLoopAllocateAndRelease                              thrpt    5   909.908 ± 146.672   ops/s
ByteBufFlowBenchmark.tightLoopAllocateAndRelease:gc.alloc.rate                thrpt    5     1.234 ±   0.255  MB/sec
ByteBufFlowBenchmark.tightLoopAllocateAndRelease:gc.alloc.rate.norm           thrpt    5  1421.093 ±  88.628    B/op
```

## Detailed Comparison

| Benchmark | Baseline | Pre-computed FQN | Runtime Simple | String Extraction | Overhead (FQN) | Overhead (Extract) | Difference |
|-----------|----------|------------------|----------------|-------------------|----------------|-------------------|------------|
| allocatePassThroughChainAndRelease | 320 B/op | **1055 B/op** | 1622 B/op | **1423 B/op** | 735 B/op | 1103 B/op | **+368 B/op** ❌ |
| allocatePassToMethodAndRelease | 320 B/op | **1055 B/op** | 1621 B/op | **1423 B/op** | 735 B/op | 1103 B/op | **+368 B/op** ❌ |
| simpleAllocateAndRelease | 320 B/op | **1055 B/op** | 1621 B/op | **1421 B/op** | 735 B/op | 1101 B/op | **+366 B/op** ❌ |
| tightLoopAllocateAndRelease | 320 B/op | **1055 B/op** | 1621 B/op | **1421 B/op** | 735 B/op | 1101 B/op | **+366 B/op** ❌ |

**Summary:**
- **Pre-computed FQN (baseline from Idea 2):** 1055 B/op ✅ **BEST**
- **String extraction approach:** **1422 B/op** (35% worse) ⚠️
- **Runtime getSimpleName():** 1621 B/op (54% worse) ❌ **WORST**

## Analysis: Why String Extraction Still Has Overhead

The string extraction approach is better than runtime `getSimpleName()` but still worse than using the pre-computed string directly because:

### 1. Substring Allocation

**String extraction (current):**
```java
String simpleSignature = fullSignature.substring(lastPackageDot + 1);  // Allocates new String
String with_return = simpleSignature + "_return";  // Another allocation for exit advice
```

This allocates:
- Substring object (even though it shares the char array in modern JVMs, the String wrapper is still allocated)
- Additional String for "_return" concatenation
- Estimated cost: ~200-250 B/op

### 2. String Operations Overhead

**Per method call:**
- `lastIndexOf('.')` - scans string twice
- `substring()` - creates String object
- String concatenation for "_return" (uses StringBuilder internally)

These operations happen on EVERY advice entry/exit, adding ~100-150 B/op overhead.

### 3. Loss of Pre-computation Benefit

**Pre-computed FQN (Ideas 2+3):**
```java
@Advice.Origin("#t.#m") String methodSignature
// ByteBuddy embeds ONE constant: "io.netty.buffer.UnpooledByteBufAllocator.heapBuffer"
tracker.recordMethodCall(arg, methodSignature, metric);  // Direct use, no allocation
```

**String extraction:**
```java
@Advice.Origin("#t.#m") String fullSignature  // ByteBuddy embeds this
String simpleSignature = fullSignature.substring(...);  // Runtime allocation
tracker.recordMethodCall(arg, simpleSignature, metric);  // Uses allocated string
```

The extraction loses the benefit of directly using the embedded constant.

## Conclusions

### Performance Ranking (Best to Worst)

1. **Pre-computed FQN (1055 B/op)** ✅ **BEST**
   - Fully qualified names embedded at instrumentation time
   - No runtime overhead
   - Direct string constant usage

2. **String extraction (1422 B/op)** ⚠️ **MIDDLE**
   - Pre-computed FQN + runtime extraction
   - Substring and concatenation overhead
   - 35% worse than FQN, but 12% better than getSimpleName()

3. **Runtime getSimpleName() (1621 B/op)** ❌ **WORST**
   - Method call + string concatenation
   - 54% worse than FQN

### Recommendation

**Use pre-computed fully-qualified names** (`@Advice.Origin("#t.#m")`) despite longer strings.

**Why accept longer strings?**
- Memory cost of longer strings (~20 chars per signature) is ~20-40 bytes
- Runtime extraction cost is ~367 bytes/op (much higher!)
- String interning means each unique signature is stored only once
- For typical applications with ~100-500 unique signatures, extra memory is:
  - FQN approach: ~2-8 KB (one-time cost)
  - Extraction approach: ~367 B/op × operations/second = ongoing cost

**When to use extraction approach:**
- If you have EXTREMELY high cardinality of signatures (10,000+)
- If memory pressure is severe and you can't afford 20-40 KB
- For most applications, the FQN approach is better

## Alternative: Cache Extracted Names

One optimization not tested here would be to cache the extracted simple names:

```java
private static final ConcurrentHashMap<String, String> SIMPLE_NAME_CACHE = new ConcurrentHashMap<>();

String simpleSignature = SIMPLE_NAME_CACHE.computeIfAbsent(fullSignature, fqn -> {
    int lastMethodDot = fqn.lastIndexOf('.');
    int lastPackageDot = fqn.lastIndexOf('.', lastMethodDot - 1);
    return fqn.substring(lastPackageDot + 1);
});
```

This would:
- Extract each signature only once
- Trade memory for CPU (similar to string interning)
- Likely perform close to FQN approach after warm-up

However, this adds:
- ConcurrentHashMap overhead
- Additional memory for the cache
- Code complexity

## Git Commits

**Changes Made:**
```
- Fixed implementation to extract from pre-computed FQN
- Changed from runtime getSimpleName() to substring extraction
- Modified ByteBufTrackingAdvice.java
- Modified ByteBufConstructorAdvice.java
- Modified ByteBufConstructionAdvice.java
```

**Status:** ⚠️ Better than getSimpleName(), but still 35% worse than FQN

---

**Generated**: 2025-11-13 14:22 UTC
**Session**: claude/review-docs-memory-011CV55i9Buz3jhFEu9c5B8h
**Branch**: claude/review-docs-memory-011CV55i9Buz3jhFEu9c5B8h
