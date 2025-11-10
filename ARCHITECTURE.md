# ByteBuf Flow Tracker - Architecture

## Project Structure

Multi-module Maven project with clean separation of concerns:

```
bytebuddy-bytebuf-tracer/
├── pom.xml                       # Parent POM (dependency management)
├── bytebuf-flow-tracker/         # Module 1: Reusable library + agent
│   ├── pom.xml                   # Builds library + fat agent JAR
│   ├── src/main/java/            # Tracking logic, agent, handlers
│   │   └── com/example/bytebuf/tracker/
│   │       ├── ByteBufFlowTracker.java         # Main tracker singleton
│   │       ├── ObjectTrackerHandler.java       # Interface for custom trackers
│   │       ├── ObjectTrackerRegistry.java      # Handler registry
│   │       ├── agent/
│   │       │   ├── ByteBufFlowAgent.java       # Java agent entry point
│   │       │   ├── ByteBufFlowMBean.java       # JMX interface
│   │       │   └── ByteBufTrackingAdvice.java  # ByteBuddy advice
│   │       ├── active/
│   │       │   ├── WeakActiveFlow.java         # WeakReference wrapper for objects
│   │       │   └── WeakActiveTracker.java      # Manages active object tracking
│   │       ├── trie/
│   │       │   ├── BoundedImprintTrie.java     # Bounded Trie with memory limits
│   │       │   └── ImprintNode.java            # Trie node with outcome tracking
│   │       └── view/
│   │           └── TrieRenderer.java           # Output formatting
│   └── src/test/java/            # Comprehensive tests
└── bytebuf-flow-example/         # Module 2: Usage examples
    ├── pom.xml                   # Shows integration patterns
    └── src/main/java/            # Demo apps (ByteBuf & custom objects)
```

## Core Components

### 1. ByteBufFlowTracker

**Purpose**: Main tracking logic with allocator-root tracking and entry/exit recording

**Key Methods**:
- `recordMethodCall(Object, String, String, int)` - Track a method call
- `getTrie()` - Get the underlying Trie structure
- `getActiveFlowCount()` - Get count of currently tracked objects
- `reset()` - Clear all tracking data

**Design**:
- Singleton pattern for global access
- Thread-safe using ConcurrentHashMap for object-to-root mapping
- Uses `System.identityHashCode()` to track object identity
- ByteBuf allocator methods (UnpooledByteBufAllocator.heapBuffer, Unpooled.buffer, etc.) become root nodes
- Entry tracking: methods receiving ByteBuf as parameters
- Exit tracking: methods returning ByteBuf (tracked with `_return` suffix)
- TRACKED_PARAMS ThreadLocal prevents duplicate tracking when object is both parameter and return value

**Active Flow Monitoring**:

The tracker uses **WeakActiveTracker** which maintains a `ConcurrentHashMap<Integer, WeakActiveFlow>` that maps object identity hash codes to weak references.

**WeakActiveFlow** holds:
- WeakReference to the tracked object
- Object ID (identity hash code)
- Current node in the Trie
- Current depth in the flow (plain `int`, not `volatile` - stale reads are benign, saves ~50ms/sec)

**Lifecycle**:
1. When object first seen → Create WeakActiveFlow, create root node
2. On each method call → Update WeakActiveFlow to point to new node
3. When metric reaches 0 (e.g., refCnt=0) → Remove from activeFlows (clean release)
4. When object is GC'd → Automatically enqueued to ReferenceQueue (leak detected)

**Lazy GC Queue Processing**:

The tracker uses a **lazy processing strategy** to minimize overhead on the hot path (every tracked method call):

- **Per-thread counter**: Each thread maintains an independent call counter via `ThreadLocal<CallCounter>`
- **First call on new thread**: Processes GC queue immediately (critical for short-lived threads)
- **Subsequent calls**: Processes GC queue every 100 calls (low overhead for long-running threads)
- **Batch size**: Processes up to 100 GC'd objects per batch (prevents blocking)

**Why this strategy**:
- Saves ~200-490ms/sec @ 10M calls/sec by avoiding queue polling on every call
- Mutable `CallCounter` class avoids Integer boxing/unboxing overhead (~100-300ms/sec savings)
- First-call safeguard ensures short-lived threads still detect leaks
- Balance between performance and timely leak detection

**For renderers**: Call `ensureGCProcessed()` before rendering to get current state

**Real-time monitoring**:
```java
tracker.getActiveFlowCount()  // Returns activeFlows.size()
```

This provides:
- Count of objects currently being tracked (not yet released/closed)
- Detection of object accumulation before they become leaks
- Available via programmatic API and JMX MBean
- Useful for debugging and runtime monitoring

**Memory management**: Objects automatically removed from tracking when:
- Their metric reaches 0 (e.g., ByteBuf.refCnt() == 0) → Marked as clean release
- They are garbage collected → Automatically detected and marked as leak
- They are explicitly removed via reset()
- JVM shuts down (map cleared)

**Bounded Memory Guarantees**:
- Active tracking: `O(concurrent_objects) × 80 bytes per object`
  - WeakActiveFlow object: ~48 bytes
  - ConcurrentHashMap entry overhead: ~32 bytes
- Trie storage: Max 1M nodes (configurable) × ~100 bytes = ~100MB max
- Total provable upper bound: `(concurrent × 80) + 100MB + ~50KB overhead`

### 2. BoundedImprintTrie

**Purpose**: Bounded data structure for storing method call paths with provable memory limits

**Design**:
- Hierarchical tree structure where each node (ImprintNode) represents a method call
- Each node stores:
  - Method signature (ClassName.methodName)
  - Bucketed refCount (0=zero, 1-2=low, 3-5=medium, 6+=high)
  - Traversal count (how many objects passed through this node)
  - Outcome counts (clean releases vs leaks) for leaf nodes only
  - Children nodes (subsequent method calls)
- Trie structure shares common path prefixes to minimize memory
- String interning further reduces memory usage

**Bounded Memory Features**:
- **Global node limit**: 1M nodes by default (configurable)
- **Depth limit**: 100 levels by default (configurable)
- **Child limit per node**: 100 children max
- **LFU eviction**: Least Frequently Used eviction when limits reached
- **RefCount bucketing**: Reduces path explosion from slight refCount variations

**Key Features**:
- Lock-free concurrent updates using ConcurrentHashMap
- No allocations during tracking (critical for performance)
- Supports multiple roots (different starting points)
- Automatic string interning for memory efficiency
- Separate tracking of traversals vs outcomes

### 3. ByteBufFlowAgent

**Purpose**: Java agent entry point for ByteBuddy instrumentation

**Responsibilities**:
- Parses agent arguments (include, exclude, trackConstructors)
- Installs ByteBuddy transformation
- Registers JMX MBean
- Sets up instrumentation rules

**Instrumentation Strategy**:
```java
// Intercept methods matching:
- Public or protected visibility
- In included packages
- NOT in excluded packages
- With ByteBuf parameter OR ByteBuf return type
- Optionally: constructors for specified classes
```

**Agent Arguments**:
- `include=com.example` - Required, packages to instrument
- `exclude=com.example.test` - Optional, packages to skip
- `trackConstructors=com.example.Message` - Optional, enable constructor tracking

### 4. ByteBufTrackingAdvice

**Purpose**: ByteBuddy advice that intercepts method calls

**Execution Flow**:
```
@OnMethodEnter (runs before method body)
  ↓
1. Check re-entrance guard (prevent infinite recursion)
2. Check parameters - any tracked objects?
3. For each tracked object:
   - Extract metric
   - Record method call in tracker (no suffix)
   - Store identity hash in TRACKED_PARAMS ThreadLocal

@OnMethodExit (runs after method completes)
  ↓
1. Check re-entrance guard
2. Check return value - track with "_return" suffix
   - Only if NOT already tracked as parameter (avoid duplicates)
3. For each tracked object:
   - Extract current metric
   - Record in tracker
4. Clear TRACKED_PARAMS ThreadLocal
```

**Re-entrance Guard Mechanism**:
```java
private static final ThreadLocal<Boolean> IS_TRACKING =
    ThreadLocal.withInitial(() -> false);

// In advice methods:
if (IS_TRACKING.get()) return;  // Already tracking, skip
try {
    IS_TRACKING.set(true);
    // ... tracking logic ...
} finally {
    IS_TRACKING.set(false);
}
```

**Why this is critical**:
- Prevents infinite recursion when tracking code calls instrumented methods
- Uses ThreadLocal for thread safety without locks
- Explains why some methods might not appear in traces (called during tracking)

**Method Entry/Exit Tracking with Suffixes**:

The advice tracks BOTH method entry and exit, using suffixes to differentiate:
- `methodName` - Tracked at method entry (ByteBuf received as parameter)
- `methodName_return` - Tracked at method exit (ByteBuf returned from method)
- `<init>` - Constructor entry (ByteBuf passed to constructor)
- `<init>_return` - Constructor exit (constructor finished storing ByteBuf)

**Duplicate Prevention**:
When a method both receives ByteBuf as parameter AND returns it (e.g., fluent API), the TRACKED_PARAMS ThreadLocal prevents double-tracking by recording identity hashes of parameters. The return value is only tracked if it's a different object.

**Example flow tree**:
```
ROOT: UnpooledByteBufAllocator.heapBuffer [count=1]
└── Client.allocate_return [ref=1, count=1]
    └── Handler.process [ref=1, count=1]
        └── Handler.cleanup_return [ref=0, count=1]
```

This allows tracking complete object flow - both down the stack (parameters) and up the stack (returns). Users will see `_return` suffixes in their flow trees for methods that return ByteBuf.

**Special Cases**:
- **release()**: Only tracked when refCnt drops to 0 (prevents tree clutter)
- **retain()**: Always tracked (shows refCount increases)
- **Static methods**: Tracked like instance methods
- **Constructors**: Only tracked if explicitly enabled

### 5. TrieRenderer

**Purpose**: Formats Trie data into various output views

**Output Formats**:
- `renderIndentedTree()` - Hierarchical tree view with indentation (human-readable)
- `renderForLLM()` - Structured format optimized for LLM parsing (token-efficient)
- `renderSummary()` - Statistics (total roots, paths, leaks)

**Leak Detection**:
- Identifies leaf nodes with non-zero metric
- Marks them with `⚠️ LEAK` in tree view
- Separate LEAKS section in LLM format

### 6. ObjectTrackerHandler Interface

**Purpose**: Extensibility point for tracking custom objects

**Interface**:
```java
public interface ObjectTrackerHandler {
    boolean shouldTrack(Object obj);      // Filter objects to track
    int getMetric(Object obj);            // Extract metric value
    String getObjectType();               // Name for reports
}
```

**Default Implementation**:
- `ByteBufObjectHandler` - Tracks ByteBuf with refCnt() metric

**Registry**:
- `ObjectTrackerRegistry` - Global registry for handler
- Can set programmatically or via system property

## How It Works

### Instrumentation Flow

```
1. JVM loads Java agent
   ↓
2. ByteBufFlowAgent.premain() executes
   ↓
3. ByteBuddy transforms matching classes:
   - Adds @OnMethodExit advice to methods
   - Advice code calls ByteBufTrackingAdvice.trackMethod()
   ↓
4. At runtime, when instrumented method executes:
   - Method completes
   - Advice executes
   - Checks for ByteBuf in params/return
   - Records in ByteBufFlowTracker
   ↓
5. Tracker updates Trie structure:
   - Allocator methods create root nodes
   - Subsequent calls add child nodes
   - Metric tracked at each step
```

### Allocator Root Strategy

**Why**: Tracks ByteBuf from creation (allocator) through entire lifecycle while avoiding expensive stack trace collection

**How**:
1. ByteBuf construction methods are instrumented (UnpooledByteBufAllocator.heapBuffer, Unpooled.buffer, etc.)
2. Allocator methods automatically become root nodes in the Trie
3. All subsequent application methods that handle the ByteBuf become children/descendants
4. Result: Complete flow from allocation to release (or leak)

**Allocator Root Transformers**:
- **ByteBufConstructionTransformer**: Instruments allocator methods
  - Targets: UnpooledByteBufAllocator, PooledByteBufAllocator, Unpooled
  - Only terminal methods (2-arg versions) to avoid telescoping constructor duplicates
- **ByteBufConstructorAdvice**: Tracks ByteBuf through allocator constructors
  - Entry: ByteBuf passed to constructor (`<init>`)
  - Exit: Constructor complete (`<init>_return`)

**Example**:
```
ROOT: UnpooledByteBufAllocator.heapBuffer [count=1]
  └── Client.allocate_return [ref=1, count=1]
      └── Handler.prepare [ref=1, count=1]
          └── Sender.send [ref=1, count=1]
              └── LEAK [ref=1]  ← Leaf with refCnt=1
```

### Release Tracking Algorithm

**Problem**: Tracking every `release()` call clutters tree and creates ambiguity

**Solution**: Only track release() when refCnt drops to 0

**Implementation**:
```java
if (methodName.equals("release")) {
    int refCntAfter = byteBuf.refCnt();
    if (refCntAfter == 0) {
        // Only track final release
        tracker.recordMethodCall(byteBuf, className, methodName, 0);
    }
    // Ignore intermediate releases
}
```

**Benefits**:
- Clean trees showing only final release
- Leaf nodes clearly indicate leak status
- No ambiguity about whether object was released

### Concurrency Model

**Thread-Safety**:
- `ConcurrentHashMap` for object-to-root mapping
- `ConcurrentHashMap` for Trie nodes
- Lock-free updates (critical for performance)
- ThreadLocal-based re-entrance guard (no locks)

**Re-entrance Prevention**:

Critical design feature to prevent infinite recursion:

```java
// In ByteBufTrackingAdvice.java
private static final ThreadLocal<Boolean> IS_TRACKING =
    ThreadLocal.withInitial(() -> false);
```

**How it works**:
1. Before tracking: Check `IS_TRACKING.get()`
2. If true → Already tracking on this thread → Skip and return
3. If false → Set to true, execute tracking logic, set back to false

**Why this is necessary**:
- Tracking code itself may call instrumented methods
- Without guard: infinite recursion (StackOverflowError)
- Example: `tracker.recordMethodCall()` might trigger `toString()` on tracked object
- ThreadLocal ensures per-thread isolation without global locks

**Side effect**: Methods called during tracking will not appear in traces. This is intentional and acceptable.

**Memory Model**:
- No synchronization on read paths
- Eventual consistency acceptable for monitoring use case
- May miss some paths in high-concurrency scenarios (acceptable tradeoff)

**Performance**:
- ~5-10% overhead in high-throughput scenarios
- Zero allocations during tracking
- No stack trace collection (major performance win)
- ThreadLocal access is fast (thread-local storage)

## Build System

### Maven Configuration

**Parent POM**:
- Manages dependency versions (ByteBuddy, Netty, JUnit)
- Defines modules
- No packaging (just coordination)

**Library Module (bytebuf-flow-tracker)**:
- Regular Maven build
- **Maven Shade Plugin** creates fat JAR:
  - Regular JAR: `bytebuf-flow-tracker-1.0.0-SNAPSHOT.jar`
  - Fat JAR: `bytebuf-flow-tracker-1.0.0-SNAPSHOT-agent.jar`
- Fat JAR includes all dependencies (required for Java agent)
- MANIFEST.MF entries:
  - `Premain-Class: com.example.bytebuf.tracker.agent.ByteBufFlowAgent`
  - `Can-Retransform-Classes: true`

**Example Module (bytebuf-flow-example)**:
- Depends on library module
- Shows integration patterns
- Exec plugin configured with agent

### Why Fat JAR?

Java agents require:
1. Single JAR file (not Maven dependencies)
2. All dependencies bundled (ByteBuddy, etc.)
3. Available at JVM startup (before application classpath)

Maven Shade plugin solves this by:
- Bundling all dependencies into one JAR
- Relocating packages to avoid conflicts
- Setting correct MANIFEST.MF entries

## Testing Strategy

### Unit Tests

**ByteBufFlowTrackerTest**:
- Empty tracker validation
- Single flow tracking
- Multiple flows
- Leak detection
- RefCount anomalies
- High-volume scenarios
- Reset functionality

**Test Approach**:
- JUnit 5
- Mockito for ByteBuf mocking
- Verify tree structure
- Verify leak detection
- Verify output formats

### Integration Testing

**Example Module**:
- Real ByteBuf usage
- Demonstrates normal flows
- Demonstrates leaks
- Demonstrates error handling
- Custom object tracking examples

## Extensibility

### Custom Object Tracking

**Steps**:
1. Implement `ObjectTrackerHandler`
2. Register with `ObjectTrackerRegistry`
3. Configure agent to instrument your packages
4. Handler's `shouldTrack()` filters objects
5. Handler's `getMetric()` extracts metric

**Example**:
```java
public class ConnectionTracker implements ObjectTrackerHandler {
    public boolean shouldTrack(Object obj) {
        return obj instanceof Connection;
    }
    public int getMetric(Object obj) {
        return ((Connection) obj).isClosed() ? 0 : 1;
    }
    public String getObjectType() {
        return "DatabaseConnection";
    }
}

// Register
ObjectTrackerRegistry.setHandler(new ConnectionTracker());
```

### Wrapper Class Support

**Problem**: ByteBuf wrapped in custom objects (Message, Request) breaks tracking

**Solution**: Constructor tracking

**Configuration**:
```bash
-javaagent:tracker.jar=include=com.example;trackConstructors=com.example.Message
```

**Effect**:
- Agent instruments specified constructors
- Constructor calls appear in flow tree
- Maintains continuous flow even when ByteBuf is wrapped

**Limitations**:
- Methods receiving wrapper objects need manual tracking
- Only public/protected constructors tracked
- Wildcards supported (`com.example.dto.*`)

## Performance Considerations

### Overhead Sources

1. **Instrumentation**: ByteBuddy transformation at class load time
2. **Method interception**: Advice execution on every tracked method
3. **Trie updates**: ConcurrentHashMap operations
4. **Metric extraction**: refCnt() calls

### Optimizations

1. **No allocations**: Critical path has zero allocations
2. **No stack traces**: Massive performance win vs allocation tracking
3. **Lock-free**: ConcurrentHashMap for all updates
4. **Lazy rendering**: Trie only formatted when requested
5. **Identity hash**: `System.identityHashCode()` for object tracking
6. **Lazy GC processing**: Process every 100 calls (not every call) - saves ~200-490ms/sec @ 10M calls/sec
7. **Mutable ThreadLocal counter**: Avoid Integer boxing - saves ~100-300ms/sec @ 10M calls/sec
8. **Non-volatile fields**: `currentDepth` is plain int (not volatile) - saves ~50ms/sec @ 10M calls/sec
9. **Single identityHashCode call**: Cache value, don't recompute - saves ~50ms/sec @ 10M calls/sec

**Total overhead reduction**: ~405-900ms/sec @ 10M calls/sec from lazy processing optimizations

### Best Practices

1. **Narrow include packages**: Only instrument application code
2. **Exclude noisy packages**: Tests, utilities, third-party
3. **Disable in production**: Remove -javaagent when not needed
4. **Sample if needed**: Implement sampling in custom handler

## JMX Integration

### MBean Interface

**MBean Name**: `com.example:type=ByteBufFlowTracker`

**Operations**:
- `getTreeView()` - String, hierarchical tree (human-readable)
- `getLLMView()` - String, LLM-optimized format (token-efficient)
- `getSummary()` - String, statistics
- `reset()` - void, clears all data

**Use Cases**:
- Runtime monitoring via JConsole
- Automated analysis via JMX clients
- Integration with monitoring tools
- Debugging production issues

## Development Workflow

### Building from Source

```bash
# Build everything
mvn clean install

# Build library only
cd bytebuf-flow-tracker
mvn clean package

# Run tests
mvn test

# Run example
cd bytebuf-flow-example
mvn exec:java
```

### Debugging

**Enable verbose agent logging**:
```java
// In ByteBufFlowAgent.java, add:
System.out.println("[Agent] Instrumenting: " + className);
```

**Verify instrumentation**:
```bash
java -javaagent:tracker-agent.jar=include=com.example -verbose:class -jar app.jar
# Look for "Transformed class: com.example.YourClass"
```

**JMX debugging**:
```bash
jconsole localhost:9999
# Check MBean attributes and operations
```

### Integration into Other Projects

**Three approaches**:

1. **Local Maven repository** (simplest):
   - `mvn clean install` in tracker project
   - Add dependency in your pom.xml
   - Reference agent JAR in ~/.m2/repository

2. **Git submodule** (multi-module projects):
   - Add tracker as submodule
   - Include `bytebuf-flow-tracker` module in parent POM
   - Build together with your project

3. **Copy source** (full control):
   - Copy `bytebuf-flow-tracker/` directory
   - Add as module in your project
   - Build together

**All approaches require**:
- Building from source (not on Maven Central)
- Using fat JAR for -javaagent
- Configuring include/exclude packages

## Design Decisions

### Why Trie Structure?

**Alternatives considered**:
- Flat list of paths: O(n) space, no sharing
- Map of sequences: Complex, no hierarchy
- Tree with separate nodes: Memory overhead

**Trie advantages**:
- Shared prefixes save memory
- Natural hierarchy matches call flow
- Efficient path lookup
- Easy to render as tree

### Why Allocator Root?

**Alternatives considered**:
- Allocation site tracking with stack traces: Expensive (high overhead)
- First-touch application method: Loses allocator context, can't see which allocator created the object
- Manual root marking: Requires code changes
- Thread-based roots: Doesn't match object flow

**Allocator root advantages**:
- Complete lifecycle visibility: Tracks from creation (allocator) to release/leak
- Zero stack trace overhead: Instruments specific allocator methods instead
- Automatic: No code changes required
- Allocator context: Clearly shows which allocator (heap vs direct, pooled vs unpooled) created the ByteBuf
- Works across threads: Object identity tracking follows ByteBuf regardless of thread
- Telescoping prevention: Only instruments terminal methods (2-arg versions) to avoid constructor call duplicates

### Why Release-Only-When-Zero?

**Alternatives considered**:
- Track all release() calls: Clutters tree
- Don't track release(): Can't detect clean paths
- Track retain/release pairs: Complex matching

**Current approach advantages**:
- Clean trees (only final release)
- Clear leak detection (non-zero leaf)
- No ambiguity (either released or leaked)

## Future Enhancements

**Potential improvements**:

1. **Sampling**: Track only % of objects for lower overhead
2. **Time tracking**: Record method execution time
3. **Memory tracking**: Track actual memory usage
4. **Flame graphs**: Visualize hot paths
5. **Export formats**: Graphviz DOT, Mermaid
6. **Web UI**: Real-time visualization
7. **Alerts**: Automatic leak detection and notification

## License

Apache License 2.0
