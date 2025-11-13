# ByteBuf Flow Tracker

A lightweight ByteBuddy-based Java agent for tracking object flows through your application using a Trie data structure. Detect memory leaks, understand flow patterns, and monitor any resource type.

## Features

- **Zero allocation overhead**: No stack trace collection or allocation site tracking
- **Allocator root tracking**: ByteBuf allocator methods (Unpooled.buffer, ByteBufAllocator.heapBuffer, etc.) serve as Trie roots
- **Intelligent release tracking**: Tracks `release()` only when refCnt drops to 0, eliminating leak ambiguity
- **Entry/Exit tracking**: Methods returning tracked objects show with `_return` suffix for complete flow visibility
- **Memory efficient**: Trie structure shares common prefixes, minimizing memory usage
- **Real-time monitoring**: JMX MBean for runtime analysis
- **Dual output formats**: Human-readable tree view and LLM-optimized structured format

## Prerequisites

- **Java 8+** (JDK 8, 11, 17, 21, or later)
- **Maven 3.6+** or **Gradle 6+**
- **Netty ByteBuf** in your application (or custom objects to track)
- **Not published to Maven Central** - must build from source

## Java Version Requirements

This project requires **Java 8+** (compiled with Java 8 source/target compatibility). Recommended: **Java 11, 17, or 21 LTS**.

```bash
# Check your Java version
java -version

# Use a specific JDK for Maven (if you have multiple versions)
JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 mvn clean install

# Find installed JDKs
ls /usr/lib/jvm/                      # Linux
/usr/libexec/java_home -V             # macOS
update-alternatives --list java        # Debian/Ubuntu
```

**Note:** ByteBuddy 1.14.9 officially supports Java 8-22. For Java 21+ you may need `export MAVEN_OPTS="-Dnet.bytebuddy.experimental=true"` if you encounter version warnings.

## Quick Start

### 1. Clone and Build

```bash
# Clone the repository
git clone https://github.com/j-tyler/bytebuddy-bytebuf-tracer.git
cd bytebuddy-bytebuf-tracer

# Build everything (library + examples)
mvn clean install
```

**Expected output:** `BUILD SUCCESS` for all modules

### 2. Run Tests

```bash
# Run all tests (unit + integration)
mvn test

# Run only integration tests
cd bytebuf-flow-integration-tests
mvn test
```

**Expected output:** All 29+ tests passing

### 3. Run Examples

```bash
# Navigate to examples and build classpath
cd bytebuf-flow-example
mvn dependency:build-classpath -Dmdep.outputFile=/tmp/cp.txt -q

# Define helper function for classpath
CP_CMD='$(pwd)/target/classes:$(cat /tmp/cp.txt)'

# Run basic example (shows leak detection)
java "-javaagent:../bytebuf-flow-tracker/target/bytebuf-flow-tracker-1.0.0-SNAPSHOT-agent.jar=include=com.example.demo" \
  -cp "$(pwd)/target/classes:$(cat /tmp/cp.txt)" \
  com.example.demo.DemoApplication

# Run wrapper example with constructor tracking
java "-javaagent:../bytebuf-flow-tracker/target/bytebuf-flow-tracker-1.0.0-SNAPSHOT-agent.jar=include=com.example.demo;trackConstructors=com.example.demo.SimpleWrapperExample\$DataPacket" \
  -cp "$(pwd)/target/classes:$(cat /tmp/cp.txt)" \
  com.example.demo.SimpleWrapperExample

# Run custom object tracking example
java "-javaagent:../bytebuf-flow-tracker/target/bytebuf-flow-tracker-1.0.0-SNAPSHOT-agent.jar=include=com.example.demo" \
  -Dobject.tracker.handler=com.example.demo.custom.FileHandleTracker \
  -cp "$(pwd)/target/classes:$(cat /tmp/cp.txt)" \
  com.example.demo.custom.CustomObjectExample
```

**Note:** Examples must run with the `-javaagent` argument to enable tracking. The basic example demonstrates intentional leaks and proper cleanup. The `$(pwd)` ensures the classpath uses absolute paths.

### Expected Output

```
=== ByteBuf Flow Summary ===
Total Root Methods: 2
Total Traversals: 10
Total Paths: 4
Leak Paths: 2
Leak Percentage: 50.00%

=== Flow Tree ===
ROOT: UnpooledByteBufAllocator.heapBuffer [count=9]
└── MessageProcessor.process [ref=1, count=5]
    └── InstrumentedUnpooledUnsafeHeapByteBuf.release [ref=0, count=5]
└── MessageProcessor.processWithPotentialError [ref=1, count=3]
    └── InstrumentedUnpooledUnsafeHeapByteBuf.release [ref=0, count=3]
└── LeakyService.forgetsToRelease [ref=1, count=1] ⚠️ LEAK

ROOT: UnpooledByteBufAllocator.directBuffer [count=2]
└── DirectLeakyService.forgetsToReleaseDirect [ref=1, count=1] 🚨 LEAK
└── Handler.cleanupProperly [ref=1, count=1]
    └── InstrumentedUnpooledUnsafeDirectByteBuf.release [ref=0, count=1]
```

**Key Points**:
- **Allocator Roots**: Netty allocator methods (UnpooledByteBufAllocator.heapBuffer, etc.) are roots
- **Return Values**: Methods returning ByteBuf show with `_return` suffix (e.g., `allocateBuffer_return`)
- **Leak Severity**: Direct buffer leaks (🚨 LEAK) are critical (never GC'd), heap leaks (⚠️ LEAK) are moderate (will GC)
- **Note**: `compositeBuffer` leaks show ⚠️ (treated as heap) since they may contain mixed buffer types

## How It Works

The agent uses ByteBuddy to instrument your application code at runtime:

1. **Allocator Roots**: ByteBuf construction methods become roots in the flow tree
2. **Method Tracking**: All methods with ByteBuf parameters/returns are automatically tracked
3. **Path Building**: Each method call creates a node in the tree showing the object's flow
4. **Leak Detection**: Objects not released (refCount > 0) at leaf nodes are marked as leaks

For detailed architecture information, see [ARCHITECTURE.md](ARCHITECTURE.md).

---

## Tracking Capabilities

The tracker automatically instruments methods with ByteBuf parameters/returns. Additional features are available for advanced tracking scenarios:

### Release Tracking

Tracks `release()` calls only when refCnt drops to zero, providing clear leak detection without tree clutter.

**Interpreting Leaf Nodes**:

| Leaf Node Pattern | Meaning | Action |
|-------------------|---------|--------|
| `release() [ref=0]` | ByteBuf properly released | ✓ No action needed |
| `SomeMethod [ref=1+]` | ByteBuf not released | ⚠️ Investigate for leak |

**Behavior**:
- `retain()` calls are always tracked (shows refCount increases)
- Only the final `release()` (ref → 0) is tracked
- Intermediate `release()` calls are skipped to keep trees clean

**Example - Multiple Retain/Release:**
```java
ByteBuf buffer = Unpooled.buffer(256);   // ref=1
buffer.retain();                         // ref=1 -> 2 ✓ TRACKED
processor.process(buffer);               // ref=2
buffer.release();                        // ref=2 -> 1 ✗ SKIPPED (intermediate)
processor.finish(buffer);                // ref=1
buffer.release();                        // ref=1 -> 0 ✓ TRACKED (final)
```

Flow tree shows: `ROOT → Processor.process → retain [ref=2] → Processor.finish → release [ref=0]` ✓ Clean

### Static Method Tracking

**Enabled by default** - Static methods with ByteBuf parameters/returns are automatically tracked.

### Wrapper Object Tracking

When ByteBuf is wrapped in custom objects (Message, Request), enable constructor tracking to maintain flow visibility:

```bash
-javaagent:tracker.jar=trackConstructors=com.example.Message
```

### Custom Object Tracking

Track any object type by implementing `ObjectTrackerHandler` and registering via system property or programmatically. See [Custom Object Tracking](#custom-object-tracking) section below for details.

---

## Integration Guide

**Important**: Not published to Maven Central. Build from source.

### Method 1: Local Maven Repository (Simplest)

```bash
git clone https://github.com/j-tyler/bytebuddy-bytebuf-tracer.git
cd bytebuddy-bytebuf-tracer
mvn clean install
```

Add to your `pom.xml`:
```xml
<dependency>
    <groupId>com.example.bytebuf</groupId>
    <artifactId>bytebuf-flow-tracker</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>

<!-- Configure agent -->
<plugin>
    <groupId>org.codehaus.mojo</groupId>
    <artifactId>exec-maven-plugin</artifactId>
    <configuration>
        <mainClass>com.yourcompany.Main</mainClass>
        <arguments>
            <argument>-javaagent:${settings.localRepository}/com/example/bytebuf/bytebuf-flow-tracker/1.0.0-SNAPSHOT/bytebuf-flow-tracker-1.0.0-SNAPSHOT-agent.jar=include=com.yourcompany</argument>
        </arguments>
    </configuration>
</plugin>
```

### Method 2: Git Submodule (Multi-module projects)

```bash
git submodule add https://github.com/j-tyler/bytebuddy-bytebuf-tracer.git modules/bytebuddy-bytebuf-tracer
```

Add `modules/bytebuddy-bytebuf-tracer/bytebuf-flow-tracker` to your parent POM modules, then reference as dependency. Agent path: `${project.basedir}/modules/bytebuddy-bytebuf-tracer/bytebuf-flow-tracker/target/bytebuf-flow-tracker-1.0.0-SNAPSHOT-agent.jar`

### Method 3: Gradle Composite Build

```bash
git clone https://github.com/j-tyler/bytebuddy-bytebuf-tracer.git ../bytebuddy-bytebuf-tracer
```

In `settings.gradle`:
```gradle
includeBuild('../bytebuddy-bytebuf-tracer/bytebuf-flow-tracker')
```

In `build.gradle`:
```gradle
dependencies {
    implementation 'com.example.bytebuf:bytebuf-flow-tracker:1.0.0-SNAPSHOT'
}

task runWithAgent(type: JavaExec) {
    mainClass = 'com.yourcompany.Main'
    classpath = sourceSets.main.runtimeClasspath
    jvmArgs = ["-javaagent:${file('../bytebuddy-bytebuf-tracer/bytebuf-flow-tracker/build/libs/bytebuf-flow-tracker-1.0.0-SNAPSHOT-agent.jar')}=include=com.yourcompany"]
}
```

See `bytebuf-flow-example/build.gradle` for complete examples.

## Configuration

### Agent Arguments

Format: `include=package1,package2;exclude=package.*,SpecificClass;trackConstructors=class1,class2;trackDirectOnly=true`

**Parameters:**
- `include` - Packages or specific classes to instrument (required)
  - Package inclusion: `com.example.package.*` (requires .* suffix)
  - Class inclusion: `com.example.package.SpecificClass` (no .* suffix)
  - Inner class inclusion: `com.example.Outer$Inner` (use $ separator)
- `exclude` - Packages or specific classes to skip (optional)
  - Package exclusion: `com.example.package.*` (requires .* suffix)
  - Class exclusion: `com.example.package.SpecificClass` (no .* suffix)
  - Inner class exclusion: `com.example.Outer$Inner` (use $ separator)
- `trackConstructors` - Classes to enable constructor tracking (optional)
- `trackDirectOnly` - Track only direct memory allocations (optional, default: false)
  - When `true`: **Maximum performance for production**
    - heapBuffer methods: NOT instrumented (**zero overhead**)
    - directBuffer/ioBuffer: Instrumented and tracked
    - Ambiguous methods (wrappedBuffer, compositeBuffer): Filtered at runtime via `isDirect()` (~5ns)
  - When `false`: track both heap and direct buffers (default behavior)
  - **Use case**: Production environments - track only critical direct memory leaks (never GC'd)
  - **Performance**: Optimal for production where direct memory crashes are the concern

**Examples:**

```bash
# Single package (use .* for packages)
-javaagent:tracker.jar=include=com.example.*

# Multiple packages or classes
-javaagent:tracker.jar=include=com.example.*,org.myapp.*,com.specific.Class

# With exclusions
-javaagent:tracker.jar=include=com.example.*;exclude=com.example.legacy.*,com.example.test.*

# Constructor tracking for wrapper classes
-javaagent:tracker.jar=include=com.example.*;trackConstructors=com.example.Message,com.example.dto.*

# Direct-only tracking (production)
-javaagent:tracker.jar=include=com.example.*;trackDirectOnly=true
```

**Important Notes:**
- Both `include` and `exclude` support the same syntax for packages and classes
- Package matching **requires** the `.*` suffix: `com.example.foo.*` matches all classes in that package and subpackages
- Class matching has **no** `.*` suffix: `com.example.Foo` matches only that specific class
- The `.*` suffix makes the distinction between packages and classes explicit and unambiguous
- Inner classes use the `$` separator: `com.example.Outer$Inner`
- **Precedence**: Exclusions take precedence over inclusions. If a class matches both `include` and `exclude`, it will be excluded
- **Constructor Tracking**: Exclusions also take precedence over `trackConstructors`. If a class is in both `trackConstructors` and `exclude` patterns, it will NOT be instrumented

### Direct Memory Tracking Performance

Production mode: `trackDirectOnly=true` tracks only direct memory (critical leaks that crash JVM) with minimal overhead.

```bash
-javaagent:tracker.jar=include=com.yourapp.*;trackDirectOnly=true
```

| Allocation Type | trackDirectOnly=true | Default (false) |
|----------------|----------------------|-----------------|
| `heapBuffer()` | **Not instrumented** (0ns) | Tracked |
| `directBuffer()` | Tracked | Tracked |
| `ioBuffer()` | Tracked | Tracked |
| `wrappedBuffer()` | Filtered via `isDirect()` (~5ns) | Tracked |

**Why use in production:** Direct memory leaks crash your JVM (never GC'd). Heap leaks eventually get cleaned up. This mode focuses on critical leaks with zero overhead for heap allocations.

### JMX Monitoring

```bash
# Enable JMX
java -javaagent:bytebuf-flow-tracker-agent.jar=include=com.yourcompany \
     -Dcom.sun.management.jmxremote \
     -Dcom.sun.management.jmxremote.port=9999 \
     -Dcom.sun.management.jmxremote.authenticate=false \
     -Dcom.sun.management.jmxremote.ssl=false \
     -jar your-app.jar

# Connect
jconsole localhost:9999
# Navigate to: MBeans → com.example → ByteBufFlowTracker
```

**JMX Operations**:
- `getTreeView()` - Get hierarchical tree view
- `getLLMView()` - Get LLM-optimized structured format
- `getSummary()` - Get statistics summary
- `getActiveFlowCount()` - Get count of currently tracked objects
- `getRootCount()` - Get number of root methods
- `exportToFile(filepath, format)` - Export to file (formats: tree, llm)
- `reset()` - Clear all tracking data

## Custom Object Tracking

Track ANY object type by implementing `ObjectTrackerHandler`:

```java
public class ConnectionTracker implements ObjectTrackerHandler {
    public boolean shouldTrack(Object obj) { return obj instanceof Connection; }
    public int getMetric(Object obj) { return ((Connection) obj).isClosed() ? 0 : 1; }
    public String getObjectType() { return "DatabaseConnection"; }
}
```

**Registration Options:**

**Option 1: System Property** (no code changes needed)
```bash
java -Dobject.tracker.handler=com.yourcompany.ConnectionTracker \
     -javaagent:tracker.jar=include=com.yourcompany \
     -jar your-app.jar
```

**Option 2: Programmatic** (in your application code)
```java
public static void main(String[] args) {
    ObjectTrackerRegistry.setHandler(new ConnectionTracker());
    // ... start application
}
```

**Note**: The handler must be set BEFORE any tracked objects are created. Use system property if you can't modify application code or need to avoid static initialization order issues.

**Metric Selection Guide:**

| Object Type | Metric | Meaning |
|-------------|--------|---------|
| ByteBuf | `refCnt()` | Reference count (0 = released) |
| Connection | `isClosed() ? 0 : 1` | 0 = closed, 1 = open |
| FileHandle | `isOpen() ? 1 : 0` | 0 = closed, 1 = open |
| SocketChannel | `isOpen() ? 1 : 0` | 0 = closed, 1 = open |
| Semaphore | `availablePermits()` | Available permits |

## Production Metrics Integration

Push leak metrics to monitoring systems (Datadog, Prometheus, etc.) with zero code changes to the tracker.

### MetricHandler Interface

```java
public interface MetricHandler {
    Set<MetricType> getRequiredMetrics();  // DIRECT_LEAKS, HEAP_LEAKS
    void onMetrics(MetricSnapshot snapshot);
    String getName();
}
```

**MetricSnapshot fields:**
- `getTotalDirectLeaks()` - Critical: crashes JVM if leaked
- `getTotalHeapLeaks()` - Moderate: wastes memory, eventually GC'd
- `getDirectLeakFlows()` / `getHeapLeakFlows()` - Unique leak paths

### Registration Methods

**Programmatic** (in your app):
```java
public class DatadogMetricHandler implements MetricHandler {
    public void onMetrics(MetricSnapshot snapshot) {
        statsd.gauge("bytebuf.direct_leaks", snapshot.getTotalDirectLeaks());
        statsd.gauge("bytebuf.heap_leaks", snapshot.getTotalHeapLeaks());
    }
    public Set<MetricType> getRequiredMetrics() {
        return EnumSet.of(MetricType.DIRECT_LEAKS, MetricType.HEAP_LEAKS);
    }
    public String getName() { return "DatadogMetrics"; }
}

MetricHandlerRegistry.register(new DatadogMetricHandler());
```

**System Property** (zero code changes):
```bash
java -Dmetric.handlers=com.yourcompany.DatadogMetricHandler \
     -javaagent:tracker.jar=include=com.yourcompany \
     -jar your-app.jar
```

**Configure interval:** `-Dbytebuf.metrics.pushInterval=60` (default: 60 seconds)

## Accessing Tracking Data

### Programmatic Access

```java
ByteBufFlowTracker tracker = ByteBufFlowTracker.getInstance();
TrieRenderer renderer = new TrieRenderer(tracker.getTrie());

System.out.println(renderer.renderSummary());        // Statistics
System.out.println(renderer.renderIndentedTree());   // Tree view
System.out.println(renderer.renderForLLM());         // LLM-optimized format
```

### Output Formats

**Tree View** (human-readable):
```
ROOT: UnpooledByteBufAllocator.heapBuffer [count=5]
└── MessageProcessor.process [ref=1, count=5]
    └── MessageProcessor.cleanup [ref=0, count=5]
```

**LLM Format** (structured):
```
METADATA:
total_roots=2, total_paths=50, leak_paths=12, leak_percentage=24.00%

LEAKS:
leak|root=UnpooledByteBufAllocator.heapBuffer|final_ref=1|path=...
```

### Automatic Shutdown Report

The agent prints a comprehensive report on JVM shutdown (summary, flow tree, leaks). Useful for batch jobs and testing.

## Troubleshooting

### Common Issues

**Agent not loading**: Verify `-javaagent` comes before `-jar`, check JAR path, ensure using `-agent.jar` (fat JAR)

**No data appearing**: Check `include` packages match your code. Agent only instruments methods with ByteBuf in signature:

```java
// ✓ INSTRUMENTED
public void process(ByteBuf buf) { }
public ByteBuf create() { }

// ✗ NOT INSTRUMENTED
public void setName(String name) { }
public void processMessage(Message msg) { }  // ByteBuf wrapped - needs trackConstructors
```

**Too much data**: Narrow `include` to app code only, add `exclude` for test/utility packages

**Build fails**: Run `mvn clean install` in tracker project first, verify `~/.m2/repository/com/example/bytebuf/` exists

**ClassNotFoundException**: Use `-agent.jar` (fat JAR) not regular JAR

**Custom handler not working**: Call `setHandler()` BEFORE tracked objects created, check system property spelling

### Mockito test failures

**Root Cause**: Agent transforms classes, Mockito can't retransform them.

**Solution**: Exclude mocked DTO/protocol classes:
```bash
-javaagent:tracker.jar=include=com.yourcompany;exclude=com.yourcompany.protocol,com.yourcompany.dto
```

**Default exclusions**: `org.mockito.*`, `java.*`, `sun.*`, `net.bytebuddy.*`

### JMX connection fails

**Symptoms**: Cannot connect via JConsole

**Solutions**:
1. Verify JMX port is open and not blocked by firewall
2. Check JMX configuration parameters are correct
3. Use `jps` to verify process ID
4. Try `jconsole <pid>` for local connections

## External Resources

- [ByteBuddy Documentation](https://bytebuddy.net/)
- [Netty ByteBuf Guide](https://netty.io/wiki/reference-counted-objects.html)
- [Java Agents Tutorial](https://www.baeldung.com/java-instrumentation)

## License

This project is licensed under the Apache License 2.0 - see the [LICENSE](LICENSE) file for details.

All source files use [SPDX license identifiers](https://spdx.dev/learn/handling-license-info/) for brevity and machine-readability:

```java
/*
 * Copyright 2025 Justin Marsh
 * SPDX-License-Identifier: Apache-2.0
 */
```

### Third-Party Licenses

This project bundles the following dependencies:
- **Byte Buddy** - Apache License 2.0 (Copyright 2014-Present Rafael Winterhalter)
- **ASM** (bundled within Byte Buddy) - BSD 3-Clause License (Copyright 2000-2011 INRIA, France Telecom)
- **Stormpot** - Apache License 2.0 (Copyright 2011-2024 Chris Vest)

See the [NOTICE](NOTICE) file for complete attribution details.

