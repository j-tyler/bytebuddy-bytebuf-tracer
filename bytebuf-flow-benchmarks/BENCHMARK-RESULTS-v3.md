# ByteBuf Flow Tracker - Benchmark Results v3 (Random Walk)

**Test Date**: 2025-11-11
**JVM**: OpenJDK 64-Bit Server VM 21.0.8
**Platform**: Linux 4.4.0
**JMH Version**: 1.37

## Test Configuration

- **Benchmark Mode**: Throughput (operations per second)
- **Forks**: 1
- **Warmup**: 1 iteration @ 5 seconds
- **Measurement**: 3 iterations @ 5 seconds each
- **Profiling**: GC profiling enabled (`-prof gc`)

⚠️ **Note**: Results have high variance (±151% error margin) due to single-fork execution. For production decisions, re-run with `-f 3` or more forks.

## RandomWalkBenchmark Details

This benchmark tests the tracker under complex flow patterns:

- **50 different methods** that accept and return ByteBuf
- **Variable path length**: 5-50 method calls per iteration (random)
- **Random method selection**: Each iteration takes a unique path
- **Expected average**: ~27.5 method calls per iteration (uniform distribution)

## Baseline Performance (WITHOUT Agent)

```
Benchmark                                           Mode  Cnt        Score       Error   Units
RandomWalkBenchmark.randomWalk                     thrpt    3  2477237.002 ± 90688.851   ops/s
RandomWalkBenchmark.randomWalk:gc.alloc.rate       thrpt    3      755.906 ±    26.854  MB/sec
RandomWalkBenchmark.randomWalk:gc.alloc.rate.norm  thrpt    3      320.001 ±     0.001    B/op
RandomWalkBenchmark.randomWalk:gc.count            thrpt    3       77.000              counts
RandomWalkBenchmark.randomWalk:gc.time             thrpt    3       65.000                  ms
```

## Performance WITH Agent (Tracking Enabled)

```
Benchmark                                           Mode  Cnt       Score        Error   Units
RandomWalkBenchmark.randomWalk                     thrpt    3  634370.876 ± 960770.713   ops/s
RandomWalkBenchmark.randomWalk:gc.alloc.rate       thrpt    3     576.623 ±    850.923  MB/sec
RandomWalkBenchmark.randomWalk:gc.alloc.rate.norm  thrpt    3     953.365 ±     41.471    B/op
RandomWalkBenchmark.randomWalk:gc.count            thrpt    3      18.000               counts
RandomWalkBenchmark.randomWalk:gc.time             thrpt    3     675.000                   ms
```

## Summary

| Metric | Baseline | With Agent | Difference |
|--------|----------|------------|------------|
| **Throughput** | 2,477,237 ops/s | 634,371 ops/s | 3.9x slower |
| **Memory/op** | 320 B | 953 B | +633 B (+198%) |
| **GC time** | 65 ms | 675 ms | 10.4x longer |

## Comparison with Simple Benchmarks

| Benchmark | Baseline (ops/s) | With Agent (ops/s) | Slowdown |
|-----------|------------------|-------------------|----------|
| Simple allocate/release | 20,479,784 | 857,197 | 23.9x |
| Single method call | 21,640,668 | 855,479 | 25.3x |
| 3-method chain | 20,542,531 | 813,887 | 25.2x |
| Random walk (5-50 methods) | 2,477,237 | 634,371 | 3.9x |

## Reproducing These Results

### Baseline (No Agent)

```bash
cd bytebuf-flow-benchmarks
java -jar target/benchmarks.jar ".*RandomWalkBenchmark.randomWalk" -prof gc
```

### With Agent

```bash
cd bytebuf-flow-benchmarks
java "-javaagent:../bytebuf-flow-tracker/target/bytebuf-flow-tracker-1.0.0-SNAPSHOT-agent.jar=include=com.example.bytebuf.benchmarks" \
  -jar target/benchmarks.jar ".*RandomWalkBenchmark.randomWalk" -prof gc
```

### For More Reliable Results

```bash
# Increase forks and iterations to reduce variance
java -jar target/benchmarks.jar ".*RandomWalkBenchmark.randomWalk" -prof gc -f 3 -i 5 -r 10s
```
