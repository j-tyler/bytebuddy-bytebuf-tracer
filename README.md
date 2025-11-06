# ByteBuf Flow Tracker

A lightweight, efficient ByteBuddy-based tool for tracking ByteBuf flows through your application using a Trie data structure.

## 🎯 Project Overview

This project provides a reusable Java agent that can track Netty ByteBuf objects as they flow through your application, helping you:

- **Detect memory leaks**: Find ByteBufs that aren't properly released
- **Understand flow patterns**: See how ByteBufs move through your code
- **Identify anomalies**: Spot unusual reference count patterns
- **Optimize performance**: Find hot paths and bottlenecks

## 📦 Project Structure

This is a multi-module Maven project:

```
bytebuddy-bytebuf-tracer/
├── pom.xml                          # Parent POM
├── bytebuf-flow-tracker/            # Module 1: Reusable library
│   ├── src/                         # Tracker implementation + agent
│   ├── pom.xml                      # Library dependencies & build
│   └── README.md                    # Library documentation
└── bytebuf-flow-example/            # Module 2: Usage example
    ├── src/                         # Demo application
    ├── pom.xml                      # Shows how to use the tracker
    └── README.md                    # Example documentation
```

### Module 1: `bytebuf-flow-tracker`

The **reusable library** that can be pulled into any project:

- Core tracking logic and data structures
- ByteBuddy Java agent for instrumentation
- JMX monitoring interface
- Multiple output formats (tree, flat, CSV, JSON)
- Comprehensive unit tests

**Use this module as a dependency in your projects.**

### Module 2: `bytebuf-flow-example`

A **complete working example** showing how to integrate the tracker:

- Sample application with ByteBuf usage patterns
- Demonstrates normal flows, error handling, and leaks
- Shows Maven configuration for the agent
- Example of programmatic access to tracking data

**Use this as a template for integrating into your own projects.**

---

## 🚀 Integration Guide: Using This Project in Your Codebase

This guide provides detailed, step-by-step instructions for integrating the ByteBuf Flow Tracker into your existing Java project. Follow these instructions to add ByteBuf tracking capabilities to your application.

**Important Note:** This project is not published to Maven Central or any public Maven repository. You must build it from source and integrate it into your project using one of the methods below.

### Prerequisites

Before integrating, ensure you have:

1. **Java 8 or higher** installed
2. **Maven 3.6+** or **Gradle 6+** as your build tool
3. **Netty ByteBuf** usage in your application
4. **Git** (if using git submodule approach)

### Integration Method 1: Local Build + Local Maven Repository

This method builds the tracker locally and installs it to your local Maven repository (`~/.m2/repository`).

#### Step 1: Clone and Build the Tracker

```bash
# Clone this repository
git clone https://github.com/j-tyler/bytebuddy-bytebuf-tracer.git
cd bytebuddy-bytebuf-tracer

# Build and install to local Maven repository
mvn clean install
```

This creates:
- `~/.m2/repository/com/example/bytebuf/bytebuf-flow-tracker/1.0.0-SNAPSHOT/bytebuf-flow-tracker-1.0.0-SNAPSHOT.jar` (library)
- `~/.m2/repository/com/example/bytebuf/bytebuf-flow-tracker/1.0.0-SNAPSHOT/bytebuf-flow-tracker-1.0.0-SNAPSHOT-agent.jar` (agent with dependencies)

#### Step 2: Add Dependency to Your Project's pom.xml

In your project's `pom.xml`, add the dependency:

```xml
<dependencies>
    <!-- Existing dependencies -->

    <!-- ByteBuf Flow Tracker (built locally, not from Maven Central) -->
    <dependency>
        <groupId>com.example.bytebuf</groupId>
        <artifactId>bytebuf-flow-tracker</artifactId>
        <version>1.0.0-SNAPSHOT</version>
    </dependency>
</dependencies>
```

**Note:** This dependency resolves from your local Maven repository (`~/.m2/repository`) after running `mvn clean install` in Step 1.

#### Step 3: Configure the Java Agent

**Option A: Maven Exec Plugin (Development)**

Add to your `pom.xml`:

```xml
<build>
    <plugins>
        <!-- Existing plugins -->

        <plugin>
            <groupId>org.codehaus.mojo</groupId>
            <artifactId>exec-maven-plugin</artifactId>
            <version>3.1.0</version>
            <configuration>
                <mainClass>com.yourcompany.yourapp.Main</mainClass>
                <arguments>
                    <argument>-javaagent:${settings.localRepository}/com/example/bytebuf/bytebuf-flow-tracker/1.0.0-SNAPSHOT/bytebuf-flow-tracker-1.0.0-SNAPSHOT-agent.jar=include=com.yourcompany</argument>
                </arguments>
                <systemProperties>
                    <!-- Optional: Enable JMX monitoring -->
                    <systemProperty>
                        <key>com.sun.management.jmxremote</key>
                        <value>true</value>
                    </systemProperty>
                    <systemProperty>
                        <key>com.sun.management.jmxremote.port</key>
                        <value>9999</value>
                    </systemProperty>
                    <systemProperty>
                        <key>com.sun.management.jmxremote.authenticate</key>
                        <value>false</value>
                    </systemProperty>
                    <systemProperty>
                        <key>com.sun.management.jmxremote.ssl</key>
                        <value>false</value>
                    </systemProperty>
                </systemProperties>
            </configuration>
        </plugin>
    </plugins>
</build>
```

**Important**: Replace `com.yourcompany.yourapp.Main` with your actual main class, and `com.yourcompany` with your package prefix to track.

Run with:
```bash
mvn exec:java
```

**Option B: Maven Surefire Plugin (Testing)**

To enable tracking during tests, add to your `pom.xml`:

```xml
<build>
    <plugins>
        <plugin>
            <groupId>org.apache.maven.plugins</groupId>
            <artifactId>maven-surefire-plugin</artifactId>
            <version>3.0.0</version>
            <configuration>
                <argLine>
                    -javaagent:${settings.localRepository}/com/example/bytebuf/bytebuf-flow-tracker/1.0.0-SNAPSHOT/bytebuf-flow-tracker-1.0.0-SNAPSHOT-agent.jar=include=com.yourcompany
                </argLine>
            </configuration>
        </plugin>
    </plugins>
</build>
```

Run tests with:
```bash
mvn test
```

**Option C: Manual Java Command (Production)**

Copy the agent JAR to your deployment:

```bash
# Copy agent JAR to your project
cp ~/.m2/repository/com/example/bytebuf/bytebuf-flow-tracker/1.0.0-SNAPSHOT/bytebuf-flow-tracker-1.0.0-SNAPSHOT-agent.jar \
   /path/to/your/project/lib/
```

Run your application with:

```bash
java -javaagent:/path/to/your/project/lib/bytebuf-flow-tracker-1.0.0-SNAPSHOT-agent.jar=include=com.yourcompany \
     -Dcom.sun.management.jmxremote \
     -Dcom.sun.management.jmxremote.port=9999 \
     -Dcom.sun.management.jmxremote.authenticate=false \
     -Dcom.sun.management.jmxremote.ssl=false \
     -jar your-application.jar
```

#### Step 4: Configure Package Filtering

The agent argument format is: `include=package1,package2;exclude=package3,package4`

**Examples:**

- Track everything in your company's packages:
  ```
  include=com.yourcompany
  ```

- Track multiple package trees:
  ```
  include=com.yourcompany,org.yourdomain
  ```

- Exclude specific packages (like test or legacy code):
  ```
  include=com.yourcompany;exclude=com.yourcompany.legacy,com.yourcompany.test
  ```

**Recommendations:**
- Start narrow (specific packages) and widen if needed
- Exclude packages that don't use ByteBufs to reduce overhead
- Exclude third-party libraries unless debugging their ByteBuf usage

#### Step 5: Access Tracking Data

**Programmatic Access:**

Add this code to your application to print tracking data:

```java
import com.example.bytebuf.tracker.ByteBufFlowTracker;
import com.example.bytebuf.tracker.view.TrieRenderer;

public class YourClass {
    public void printByteBufAnalysis() {
        ByteBufFlowTracker tracker = ByteBufFlowTracker.getInstance();
        TrieRenderer renderer = new TrieRenderer(tracker.getTrie());

        // Print summary
        System.out.println(renderer.renderSummary());

        // Print tree view
        System.out.println("\n=== Flow Tree ===");
        System.out.println(renderer.renderIndentedTree());

        // Print flat paths (shows leaks clearly)
        System.out.println("\n=== Flat Paths ===");
        System.out.println(renderer.renderFlatPaths());
    }
}
```

**JMX Access:**

If you enabled JMX (see Option C above), connect with JConsole:

```bash
jconsole localhost:9999
```

Navigate to: `MBeans` → `com.example` → `ByteBufFlowTracker`

Available operations:
- `getTreeView()` - Hierarchical tree view
- `getFlatView()` - Flat root-to-leaf paths
- `getCsvView()` - CSV format
- `getJsonView()` - JSON format
- `getSummary()` - Statistics
- `reset()` - Clear tracking data

#### Step 6: Verify Integration

Run your application and check for these indicators:

1. **Agent loaded successfully:**
   ```
   [ByteBufFlowAgent] Starting with config: AgentConfig{...}
   [ByteBufFlowAgent] Instrumentation installed successfully
   [ByteBufFlowAgent] JMX MBean registered
   ```

2. **ByteBuf operations are tracked:**
   - Run your application normally
   - Call the tracking API or check JMX
   - You should see method calls and flow trees

3. **Look for leaks:**
   - Leaf nodes with `⚠️ LEAK` indicate ByteBufs not released
   - These show as `[ref=N]` where N > 0 at leaf positions

### Integration Method 2: Git Submodule (Recommended for Claude Code)

Use this method to keep the tracker code within your repository and build it as part of your project's build. **This is the recommended approach for Claude Code projects.**

#### Step 1: Add as Git Submodule

```bash
# From your project root
cd your-project/
git submodule add https://github.com/j-tyler/bytebuddy-bytebuf-tracer.git lib/bytebuddy-bytebuf-tracer
git submodule update --init --recursive
```

#### Step 2: Build the Tracker

```bash
cd lib/bytebuddy-bytebuf-tracer
mvn clean install
cd ../..
```

#### Step 3: Include as Multi-Module Build

Add the tracker as a module in your parent `pom.xml`:

```xml
<modules>
    <module>your-existing-module</module>
    <!-- Add the tracker module -->
    <module>lib/bytebuddy-bytebuf-tracer/bytebuf-flow-tracker</module>
</modules>

<!-- In your application module's pom.xml -->
<dependencies>
    <dependency>
        <groupId>com.example.bytebuf</groupId>
        <artifactId>bytebuf-flow-tracker</artifactId>
        <version>1.0.0-SNAPSHOT</version>
    </dependency>
</dependencies>
```

**Note:** This makes the tracker part of your project's build. Running `mvn clean install` at the root builds everything in one command.

#### Step 4: Configure Agent

Use relative path to agent JAR in your exec plugin:

```xml
<plugin>
    <groupId>org.codehaus.mojo</groupId>
    <artifactId>exec-maven-plugin</artifactId>
    <version>3.1.0</version>
    <configuration>
        <mainClass>com.yourcompany.yourapp.Main</mainClass>
        <arguments>
            <argument>-javaagent:${project.basedir}/lib/bytebuddy-bytebuf-tracer/bytebuf-flow-tracker/target/bytebuf-flow-tracker-1.0.0-SNAPSHOT-agent.jar=include=com.yourcompany</argument>
        </arguments>
    </configuration>
</plugin>
```

### Integration Method 3: Copy Source Code Directly

Use this method to fully embed the tracker in your project without git submodule.

#### Step 1: Copy the Library Module

```bash
# From your project root
cp -r /path/to/bytebuddy-bytebuf-tracer/bytebuf-flow-tracker your-project/modules/
```

#### Step 2: Add Module to Your Parent POM

In your project's parent `pom.xml`:

```xml
<modules>
    <module>your-existing-module</module>
    <!-- Add this: -->
    <module>modules/bytebuf-flow-tracker</module>
</modules>
```

#### Step 3: Add Dependency

In the module where you want to use the tracker:

```xml
<dependencies>
    <dependency>
        <groupId>com.example.bytebuf</groupId>
        <artifactId>bytebuf-flow-tracker</artifactId>
        <version>1.0.0-SNAPSHOT</version>
    </dependency>
</dependencies>
```

#### Step 4: Configure Agent

Reference the module's target directory:

```xml
<plugin>
    <groupId>org.codehaus.mojo</groupId>
    <artifactId>exec-maven-plugin</artifactId>
    <version>3.1.0</version>
    <configuration>
        <mainClass>com.yourcompany.yourapp.Main</mainClass>
        <arguments>
            <argument>-javaagent:${project.parent.basedir}/modules/bytebuf-flow-tracker/target/bytebuf-flow-tracker-1.0.0-SNAPSHOT-agent.jar=include=com.yourcompany</argument>
        </arguments>
    </configuration>
</plugin>
```

### Gradle Integration

If your project uses Gradle instead of Maven, you'll need to first build the tracker with Maven, then reference it:

#### Step 1: Build the Tracker

```bash
cd /path/to/bytebuddy-bytebuf-tracer
mvn clean install
```

#### Step 2: Configure build.gradle

```groovy
dependencies {
    // Reference from local Maven repository (after mvn install above)
    implementation 'com.example.bytebuf:bytebuf-flow-tracker:1.0.0-SNAPSHOT'
}

// Configure agent for running
task runWithAgent(type: JavaExec) {
    classpath = sourceSets.main.runtimeClasspath
    mainClass = 'com.yourcompany.yourapp.Main'

    jvmArgs = [
        "-javaagent:${System.getProperty('user.home')}/.m2/repository/com/example/bytebuf/bytebuf-flow-tracker/1.0.0-SNAPSHOT/bytebuf-flow-tracker-1.0.0-SNAPSHOT-agent.jar=include=com.yourcompany",
        "-Dcom.sun.management.jmxremote",
        "-Dcom.sun.management.jmxremote.port=9999",
        "-Dcom.sun.management.jmxremote.authenticate=false",
        "-Dcom.sun.management.jmxremote.ssl=false"
    ]
}

// Configure agent for tests
test {
    jvmArgs "-javaagent:${System.getProperty('user.home')}/.m2/repository/com/example/bytebuf/bytebuf-flow-tracker/1.0.0-SNAPSHOT/bytebuf-flow-tracker-1.0.0-SNAPSHOT-agent.jar=include=com.yourcompany"
}
```

Run with:
```bash
gradle runWithAgent
```

**Note:** Gradle will resolve the dependency from your local Maven repository. You must run `mvn clean install` in the tracker project before the first Gradle build.

---

## 📖 Documentation

- **[Library README](bytebuf-flow-tracker/README.md)** - Detailed API documentation, architecture, and usage
- **[Example README](bytebuf-flow-example/README.md)** - Integration guide and best practices
- **[CLAUDE_CODE_INTEGRATION.md](CLAUDE_CODE_INTEGRATION.md)** - **Specific guide for Claude Code projects** (build from source strategy)
- **[MIGRATION_GUIDE.md](MIGRATION_GUIDE.md)** - Project restructuring documentation

## 🔍 How It Works

1. **ByteBuddy Instrumentation**: The agent intercepts all public/protected methods in specified packages
2. **First Touch = Root**: The first method to handle a ByteBuf becomes its root in the Trie
3. **Path Building**: Each subsequent method call adds a node to the tree
4. **RefCount Tracking**: Each node records the ByteBuf's reference count at that point
5. **Leak Detection**: Leaf nodes with non-zero refCount indicate memory leaks

### Example Output

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

## 🛠️ Configuration

Agent arguments format: `include=package1,package2;exclude=package3,package4`

Examples:
- `include=com.example` - Track everything in com.example
- `include=com.example,com.myapp` - Track multiple packages
- `include=com.example;exclude=com.example.legacy` - Exclude specific packages

## 🧪 Testing

Run the library tests:

```bash
cd bytebuf-flow-tracker
mvn test
```

Tests cover:
- Simple flow tracking
- Leak detection
- RefCount anomalies
- High-volume scenarios
- CSV/JSON export

## 🔧 Troubleshooting

### Problem: Agent not loading

**Symptoms:** No startup messages, tracking doesn't work

**Solutions:**
1. Verify agent JAR path is correct
2. Check that `-javaagent` comes before `-jar` in command line
3. Ensure the agent JAR was built with `mvn install`

### Problem: No data appearing

**Symptoms:** Agent loads but no tracking data

**Solutions:**
1. Verify `include` packages match your code (check your package structure)
2. Ensure ByteBufs are actually being used in tracked packages
3. Add debug logging to see which classes are being instrumented
4. Start with a broader include (e.g., `include=com,org`) to test

### Problem: Too much data

**Symptoms:** Overwhelming amount of tracking output

**Solutions:**
1. Narrow the `include` packages to only your application code
2. Add `exclude` patterns for noisy packages
3. Exclude test and utility packages
4. Consider adding sampling logic to `ByteBufTrackingAdvice`

### Problem: JMX connection fails

**Symptoms:** Cannot connect via JConsole

**Solutions:**
1. Verify JMX port is not in use: `netstat -an | grep 9999`
2. Check firewall settings
3. Try connecting locally first: `jconsole localhost:9999`
4. Ensure all JMX system properties are set

### Problem: Build fails with Maven

**Symptoms:** Cannot resolve dependency

**Solutions:**
1. Run `mvn clean install` in the tracker project first
2. Check your `~/.m2/repository/com/example/bytebuf/` directory exists
3. Verify version numbers match (1.0.0-SNAPSHOT)
4. Try `mvn dependency:purge-local-repository` to refresh

### Problem: ClassNotFoundException at runtime

**Symptoms:** Agent loads but crashes with missing classes

**Solutions:**
1. Use the `-agent.jar` (fat JAR) not the regular JAR
2. Verify all ByteBuddy dependencies are included
3. Check that Netty is in your application's classpath

## 📈 Performance Impact

- Minimal overhead: ~5-10% in high-throughput scenarios
- No allocation overhead (no stack traces)
- Lock-free concurrent data structures
- JIT-friendly implementation
- Can be disabled in production by not loading the agent

## 🎨 Tracking Custom Objects (Not Just ByteBuf)

While designed for ByteBuf, **the tracker can monitor ANY object type** in your codebase. This section explains how to track your own objects.

### Quick Example: Track Database Connections

```java
// 1. Implement the ObjectTrackerHandler interface
public class ConnectionTracker implements ObjectTrackerHandler {
    public boolean shouldTrack(Object obj) {
        return obj instanceof Connection;
    }

    public int getMetric(Object obj) {
        Connection conn = (Connection) obj;
        return conn.isClosed() ? 0 : 1;  // 0 = closed, 1 = open
    }

    public String getObjectType() {
        return "DatabaseConnection";
    }
}

// 2. Set your handler at application startup (before agent starts tracking)
public static void main(String[] args) {
    ObjectTrackerRegistry.setHandler(new ConnectionTracker());
    // ... rest of your application
}
```

That's it! The tracker will now monitor database connections instead of ByteBufs.

### How It Works

The tracker uses an **ObjectTrackerHandler** interface with three methods:

1. **`shouldTrack(Object)`** - Is this object something to track?
2. **`getMetric(Object)`** - Extract an integer metric from it
3. **`getObjectType()`** - What's the name for reports?

By default, it tracks ByteBuf objects using their reference count. You can replace this with your own handler.

### Detailed Integration Steps

#### Step 1: Create Your Handler Class

Create a class implementing `ObjectTrackerHandler`:

```java
package com.yourcompany.tracking;

import com.example.bytebuf.tracker.ObjectTrackerHandler;
import java.io.RandomAccessFile;

public class FileHandleTracker implements ObjectTrackerHandler {

    @Override
    public boolean shouldTrack(Object obj) {
        // Track RandomAccessFile instances
        return obj instanceof RandomAccessFile;
    }

    @Override
    public int getMetric(Object obj) {
        if (obj instanceof RandomAccessFile) {
            RandomAccessFile file = (RandomAccessFile) obj;
            try {
                file.getFD();  // Throws if closed
                return 1;  // File is open
            } catch (Exception e) {
                return 0;  // File is closed
            }
        }
        return 0;
    }

    @Override
    public String getObjectType() {
        return "FileHandle";
    }
}
```

#### Step 2: Set Your Handler at Startup

**Option A: Programmatically (Recommended)**

Set it in your main() method or static initializer BEFORE any tracked objects are created:

```java
import com.example.bytebuf.tracker.ObjectTrackerRegistry;

public class YourApplication {
    public static void main(String[] args) {
        // Set custom handler FIRST
        ObjectTrackerRegistry.setHandler(new FileHandleTracker());

        // Then run your application
        startApplication();
    }
}
```

**Option B: Via System Property**

Pass the handler class name as a system property:

```bash
java -javaagent:bytebuf-flow-tracker-agent.jar=include=com.yourcompany \
     -Dobject.tracker.handler=com.yourcompany.tracking.FileHandleTracker \
     -jar your-application.jar
```

Or in Maven pom.xml:

```xml
<plugin>
    <groupId>org.codehaus.mojo</groupId>
    <artifactId>exec-maven-plugin</artifactId>
    <configuration>
        <mainClass>com.yourcompany.Main</mainClass>
        <systemProperties>
            <systemProperty>
                <key>object.tracker.handler</key>
                <value>com.yourcompany.tracking.FileHandleTracker</value>
            </systemProperty>
        </systemProperties>
    </configuration>
</plugin>
```

#### Step 3: Run with Agent

Use the same agent setup as before, just with your custom handler:

```bash
mvn exec:java
```

The tracker will now monitor your custom objects!

### Choosing the Right Metric

The `getMetric()` method should return an integer representing the object's state:

**Common Patterns:**

| Object Type | Metric | Meaning |
|-------------|--------|---------|
| ByteBuf | `refCnt()` | Reference count (0 = released) |
| Connection | `isClosed() ? 0 : 1` | 0 = closed, 1 = open |
| FileHandle | `isOpen() ? 1 : 0` | 0 = closed, 1 = open |
| SocketChannel | `isOpen() ? 1 : 0` | 0 = closed, 1 = open |
| Semaphore | `availablePermits()` | Number of available permits |
| AtomicInteger | `get()` | Current value |

**Leak Detection Rule:**
- Leaf nodes with **metric = 0** → Properly released ✅
- Leaf nodes with **metric > 0** → Leaked resource ⚠️

### Complete Example: File Handle Tracking

See the example module for a complete working example:
- `FileHandleTracker.java` - Tracks RandomAccessFile
- `DatabaseConnectionTracker.java` - Tracks JDBC Connections
- `CustomObjectExample.java` - Demo application

Run it:

```bash
cd bytebuf-flow-example
mvn exec:java -Dexec.mainClass="com.example.demo.custom.CustomObjectExample"
```

### Advanced: Track Multiple Object Types

You can track multiple object types in a single handler:

```java
public class MultiObjectTracker implements ObjectTrackerHandler {

    @Override
    public boolean shouldTrack(Object obj) {
        return obj instanceof Connection ||
               obj instanceof RandomAccessFile ||
               obj instanceof SocketChannel;
    }

    @Override
    public int getMetric(Object obj) {
        if (obj instanceof Connection) {
            return ((Connection) obj).isClosed() ? 0 : 1;
        } else if (obj instanceof RandomAccessFile) {
            try {
                ((RandomAccessFile) obj).getFD();
                return 1;
            } catch (Exception e) {
                return 0;
            }
        } else if (obj instanceof SocketChannel) {
            return ((SocketChannel) obj).isOpen() ? 1 : 0;
        }
        return 0;
    }

    @Override
    public String getObjectType() {
        return "ManagedResource";
    }
}
```

### Performance Considerations

- **Keep `shouldTrack()` fast** - Called for every parameter/return value
- **Use `instanceof` checks** - They're fast and JIT-optimized
- **Avoid complex logic** - The handler is called very frequently
- **Consider filtering** - Track only critical objects to reduce overhead

### Real-World Use Cases

**Database Connection Pools:**
```java
// Track connections to find leaks in connection pool usage
public class ConnectionPoolTracker implements ObjectTrackerHandler {
    public boolean shouldTrack(Object obj) {
        return obj instanceof PooledConnection;
    }
    // ... track open/closed state
}
```

**File Descriptors:**
```java
// Track file handles to find unclosed files
public class FileDescriptorTracker implements ObjectTrackerHandler {
    public boolean shouldTrack(Object obj) {
        return obj instanceof FileInputStream ||
               obj instanceof FileOutputStream;
    }
    // ... track open/closed state
}
```

**Network Channels:**
```java
// Track socket channels to find connection leaks
public class ChannelTracker implements ObjectTrackerHandler {
    public boolean shouldTrack(Object obj) {
        return obj instanceof SocketChannel;
    }
    // ... track connected/disconnected state
}
```

**Custom Objects:**
```java
// Track your own resource classes
public class MyResourceTracker implements ObjectTrackerHandler {
    public boolean shouldTrack(Object obj) {
        return obj instanceof MyResource;
    }

    public int getMetric(Object obj) {
        MyResource res = (MyResource) obj;
        return res.isActive() ? 1 : 0;
    }
}
```

### Troubleshooting Custom Trackers

**Problem: Handler not being used**

Check:
1. Is `setHandler()` called BEFORE any tracked objects are created?
2. Is system property spelled correctly if using `-Dobject.tracker.handler`?
3. Check console for `[ObjectTrackerRegistry]` log messages

**Problem: No objects being tracked**

Check:
1. Is `shouldTrack()` returning true for your objects?
2. Are the objects actually being passed through instrumented methods?
3. Add debug logging to your `shouldTrack()` method

**Problem: Incorrect metrics**

Check:
1. Is `getMetric()` throwing exceptions? (Wrap in try-catch)
2. Is the metric actually changing? Print values to verify
3. Does metric follow the pattern: 0 = released, >0 = active?

## 🤝 Contributing

This project demonstrates a clean separation between:
1. **Reusable library** (`bytebuf-flow-tracker`) - Core tracking functionality
2. **Example usage** (`bytebuf-flow-example`) - Shows integration patterns

To contribute:
1. Library changes go in `bytebuf-flow-tracker/`
2. Example changes go in `bytebuf-flow-example/`
3. Keep the two modules independent (example depends on library, not vice versa)

**Note:** This project is not currently published to Maven Central. Users must build from source.

## 📄 License

Apache License 2.0

## 🔗 Additional Resources

- [ByteBuddy Documentation](https://bytebuddy.net/)
- [Netty ByteBuf Guide](https://netty.io/wiki/reference-counted-objects.html)
- [Java Agents Tutorial](https://www.baeldung.com/java-instrumentation)

---

## 📋 Quick Reference: Integration Checklist

Use this checklist when integrating into a new project:

- [ ] Clone or download this repository
- [ ] Run `mvn clean install` to build locally
- [ ] Add Maven dependency to your `pom.xml`
- [ ] Configure exec plugin or surefire plugin with `-javaagent` argument
- [ ] Set `include=` to match your package structure
- [ ] Run your application with `mvn exec:java` or `mvn test`
- [ ] Verify agent startup messages appear
- [ ] Add code to print tracking data or enable JMX
- [ ] Test with a simple ByteBuf operation
- [ ] Verify tracking data appears correctly
- [ ] Tune `include`/`exclude` packages as needed
- [ ] Document the integration in your project's README

---

**Need help?** Check the README files in each module for detailed documentation.

**Found a bug?** Please open an issue with a reproducible example.

**Have a question?** See the example module for common integration patterns.
