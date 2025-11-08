# ByteBuf Flow Tracker

A lightweight ByteBuddy-based Java agent for tracking object flows through your application using a Trie data structure. Detect memory leaks, understand flow patterns, and monitor any resource type.

## Features

- **Zero allocation overhead**: No stack trace collection or allocation site tracking
- **First-touch root**: The first method that handles a ByteBuf becomes the Trie root
- **Intelligent release tracking**: Tracks `release()` only when refCnt drops to 0, eliminating leak ambiguity
- **Memory efficient**: Trie structure shares common prefixes, minimizing memory usage
- **Real-time monitoring**: JMX MBean for runtime analysis
- **Dual output formats**: Human-readable tree view and LLM-optimized structured format

## Prerequisites

- **Java 8+**
- **Maven 3.6+** or **Gradle 6+**
- **Netty ByteBuf** in your application (or custom objects to track)
- **Not published to Maven Central** - must build from source

## Quick Start

### Build and Run Example

**Using Gradle (Recommended):**
```bash
# Clone and navigate to examples
git clone https://github.com/j-tyler/bytebuddy-bytebuf-tracer.git
cd bytebuddy-bytebuf-tracer/bytebuf-flow-example

# List all available examples
gradle listExamples

# Run basic ByteBuf tracking example
gradle runBasicExample

# Run custom object tracking (programmatic)
gradle runCustomObjectExample

# Run custom object tracking (Gradle config - zero code changes!)
gradle runCustomObjectViaGradle
```

**Using Maven (Alternative):**
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

---

## Tracking Capabilities

The tracker automatically instruments methods with ByteBuf parameters/returns. Additional features are available for advanced tracking scenarios:

### Release Tracking (NEW)

**Intelligent leak detection** - Tracks `release()` calls only when they drop refCnt to zero.

**Problem solved**: Previously, leaf nodes didn't show whether ByteBuf was released, causing ambiguity.

**Now**: Leaf nodes ending with `release() [ref=0]` are clean. Leaf nodes with `[ref>0]` are leaks.

```java
ByteBuf buffer = allocate();           // ✓ Tracked
processor.process(buffer);             // ✓ Tracked
buffer.release();                      // ✓ Tracked ONLY if refCnt -> 0
```

**Benefits**:
- Clear leak detection at leaf nodes
- No tree clutter from intermediate `release()` calls
- Tracks `retain()` to show refCount increases

**Interpreting Leaf Nodes**:

| Leaf Node Pattern | Meaning | Action |
|-------------------|---------|--------|
| `release() [ref=0]` | ByteBuf properly released | ✓ No action needed |
| `SomeMethod [ref=1]` | ByteBuf not released | ⚠️ Investigate for leak |
| `SomeMethod [ref>1]` | ByteBuf retained but not released | ⚠️ Investigate for leak |

**Complex Example - Multiple Retain/Release**:

```java
ByteBuf buffer = Unpooled.buffer(256);          // ref=1

buffer.retain();                                 // ref=1 -> 2 ✓ TRACKED
processor.process(buffer);                       // ref=2

buffer.retain();                                 // ref=2 -> 3 ✓ TRACKED
worker.work(buffer);                             // ref=3

buffer.release();                                // ref=3 -> 2 ✗ SKIPPED (intermediate)
worker.cleanup(buffer);                          // ref=2

buffer.release();                                // ref=2 -> 1 ✗ SKIPPED (intermediate)
processor.finish(buffer);                        // ref=1

buffer.release();                                // ref=1 -> 0 ✓ TRACKED (final)
```

**Flow Tree**:
```
ROOT: Processor.process [count=1]
└── UnpooledHeapByteBuf.retain [ref=2, count=1]
    └── Worker.work [ref=3, count=1]
        └── UnpooledHeapByteBuf.retain [ref=3, count=1]
            └── Worker.cleanup [ref=2, count=1]
                └── Processor.finish [ref=1, count=1]
                    └── UnpooledHeapByteBuf.release [ref=0, count=1]  ✓ Clean
```

**Note**: Only retain calls and the FINAL release are tracked. Intermediate releases are skipped to keep the tree clean.

### Static Method Tracking

**Enabled by default** - Static methods are automatically tracked.

```java
public static ByteBuf compress(ByteBuf buffer) { }  // ✓ Tracked
```

### Wrapper Object Tracking

**Important for real applications** - When ByteBuf is wrapped in custom objects (Message, Request, Event), tracking breaks by default.

**Enable constructor tracking** for your wrapper classes to maintain continuous flow:

```bash
-javaagent:tracker.jar=trackConstructors=com.example.Message,com.example.Request
```

**Combine with manual tracking** for methods receiving wrappers:

```java
public class Message {
    public Message(ByteBuf data) { }  // ✓ Tracked with trackConstructors
}

public void processMessage(Message msg) {
    ByteBuf buf = msg.getData();
    tracker.recordMethodCall(buf, "Handler", "processMessage", buf.refCnt());  // Manual tracking needed
}
```

---

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

<!-- Step 3: Configure agent -->
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
```

### Method 2: Git Submodule (Multi-module projects)

**Best for**: Multi-module projects, continuous integration, Claude Code

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

### Method 3: Gradle Composite Build (Recommended for Gradle users)

**Best for**: Gradle projects, clean builds from source, zero publishing required

```bash
# Step 1: Clone the tracker as a sibling directory
cd your-project/
git clone https://github.com/j-tyler/bytebuddy-bytebuf-tracer.git ../bytebuddy-bytebuf-tracer
```

```gradle
// Step 2: Add to your settings.gradle
pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

rootProject.name = 'your-project'

// Include the tracker as a composite build
includeBuild('../bytebuddy-bytebuf-tracer/bytebuf-flow-tracker')
```

```gradle
// Step 3: Add to your build.gradle
dependencies {
    implementation 'com.example.bytebuf:bytebuf-flow-tracker:1.0.0-SNAPSHOT'
}

// Step 4: Configure agent for your run task
def getAgentJar() {
    return file("../bytebuddy-bytebuf-tracer/bytebuf-flow-tracker/build/libs/bytebuf-flow-tracker-1.0.0-SNAPSHOT-agent.jar")
}

task runWithAgent(type: JavaExec) {
    mainClass = 'com.yourcompany.Main'
    classpath = sourceSets.main.runtimeClasspath

    jvmArgs = [
        "-javaagent:${getAgentJar()}=include=com.yourcompany"
    ]
}
```

**Benefits of Gradle Composite Build:**
- Builds tracker from source automatically
- No need to publish to Maven Central or local repository
- Changes to tracker are immediately available
- Clean, reproducible builds

**Example with Custom Object Tracking:**
```gradle
task runWithCustomTracking(type: JavaExec) {
    mainClass = 'com.yourcompany.Main'
    classpath = sourceSets.main.runtimeClasspath

    jvmArgs = [
        "-javaagent:${getAgentJar()}=include=com.yourcompany;trackConstructors=com.yourcompany.Message",
        "-Dobject.tracker.handler=com.yourcompany.custom.ConnectionTracker"
    ]
}
```

See `bytebuf-flow-example/build.gradle` for a complete, realistic example.

### Method 4: Gradle with Local Maven Repository

**Best for**: Simple Gradle projects, testing

```bash
# Step 1: Build and install
cd bytebuddy-bytebuf-tracer
mvn clean install
```

```gradle
// Step 2: Add to your build.gradle
repositories {
    mavenLocal()  // Check local Maven repository first
    mavenCentral()
}

dependencies {
    implementation 'com.example.bytebuf:bytebuf-flow-tracker:1.0.0-SNAPSHOT'
}

// Step 3: Configure agent
task runWithAgent(type: JavaExec) {
    mainClass = 'com.yourcompany.Main'
    classpath = sourceSets.main.runtimeClasspath

    def agentJar = "${System.getProperty('user.home')}/.m2/repository/com/example/bytebuf/bytebuf-flow-tracker/1.0.0-SNAPSHOT/bytebuf-flow-tracker-1.0.0-SNAPSHOT-agent.jar"

    jvmArgs = ["-javaagent:${agentJar}=include=com.yourcompany"]
}
```

### Method 5: Copy Source Directly

**Best for**: Full control, embedded in monorepo

```bash
# Copy library module
cp -r /path/to/bytebuddy-bytebuf-tracer/bytebuf-flow-tracker your-project/modules/
```

Then follow the same pom.xml configuration as Method 2 (for Maven) or Method 3 (for Gradle).

## Configuration

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

# Exclude protocol/DTO classes to avoid Mockito conflicts
-javaagent:tracker.jar=include=com.github.ambry;exclude=com.github.ambry.protocol

# Enable constructor tracking for wrapper classes
-javaagent:tracker.jar=include=com.example;trackConstructors=com.example.Message,com.example.Request

# Use wildcards for constructor tracking
-javaagent:tracker.jar=include=com.example;trackConstructors=com.example.dto.*
```

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

## Accessing Tracking Data

### Programmatic Access

```java
import com.example.bytebuf.tracker.ByteBufFlowTracker;
import com.example.bytebuf.tracker.view.TrieRenderer;

ByteBufFlowTracker tracker = ByteBufFlowTracker.getInstance();
TrieRenderer renderer = new TrieRenderer(tracker.getTrie());

// View tracking data
System.out.println(renderer.renderSummary());        // Statistics
System.out.println(renderer.renderIndentedTree());   // Tree view
System.out.println(renderer.renderForLLM());         // LLM-optimized format

// Active monitoring
int activeObjects = tracker.getActiveFlowCount();    // Currently tracked objects
int rootMethods = tracker.getTrie().getRootCount();  // Root methods
System.out.println("Active flows: " + activeObjects + ", Roots: " + rootMethods);
```

### Output Formats

**Human-Readable Tree:**
```
ROOT: DemoApplication.handleRequest [count=5]
└── MessageProcessor.process [ref=1, count=5]
    └── MessageProcessor.cleanup [ref=0, count=5]
```

**LLM-Optimized Format:**
```
METADATA:
total_roots=6
total_traversals=210
leak_percentage=37.50%

LEAKS:
leak|root=LeakyExample.allocate|final_ref=1|path=LeakyExample.allocate[ref=1] -> Logger.logError[ref=1]

FLOWS:
flow|root=DirectExample.allocate|final_ref=0|is_leak=false|path=DirectExample.allocate[ref=1] -> cleanup[ref=0]
```

### Automatic Shutdown Report

The agent automatically registers a shutdown hook that prints a comprehensive final report when the JVM exits. This is useful for:
- Batch jobs that run and exit
- Short-lived processes
- Testing scenarios
- Getting results without setting up JMX

**What's included in the shutdown report:**
- Summary statistics (total roots, paths, leaks)
- Full flow tree
- List of all detected leaks with complete paths

**Example output:**
```
=== ByteBuf Flow Final Report ===
ByteBuf Flow Analysis Report
Generated: Thu Nov 08 12:34:56 UTC 2025
================================================================================

=== ByteBuf Flow Summary ===
Total Root Methods: 3
Total Traversals: 15
Unique Paths: 4
Leak Paths: 1
Leak Percentage: 25.00%

=== Flow Tree ===
ROOT: Client.allocate [count=10]
└── Handler.process [ref=1, count=10]
    └── Handler.cleanup [ref=0, count=9]

=== Potential Leaks ===
[count=1] [LEAK:ref=1] Client.allocate -> Handler.process[1]
```

**Note**: This report is printed to stdout during JVM shutdown. To disable, you would need to modify the agent code (not configurable via arguments).

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

**Understanding what gets instrumented**:

The agent only instruments methods that have ByteBuf in their signature (parameters or return type):

```java
// ✓ INSTRUMENTED - Has ByteBuf parameter
public void processByteBuf(ByteBuf buf) { }

// ✓ INSTRUMENTED - Returns ByteBuf
public ByteBuf createBuffer() { }

// ✗ NOT INSTRUMENTED - No ByteBuf in signature
public void setName(String name) { }

// ✗ NOT INSTRUMENTED - ByteBuf is wrapped
public void processMessage(Message msg) { }  // Even if Message contains ByteBuf
```

If a class has NO ByteBuf methods, it won't be transformed at all.

### Too much data

**Symptoms**: Overwhelming output

**Solutions**:
1. Narrow `include` packages to application code only
2. Add `exclude` patterns for noisy packages
3. Exclude test, utility, and third-party packages

### Mockito test failures

**Symptoms**: `Mockito cannot mock this class` or `class redefinition failed`

**Root Cause**: The agent transforms classes with ByteBuf. When Mockito 5 tries to retransform an already-transformed class, the JVM rejects it.

**Solution**: Exclude DTO/protocol/response classes that are mocked:
```bash
-javaagent:tracker.jar=include=com.yourcompany;exclude=com.yourcompany.protocol,com.yourcompany.dto
```

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

### Mockito test failures

**Symptoms**: `Mockito cannot mock this class` or `class redefinition failed: attempted to delete a method`

**Root Cause**: The agent transforms classes with ByteBuf in their signatures. When Mockito 5 tries to retransform an already-transformed class, the JVM rejects it.

**Solution**: As of version 1.0.0-SNAPSHOT, **org.mockito packages are excluded by default**, so Mockito itself works seamlessly with the agent. However, if you mock your own application classes that have ByteBuf in their signatures, you may need additional exclusions:

1. **Exclude DTO/protocol/response classes** that are mocked but don't cause leaks:
   ```bash
   -javaagent:tracker.jar=include=com.yourcompany;exclude=com.yourcompany.protocol,com.yourcompany.dto
   ```

2. **Exclude specific packages** commonly mocked in tests:
   ```bash
   # Exclude classes implementing ByteBufHolder (data carriers)
   -javaagent:tracker.jar=include=com.github.ambry;exclude=com.github.ambry.protocol
   ```

3. **Pattern**: Exclude packages containing:
   - DTOs (Data Transfer Objects)
   - Protocol messages/responses
   - Classes implementing `ByteBufHolder` that are just data carriers
   - Any class that is mocked in tests and has ByteBuf in its signature

**Why this works**: Excluding these classes prevents the agent from transforming them, allowing Mockito to work normally. These classes typically don't cause leaks anyway (they're just data containers).

**Default Exclusions**: The agent automatically excludes the following packages:
- `org.mockito.*` - Mockito framework classes
- `java.*` - JDK classes
- `sun.*` - JDK internal classes
- `net.bytebuddy.*` - ByteBuddy instrumentation framework

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

## External Resources

- [ByteBuddy Documentation](https://bytebuddy.net/)
- [Netty ByteBuf Guide](https://netty.io/wiki/reference-counted-objects.html)
- [Java Agents Tutorial](https://www.baeldung.com/java-instrumentation)

