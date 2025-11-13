# ByteBuf Flow Tracker - Architecture

## Quick Reference

**Concepts**: Java agent tracks ByteBuf (Netty buffer) flow using ByteBuddy instrumentation. Stores paths in Trie. Leaks = non-zero refCount at leaf nodes.

**Key Files**:
1. `agent/ByteBufFlowAgent.java` - Entry point, argument parsing
2. `ByteBufFlowTracker.java` - Core tracking singleton
3. `agent/ByteBufTrackingAdvice.java` - Method interception
4. `trie/BoundedImprintTrie.java` - Path storage (bounded, lock-free)
5. `bytebuf-flow-example/` - Usage examples

**Modification Patterns**:
- New allocator: `ByteBufConstructionTransformer.java`
- Tracking behavior: `ByteBufTrackingAdvice.java`
- Memory limits: `BoundedImprintTrie` constructor
- Custom tracker: Implement `ObjectTrackerHandler`

**Design Patterns**: Singleton, ThreadLocal (re-entrance guard), WeakReference (GC detection), Lock-free (ConcurrentHashMap), Object Pooling (Stormpot for FlowState)

---

## Project Structure

Multi-module Maven: `bytebuf-flow-tracker/` (library + agent JAR) and `bytebuf-flow-example/` (demos).

**Core packages**:
- `agent/` - ByteBufFlowAgent, ByteBufTrackingAdvice, transformers
- `active/` - WeakActiveFlow, WeakActiveTracker (live object tracking)
- `trie/` - BoundedImprintTrie, ImprintNode (path storage)
- `view/` - TrieRenderer (output formats)
- Root - ByteBufFlowTracker (main singleton), ObjectTrackerHandler (extensibility)

## Core Components

### 1. ByteBufFlowTracker
Main singleton. Methods: `recordMethodCall()`, `getTrie()`, `getActiveFlowCount()`, `reset()`. Thread-safe (ConcurrentHashMap). Tracks via `identityHashCode()`. Allocator methods = root nodes. Entry (params) and exit (`_return` suffix) tracking. TRACKED_PARAMS ThreadLocal prevents duplicates.

**Active Flow Monitoring**: `WeakActiveTracker` uses `ConcurrentHashMap<Integer, WeakActiveFlow>`. Each flow holds WeakReference, object ID, current trie node, depth (plain int, not volatile).

**Lifecycle**: First seen → create flow + root. Each call → update node. Metric=0 → remove (clean). GC'd → enqueue (leak).

**Lazy GC Processing**: Per-thread counter (ThreadLocal). Process GC queue on first call + every 100 calls. Batch size 100. Saves ~200-490ms/sec @ 10M calls/sec. Mutable CallCounter class avoids Integer boxing (~100-300ms/sec savings). First-call safeguard ensures short-lived threads still detect leaks. Call `ensureGCProcessed()` before rendering.

**Memory**: Active tracking ~80 bytes/object. Trie max 1M nodes × 100 bytes = 100MB. Upper bound: `(concurrent × 80) + 100MB`.

### 2. BoundedImprintTrie
Tree stores method paths. Nodes: signature, bucketed refCount (0/1-2/3-5/6+), traversal count, outcomes (leaf only), children. String interning. Lock-free (ConcurrentHashMap). No allocations during tracking.

**Bounds**: 1M nodes (default), 100 depth, 1000 children/node. Stop-on-limit (no eviction). RefCount bucketing reduces path explosion.

### 3. ByteBufFlowAgent
Entry point. Parses args (`include=`, `exclude=`, `trackConstructors=`), installs ByteBuddy transforms, registers JMX. Instruments public/protected methods with ByteBuf param/return in included packages.

### 4. ByteBufTrackingAdvice
Intercepts methods. `@OnMethodEnter`: check re-entrance guard, track params (no suffix), store in TRACKED_PARAMS ThreadLocal. `@OnMethodExit`: track return (`_return` suffix if not already tracked as param).

**Re-entrance Guard**: ThreadLocal<Boolean> prevents infinite recursion - critical because tracking code itself may call instrumented methods. Without guard: StackOverflowError. Side effect: methods called during tracking won't appear in traces (intentional).

**Suffixes**: `methodName` (entry), `methodName_return` (exit), `<init>`, `<init>_return`. Special: `release()` only tracked when refCnt→0.

**Example flow tree**:
```
ROOT: UnpooledByteBufAllocator.heapBuffer [count=1]
└── Client.allocate_return [ref=1, count=1]
    └── Handler.process [ref=1, count=1]
        └── Handler.cleanup_return [ref=0, count=1]  ✓ Clean
```

### 5. TrieRenderer
Formats: `renderIndentedTree()` (human), `renderForLLM()` (token-efficient), `renderSummary()` (stats). Detects leaf nodes with metric>0 as leaks.

### 6. ObjectTrackerHandler
Extensibility. Interface: `shouldTrack()`, `getMetric()`, `getObjectType()`. Default: ByteBufObjectHandler. Registry: ObjectTrackerRegistry.

### 7. Production Metrics
Push leak metrics to monitoring (Datadog, Prometheus). **bytebuf-flow-api** (zero deps): MetricType enum, MetricSnapshot, MetricHandler interface. **bytebuf-flow-tracker**: MetricHandlerRegistry (CopyOnWriteArrayList), MetricPushScheduler (daemon, 60s), MetricCollector (walks trie).

**Architecture**: Scheduler (60s) → Collector walks trie → MetricHandlerRegistry → Your handlers (Datadog, Prometheus, etc). Thread-safe, per-handler exception isolation. Zero-dep API = production apps need no ByteBuddy/Netty deps.

Flow format: `root=M|final_ref=N|leak_count=N|leak_rate=N%|path=A->B->C`. Selective capture (only requested metrics). Registration: programmatic, system property, ServiceLoader.

## How It Works

**Flow**: JVM loads agent → ByteBufFlowAgent.premain() → ByteBuddy transforms classes (adds advice) → Runtime: advice checks params/return, records in tracker → Tracker updates Trie.

**Allocator Root**: Instruments allocator methods (UnpooledByteBufAllocator, Unpooled, etc.) as root nodes. Avoids stack traces. ByteBufConstructionTransformer targets only terminal methods (2-arg) to avoid telescoping duplicates.

**Release Tracking**: Only track `release()` when refCnt→0 (prevents clutter). Clean trees, clear leak status.

**Concurrency**: ConcurrentHashMap (lock-free). ThreadLocal re-entrance guard prevents infinite recursion. ~5-10% overhead, zero allocations, no stack traces.

## Build & Testing

**Maven**: Parent POM manages deps. Library module uses Shade plugin for fat JAR (`*-agent.jar`) with all deps + MANIFEST (`Premain-Class`, `Can-Retransform-Classes`). Example module shows integration.

**Tests**: Unit (JUnit 5, Mockito, verify tree/leaks/formats), Integration (real ByteBuf usage, leak demos).

## Extensibility

**Custom Objects**: Implement `ObjectTrackerHandler` (`shouldTrack()`, `getMetric()`, `getObjectType()`), register with ObjectTrackerRegistry, configure agent.

Example:
```java
public class ConnectionTracker implements ObjectTrackerHandler {
    public boolean shouldTrack(Object obj) { return obj instanceof Connection; }
    public int getMetric(Object obj) { return ((Connection)obj).isClosed() ? 0 : 1; }
    public String getObjectType() { return "DatabaseConnection"; }
}
ObjectTrackerRegistry.setHandler(new ConnectionTracker());
```

**Wrapper Classes**: Use `trackConstructors=com.example.Message` to instrument constructors, maintains flow when ByteBuf wrapped. Wildcards supported.

## Performance

**Overhead**: ByteBuddy transformation (class load), advice execution, ConcurrentHashMap ops, refCnt() calls.

**Optimizations**: Zero allocations, no stack traces, lock-free, lazy rendering, identity hash, lazy GC (every 100 calls saves ~200-490ms/sec @ 10M), mutable ThreadLocal counter (avoids boxing, ~100-300ms/sec), non-volatile fields (~50ms/sec), cached identityHashCode (~50ms/sec). Total: ~405-900ms/sec reduction @ 10M calls/sec.

### trackDirectOnly
Direct leaks critical (never GC'd, crash JVM). Hybrid filtering: compile-time (don't instrument heapBuffer) + runtime (isDirect() check for ambiguous methods).

| Method | Default | trackDirectOnly |
|--------|---------|-----------------|
| heapBuffer() | Track | **Not instrumented** |
| directBuffer() | Track | Track |
| buffer/wrapped/composite | Track | Check isDirect() (~5ns) |

80% allocations are heap → major savings. wrappedBuffer(directBuf) correctly inherits isDirect()=true.

### FlowState Pooling
High churn creates GC pressure. Stormpot pools FlowState objects (size 1024, ~32KB). WeakActiveFlow (unpoolable) delegates to PooledFlowState. Fallback to unpooled on exhaustion. Lock-free, thread-safe. Files: FlowState.java, FlowStatePool.java, WeakActiveFlow.java, WeakActiveTracker.java.

**Best Practices**: Narrow includes, exclude noise, use trackDirectOnly in prod, sample if needed.

## JMX & Development

**MBean** (`com.example:type=ByteBufFlowTracker`): `getTreeView()`, `getLLMView()`, `getSummary()`, `reset()`. Use with JConsole, monitoring tools.

**Build**: `mvn clean install` (everything), `mvn test` (tests), `mvn exec:java` (example).

**Debug**: Add logging in ByteBufFlowAgent, use `-verbose:class`, JConsole.

**Integration**: (1) Local Maven repo (`mvn install` + add dep), (2) Git submodule, (3) Copy source. All need fat JAR for -javaagent.

## Design Decisions

**Trie**: Shared prefixes save memory, natural hierarchy, efficient lookup vs flat list/map alternatives.

**Allocator Root**: Complete lifecycle from creation, zero stack trace overhead, automatic, shows allocator context (heap/direct, pooled/unpooled), works across threads, prevents telescoping (terminal methods only). Alt: stack traces (expensive), first-touch (loses context), manual (code changes).

**Release-Only-When-Zero**: Clean trees (only final release), clear leak detection (non-zero leaf), no ambiguity vs tracking all releases (clutters) or none (no clean path detection).

## Future

Sampling, time tracking, memory tracking, flame graphs, export formats (DOT, Mermaid), Web UI, alerts.

Apache License 2.0
