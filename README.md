# ByteBuf Flow Tracker

A lightweight ByteBuddy-based Java agent for tracking object flows through your application using a Trie data structure. Detect memory leaks, understand flow patterns, and monitor any resource type.

---

- **Zero allocation overhead**: No stack trace collection or allocation site tracking
- **First-touch root**: The first method that handles a ByteBuf becomes the Trie root
- **Memory efficient**: Trie structure shares common prefixes, minimizing memory usage
- **Clean separation**: Pure data structure (Trie) with separate rendering/viewing
- **Real-time monitoring**: JMX MBean for runtime analysis
- **Dual output formats**: Human-readable tree view and LLM-optimized structured format

### Prerequisites

- **Java 8+**
- **Maven 3.6+** or **Gradle 6+**
- **Netty ByteBuf** in your application (or custom objects to track)
- **Not published to Maven Central** - must build from source

---

## Project Structure

Multi-module Maven project:

```
bytebuddy-bytebuf-tracer/
├── pom.xml                       # Parent POM
├── bytebuf-flow-tracker/         # Reusable library + agent
│   ├── src/main/java/            # Tracking logic, agent, handlers
│   ├── src/test/java/            # Comprehensive tests
│   └── pom.xml                   # Builds library + fat agent JAR
└── bytebuf-flow-example/         # Usage examples
    ├── src/main/java/            # Demo apps (ByteBuf & custom objects)
    └── pom.xml                   # Shows integration patterns
```

**Module 1 (`bytebuf-flow-tracker`)**: Reusable library with ByteBuddy agent, use as dependency
**Module 2 (`bytebuf-flow-example`)**: Complete examples showing integration

---

## Quick Start

### Build and Run Example

```bash
# Clone and build
git clone https://github.com/j-tyler/bytebuddy-bytebuf-tracer.git
cd bytebuddy-bytebuf-tracer
mvn clean install

# Run ByteBuf example
cd bytebuf-flow-example
mvn exec:java

# Run custom object example (file handles)
mvn exec:java -Dexec.mainClass="com.example.demo.custom.CustomObjectExample"
```

### Expected Output

```
=== ByteBuf Flow Summary ===
Total Root Methods: 2
Total Traversals: 9
Unique Paths: 3
Leak Paths: 1

=== Flow Tree ===
ROOT: DemoApplication.handleNormalRequest [count=5]
└── MessageProcessor.process [ref=1, count=5]
    └── MessageProcessor.validate [ref=1, count=5]
        └── MessageProcessor.parseContent [ref=1, count=5]
            └── MessageProcessor.store [ref=0, count=5]

ROOT: DemoApplication.createLeak [count=1]
└── LeakyService.forgetsToRelease [ref=1, count=1]
    └── LeakyService.processData [ref=1, count=1] ⚠️ LEAK
```

**Leak Detection**: Leaf nodes with `⚠️ LEAK` (non-zero metric) indicate unreleased resources.

---

## How It Works

### Architecture

1. **ByteBuddy Instrumentation**: Agent intercepts all public/protected methods in specified packages
2. **First Touch = Root**: First method to handle an object becomes its Trie root
3. **Path Building**: Each subsequent method call adds a node to the tree
4. **Metric Tracking**: Each node records object's metric (refCount for ByteBuf, open/closed for others)
5. **Leak Detection**: Leaf nodes with non-zero metric indicate leaks

### Components

- **`FlowTrie`**: Pure Trie data structure for storing method call paths
- **`ByteBufFlowTracker`**: Main tracking logic using first-touch-as-root approach
- **`ByteBufFlowAgent`**: Java agent entry point for ByteBuddy instrumentation
- **`ByteBufTrackingAdvice`**: ByteBuddy advice that intercepts methods
- **`ObjectTrackerHandler`**: Interface for tracking custom objects
- **`TrieRenderer`**: Formats Trie data into tree, flat, CSV, JSON views
- **`ByteBufFlowMBean`**: JMX interface for runtime monitoring

### Extensibility

Track ANY object type by implementing `ObjectTrackerHandler`:

```java
public class ConnectionTracker implements ObjectTrackerHandler {
    public boolean shouldTrack(Object obj) { return obj instanceof Connection; }
    public int getMetric(Object obj) { return ((Connection) obj).isClosed() ? 0 : 1; }
    public String getObjectType() { return "DatabaseConnection"; }
}

// Register in main()
ObjectTrackerRegistry.setHandler(new ConnectionTracker());
```

---

## Integration Guide

**Important**: This project is NOT on Maven Central. Build from source using one of three methods below.

### Method 1: Local Maven Repository (Simple)

**Best for**: Individual developers, local testing

```bash
# Step 1: Clone and build
git clone https://github.com/j-tyler/bytebuddy-bytebuf-tracer.git
cd bytebuddy-bytebuf-tracer
mvn clean install
```

This installs to `~/.m2/repository/com/example/bytebuf/bytebuf-flow-tracker/1.0.0-SNAPSHOT/`

```xml
<!-- Step 2: Add to your pom.xml -->
<dependency>
    <groupId>com.example.bytebuf</groupId>
    <artifactId>bytebuf-flow-tracker</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>

<!-- Step 3: Configure agent (choose ONE option) -->

<!-- Option A: Development with exec plugin -->
<plugin>
    <groupId>org.codehaus.mojo</groupId>
    <artifactId>exec-maven-plugin</artifactId>
    <version>3.1.0</version>
    <configuration>
        <mainClass>com.yourcompany.Main</mainClass>
        <arguments>
            <argument>-javaagent:${settings.localRepository}/com/example/bytebuf/bytebuf-flow-tracker/1.0.0-SNAPSHOT/bytebuf-flow-tracker-1.0.0-SNAPSHOT-agent.jar=include=com.yourcompany</argument>
        </arguments>
    </configuration>
</plugin>

<!-- Option B: Testing with surefire -->
<plugin>
    <groupId>org.apache.maven.plugins</groupId>
    <artifactId>maven-surefire-plugin</artifactId>
    <version>3.0.0</version>
    <configuration>
        <argLine>-javaagent:${settings.localRepository}/com/example/bytebuf/bytebuf-flow-tracker/1.0.0-SNAPSHOT/bytebuf-flow-tracker-1.0.0-SNAPSHOT-agent.jar=include=com.yourcompany</argLine>
    </configuration>
</plugin>

<!-- Option C: Production (manual command) -->
<!-- Copy agent JAR to your deployment, then: -->
<!-- java -javaagent:/path/to/bytebuf-flow-tracker-agent.jar=include=com.yourcompany -jar your-app.jar -->
```

### Method 2: Git Submodule (Recommended for Claude Code)

**Best for**: Multi-module projects, continuous integration

```bash
# Step 1: Add as submodule
cd your-project/
git submodule add https://github.com/j-tyler/bytebuddy-bytebuf-tracer.git modules/bytebuddy-bytebuf-tracer
```

```xml
<!-- Step 2: Add to your parent pom.xml -->
<modules>
    <module>your-existing-module</module>
    <module>modules/bytebuddy-bytebuf-tracer/bytebuf-flow-tracker</module>
</modules>

<!-- Step 3: Add dependency in your app module -->
<dependency>
    <groupId>com.example.bytebuf</groupId>
    <artifactId>bytebuf-flow-tracker</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>

<!-- Step 4: Configure agent with relative path -->
<plugin>
    <groupId>org.codehaus.mojo</groupId>
    <artifactId>exec-maven-plugin</artifactId>
    <configuration>
        <mainClass>com.yourcompany.Main</mainClass>
        <arguments>
            <argument>-javaagent:${project.basedir}/modules/bytebuddy-bytebuf-tracer/bytebuf-flow-tracker/target/bytebuf-flow-tracker-1.0.0-SNAPSHOT-agent.jar=include=com.yourcompany</argument>
        </arguments>
    </configuration>
</plugin>
```

**Note**: Running `mvn clean install` at your project root builds everything in one command.

### Method 3: Copy Source Directly

**Best for**: Full control, embedded in monorepo

```bash
# Step 1: Copy library module
cp -r /path/to/bytebuddy-bytebuf-tracer/bytebuf-flow-tracker your-project/modules/
```

```xml
<!-- Step 2: Add to parent pom.xml -->
<modules>
    <module>modules/bytebuf-flow-tracker</module>
</modules>

<!-- Step 3 & 4: Same as Method 2 -->
```

### Gradle Integration

```bash
# Build tracker with Maven first
cd /path/to/bytebuddy-bytebuf-tracer
mvn clean install
```

```groovy
// build.gradle
dependencies {
    implementation 'com.example.bytebuf:bytebuf-flow-tracker:1.0.0-SNAPSHOT'
}

task runWithAgent(type: JavaExec) {
    mainClass = 'com.yourcompany.Main'
    jvmArgs = ["-javaagent:${System.getProperty('user.home')}/.m2/repository/com/example/bytebuf/bytebuf-flow-tracker/1.0.0-SNAPSHOT/bytebuf-flow-tracker-1.0.0-SNAPSHOT-agent.jar=include=com.yourcompany"]
}
```

### Access Tracking Data

**Programmatic Access:**

```java
import com.example.bytebuf.tracker.ByteBufFlowTracker;
import com.example.bytebuf.tracker.view.TrieRenderer;

ByteBufFlowTracker tracker = ByteBufFlowTracker.getInstance();
TrieRenderer renderer = new TrieRenderer(tracker.getTrie());

System.out.println(renderer.renderSummary());        // Statistics
System.out.println(renderer.renderIndentedTree());   // Tree view
System.out.println(renderer.renderFlatPaths());      // Flat paths (leaks highlighted)
System.out.println(renderer.renderCsv());            // CSV export
System.out.println(renderer.renderJson());           // JSON export
```

**JMX Access:**

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

**JMX Operations**: `getTreeView()`, `getFlatView()`, `getCsvView()`, `getJsonView()`, `getSummary()`, `reset()`

### Verify Integration

Run your application and check for:

1. **Agent loaded**: `[ByteBufFlowAgent] Starting with config...`
2. **Instrumentation installed**: `[ByteBufFlowAgent] Instrumentation installed successfully`
3. **JMX registered**: `[ByteBufFlowAgent] JMX MBean registered`
4. **Objects tracked**: Call tracking API or check JMX - should see method calls
5. **Leaks detected**: Leaf nodes with `⚠️ LEAK` or `[ref=N]` where N > 0

## Output Formats

The tracker provides two output formats optimized for different use cases:

### 1. Human-Readable Format: Visual Tree

A clean tree visualization with summary statistics, perfect for manual analysis:
```

That's it! The tracker now monitors database connections for leaks.

### ObjectTrackerHandler Interface

Three methods define what and how to track:

1. **`shouldTrack(Object obj)`**: Return `true` if this object should be tracked (called for every method parameter/return)
2. **`getMetric(Object obj)`**: Extract integer metric (0 = released, >0 = active)
3. **`getObjectType()`**: Name for reports (e.g., "FileHandle", "Connection")

**Default**: Uses `ByteBufObjectHandler` which tracks ByteBuf with `refCnt()` as metric.

### Metric Selection Guide

| Object Type | Metric | Meaning |
|-------------|--------|---------|
| ByteBuf | `refCnt()` | Reference count (0 = released) |
| Connection | `isClosed() ? 0 : 1` | 0 = closed, 1 = open |
| FileHandle | `isOpen() ? 1 : 0` | 0 = closed, 1 = open |
| SocketChannel | `isOpen() ? 1 : 0` | 0 = closed, 1 = open |
| Semaphore | `availablePermits()` | Available permits |

**Leak Detection**: Leaf nodes with metric > 0 indicate leaked resources.

### Setting Handler

**Option A: Programmatically (Recommended)**

```java
import com.example.bytebuf.tracker.ObjectTrackerRegistry;

public static void main(String[] args) {
    ObjectTrackerRegistry.setHandler(new YourCustomTracker());
    startApplication();
}
```

**Option B: System Property**

```bash
java -Dobject.tracker.handler=com.yourcompany.YourTracker -jar app.jar
```

Or in Maven:

```xml
<systemProperty>
    <key>object.tracker.handler</key>
    <value>com.yourcompany.tracking.YourTracker</value>
</systemProperty>
```

### 2. LLM-Optimized Format: Structured Analysis

Structured text format designed for automated analysis and LLM parsing:

```
METADATA:
total_roots=6
total_traversals=210
total_paths=8
leak_paths=3
leak_percentage=37.50%

LEAKS:
leak|root=LeakyExample.allocate|final_ref=1|path=LeakyExample.allocate[ref=1,count=2] -> ErrorDecoder.decode[ref=1,count=1] -> Logger.logError[ref=1,count=0]

FLOWS:
flow|root=DirectExample.allocate|final_ref=0|is_leak=false|path=DirectExample.allocate[ref=1,count=2] -> Decoder.decode[ref=1,count=1] -> DirectExample.cleanup[ref=0,count=0]
flow|root=LeakyExample.allocate|final_ref=1|is_leak=true|path=LeakyExample.allocate[ref=1,count=2] -> ErrorDecoder.decode[ref=1,count=1] -> Logger.logError[ref=1,count=0]
```

**Format Details:**
- **METADATA section**: Key-value pairs with overall statistics
- **LEAKS section**: Pipe-delimited leak records with root, final_ref, and full path
- **FLOWS section**: All flows with leak status and complete paths
- Each node shows: `ClassName.methodName[ref=N,count=N]`

## Building and Testing

### Build the Project

```bash
# Build with Gradle
gradle -b build-standalone.gradle build

# Run the example
gradle -b build-standalone.gradle run
```

### Run Unit Tests

```bash
# Run all tests
gradle -b build-standalone.gradle test

# Run with verbose output
gradle -b build-standalone.gradle test --info
```

The test suite uses **JUnit 5** and includes:
- Empty tracker validation
- Single and multiple flow tracking
- Leak detection verification
- Output format validation
- Reset functionality testing

## Configuration

**File Handles:**

```java
public class FileHandleTracker implements ObjectTrackerHandler {
    public boolean shouldTrack(Object obj) {
        return obj instanceof RandomAccessFile;
    }
    public int getMetric(Object obj) {
        try {
            ((RandomAccessFile) obj).getFD();
            return 1; // Open
        } catch (Exception e) {
            return 0; // Closed
        }
    }
    public String getObjectType() { return "FileHandle"; }
}
```

**Multiple Object Types:**

```java
public class MultiResourceTracker implements ObjectTrackerHandler {
    public boolean shouldTrack(Object obj) {
        return obj instanceof Connection || obj instanceof RandomAccessFile;
    }
    public int getMetric(Object obj) {
        if (obj instanceof Connection) {
            return ((Connection) obj).isClosed() ? 0 : 1;
        } else if (obj instanceof RandomAccessFile) {
            try { ((RandomAccessFile) obj).getFD(); return 1; }
            catch (Exception e) { return 0; }
        }
        return 0;
    }
    public String getObjectType() { return "ManagedResource"; }
}
```

See `bytebuf-flow-example/src/main/java/com/example/demo/custom/` for complete examples.

### Performance Tips

- Keep `shouldTrack()` fast (called very frequently)
- Use simple `instanceof` checks (JIT-optimized)
- Avoid complex logic in metric extraction
- Track only critical objects to reduce overhead

---

## Configuration Reference

### Agent Arguments

Format: `include=package1,package2;exclude=package3,package4;trackConstructors=class1,class2`

**Parameters:**
- `include` - Packages to instrument (required)
- `exclude` - Packages to skip (optional)
- `trackConstructors` - Classes to enable constructor tracking (optional)

**Examples:**

```bash
# Track single package
-javaagent:tracker.jar=include=com.example

# Track multiple packages
-javaagent:tracker.jar=include=com.example,org.myapp

# Exclude subpackages
-javaagent:tracker.jar=include=com.example;exclude=com.example.legacy,com.example.test

# Exclude third-party
-javaagent:tracker.jar=include=com.example;exclude=org.apache,io.netty

# Enable constructor tracking for wrapper classes
-javaagent:tracker.jar=include=com.example;trackConstructors=com.example.Message,com.example.Request

# Use wildcards for constructor tracking
-javaagent:tracker.jar=include=com.example;trackConstructors=com.example.dto.*
```

**Constructor Tracking:**

By default, constructors are NOT tracked. Enable selectively for wrapper classes:

```bash
trackConstructors=com.example.Message,com.example.HttpRequest
```

This maintains continuous flow when ByteBuf is wrapped in custom objects:
```
allocate → prepareBuffer → Message.<init> → processMessage → cleanup
```

See [CONSTRUCTOR_TRACKING.md](CONSTRUCTOR_TRACKING.md) for details.

**Recommendations:**
- Start narrow (specific packages)
- Widen if needed
- Exclude packages without tracked objects
- Exclude third-party unless debugging them
- Enable constructor tracking only for wrapper classes that hold ByteBuf

### System Properties

```bash
# Custom object handler
-Dobject.tracker.handler=com.yourcompany.YourTracker

# JMX monitoring
-Dcom.sun.management.jmxremote=true
-Dcom.sun.management.jmxremote.port=9999
-Dcom.sun.management.jmxremote.authenticate=false
-Dcom.sun.management.jmxremote.ssl=false
```

### Output Formats

| Method | Description | Use Case |
|--------|-------------|----------|
| `renderIndentedTree()` | Hierarchical tree | Visual analysis |
| `renderFlatPaths()` | Root-to-leaf paths | Leak identification |
| `renderCsv()` | CSV format | Spreadsheet analysis |
| `renderJson()` | JSON format | Programmatic processing |
| `renderSummary()` | Statistics | Quick overview |

---

## Troubleshooting

### Agent not loading

**Symptoms**: No startup messages

**Solutions**:
1. Verify agent JAR path is correct
2. Ensure `-javaagent` comes BEFORE `-jar`
3. Confirm agent JAR was built: `mvn clean install`
4. Check you're using `-agent.jar` (fat JAR), not regular JAR

### No data appearing

**Symptoms**: Agent loads but no tracking data

**Solutions**:
1. Verify `include` packages match your code structure
2. Ensure objects are actually used in tracked packages
3. Test with broader include: `include=com,org`
4. Check agent logs for instrumentation messages

### Too much data

**Symptoms**: Overwhelming output

**Solutions**:
1. Narrow `include` packages to application code only
2. Add `exclude` patterns for noisy packages
3. Exclude test, utility, and third-party packages
4. Consider sampling in custom `ObjectTrackerHandler`

### Build fails

**Symptoms**: Cannot resolve dependency

**Solutions**:
1. Run `mvn clean install` in tracker project first
2. Verify `~/.m2/repository/com/example/bytebuf/` exists
3. Check version numbers match (1.0.0-SNAPSHOT)
4. Try `mvn dependency:purge-local-repository`

### ClassNotFoundException

**Symptoms**: Agent crashes with missing classes

**Solutions**:
1. Use `-agent.jar` (fat JAR) not regular JAR
2. Verify ByteBuddy dependencies are included
3. Ensure Netty is in application classpath

### Custom handler not working

**Symptoms**: Handler not being used

**Solutions**:
1. Is `setHandler()` called BEFORE tracked objects are created?
2. System property spelled correctly? `-Dobject.tracker.handler=...`
3. Check console for `[ObjectTrackerRegistry]` messages
4. Is `shouldTrack()` returning true? Add debug logging

### JMX connection fails

**Symptoms**: Cannot connect via JConsole

// Get human-readable tree view
String tree = renderer.renderIndentedTree();

// Get LLM-optimized format
String llmFormat = renderer.renderForLLM();

// Get summary statistics
String summary = renderer.renderSummary();
```

**Test coverage**: Simple flow tracking, leak detection, refCount anomalies, high-volume scenarios, CSV/JSON export.

### Documentation

- **[Library README](bytebuf-flow-tracker/README.md)** - API documentation, architecture
- **[Example README](bytebuf-flow-example/README.md)** - Integration patterns, best practices
- **[CLAUDE_CODE_INTEGRATION.md](CLAUDE_CODE_INTEGRATION.md)** - Claude Code specific guide (multi-module builds)
- **[MIGRATION_GUIDE.md](MIGRATION_GUIDE.md)** - Project restructuring history

### External Resources

- [ByteBuddy Documentation](https://bytebuddy.net/)
- [Netty ByteBuf Guide](https://netty.io/wiki/reference-counted-objects.html)
- [Java Agents Tutorial](https://www.baeldung.com/java-instrumentation)

---

## Contributing

This project demonstrates clean separation:
1. **`bytebuf-flow-tracker/`** - Reusable library (core functionality)
2. **`bytebuf-flow-example/`** - Examples (integration patterns)

To contribute:
- Library changes go in `bytebuf-flow-tracker/`
- Example changes go in `bytebuf-flow-example/`
- Keep modules independent (example depends on library, not vice versa)

**Note**: Project is not published to Maven Central. Users must build from source.

## License

Apache License 2.0

---

**Need help?** Check the module READMEs for detailed documentation.
**Found a bug?** Open an issue with reproducible example.
**Questions?** See the example module for common patterns.
