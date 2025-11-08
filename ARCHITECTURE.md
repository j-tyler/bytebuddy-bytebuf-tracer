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
│   │       ├── trie/
│   │       │   └── FlowTrie.java               # Pure Trie data structure
│   │       └── view/
│   │           └── TrieRenderer.java           # Output formatting
│   └── src/test/java/            # Comprehensive tests
└── bytebuf-flow-example/         # Module 2: Usage examples
    ├── pom.xml                   # Shows integration patterns
    └── src/main/java/            # Demo apps (ByteBuf & custom objects)
```

## Core Components

### 1. ByteBufFlowTracker

**Purpose**: Main tracking logic using first-touch-as-root approach

**Key Methods**:
- `recordMethodCall(Object, String, String, int)` - Track a method call
- `getTrie()` - Get the underlying Trie structure
- `reset()` - Clear all tracking data

**Design**:
- Singleton pattern for global access
- Thread-safe using ConcurrentHashMap for object-to-root mapping
- Uses `System.identityHashCode()` to track object identity
- First method to handle an object becomes its root node

### 2. FlowTrie

**Purpose**: Pure data structure for storing method call paths

**Design**:
- Hierarchical tree structure where each node represents a method call
- Each node stores:
  - Method signature (ClassName.methodName)
  - Metric value (e.g., refCount for ByteBuf)
  - Call count (how many times this path was taken)
  - Children nodes (subsequent method calls)
- Trie structure shares common path prefixes to minimize memory

**Key Features**:
- Lock-free concurrent updates using ConcurrentHashMap
- No allocations during tracking (critical for performance)
- Supports multiple roots (different starting points)

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
@OnMethodExit (runs after method completes)
  ↓
1. Check return value - is it ByteBuf?
2. Check parameters - any ByteBuf?
3. For each ByteBuf found:
   - Extract metric (refCnt())
   - Record method call in tracker
   - Handle release() specially (only track if refCnt -> 0)
```

**Special Cases**:
- **release()**: Only tracked when refCnt drops to 0 (prevents tree clutter)
- **retain()**: Always tracked (shows refCount increases)
- **Static methods**: Tracked like instance methods
- **Constructors**: Only tracked if explicitly enabled

### 5. TrieRenderer

**Purpose**: Formats Trie data into various output views

**Output Formats**:
- `renderIndentedTree()` - Hierarchical tree view with indentation
- `renderFlatPaths()` - Root-to-leaf paths (one per line)
- `renderSummary()` - Statistics (total roots, paths, leaks)
- `renderCsv()` - CSV format for spreadsheet analysis
- `renderJson()` - JSON format for programmatic processing
- `renderForLLM()` - Structured format optimized for LLM parsing

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
   - First call for object creates root
   - Subsequent calls add child nodes
   - Metric tracked at each step
```

### First-Touch Root Strategy

**Why**: Avoids allocation site tracking (expensive) while still identifying leak sources

**How**:
1. When ByteBuf first encountered, create root node
2. Root = first method that handled this object
3. All subsequent methods become children/descendants
4. Result: Clear flow from entry point to leak

**Example**:
```
allocate()           ← Root (first touch)
  └── prepare()      ← Child
      └── send()     ← Grandchild
          └── LEAK   ← Leaf with refCnt=1
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

**Memory Model**:
- No synchronization on read paths
- Eventual consistency acceptable for monitoring use case
- May miss some paths in high-concurrency scenarios (acceptable tradeoff)

**Performance**:
- ~5-10% overhead in high-throughput scenarios
- Zero allocations during tracking
- No stack trace collection (major performance win)

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

### Best Practices

1. **Narrow include packages**: Only instrument application code
2. **Exclude noisy packages**: Tests, utilities, third-party
3. **Disable in production**: Remove -javaagent when not needed
4. **Sample if needed**: Implement sampling in custom handler

## JMX Integration

### MBean Interface

**MBean Name**: `com.example:type=ByteBufFlowTracker`

**Operations**:
- `getTreeView()` - String, hierarchical tree
- `getFlatView()` - String, flat paths
- `getCsvView()` - String, CSV format
- `getJsonView()` - String, JSON format
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

### Why First-Touch Root?

**Alternatives considered**:
- Allocation site tracking: Expensive (stack traces)
- Manual root marking: Requires code changes
- Thread-based roots: Doesn't match object flow

**First-touch advantages**:
- Zero overhead (no stack traces)
- Automatic (no code changes)
- Intuitive (matches developer mental model)
- Works across threads

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
