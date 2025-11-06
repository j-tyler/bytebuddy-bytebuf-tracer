# ByteBuf Flow Tracker - Example Application

This module demonstrates how to integrate the ByteBuf Flow Tracker into your own application.

## What This Example Shows

1. **How to add the tracker as a Maven dependency**
2. **How to configure the Java agent in your build**
3. **Example code patterns** that the tracker monitors:
   - Normal ByteBuf usage (properly released)
   - Error handling with ByteBufs (still properly released)
   - Memory leaks (ByteBuf not released)

## Project Structure

```
bytebuf-flow-example/
├── pom.xml                          # Shows how to depend on the tracker
└── src/main/java/com/example/demo/
    ├── DemoApplication.java         # Main application
    ├── MessageProcessor.java        # Normal ByteBuf processing
    ├── ErrorHandler.java            # Error handling with ByteBufs
    └── LeakyService.java            # Demonstrates a memory leak
```

## Building

First, build the parent project (which includes the tracker library):

```bash
cd ..
mvn clean install
```

Then build this example:

```bash
cd bytebuf-flow-example
mvn clean package
```

## Running the Example

### Option 1: Using Maven Exec Plugin

The easiest way (recommended for development):

```bash
mvn exec:java
```

This automatically runs with the agent attached as configured in the `pom.xml`.

### Option 2: Manual Java Command

```bash
java -javaagent:../bytebuf-flow-tracker/target/bytebuf-flow-tracker-1.0.0-SNAPSHOT-agent.jar=include=com.example.demo \
     -jar target/bytebuf-flow-example-1.0.0-SNAPSHOT.jar
```

### Option 3: With JMX Monitoring

```bash
java -javaagent:../bytebuf-flow-tracker/target/bytebuf-flow-tracker-1.0.0-SNAPSHOT-agent.jar=include=com.example.demo \
     -Dcom.sun.management.jmxremote \
     -Dcom.sun.management.jmxremote.port=9999 \
     -Dcom.sun.management.jmxremote.authenticate=false \
     -Dcom.sun.management.jmxremote.ssl=false \
     -jar target/bytebuf-flow-example-1.0.0-SNAPSHOT.jar
```

Then connect with JConsole:
```bash
jconsole localhost:9999
```

Navigate to the MBean: `com.example:type=ByteBufFlowTracker`

## Expected Output

When you run the example, you'll see:

1. **Summary Statistics**: Number of roots, traversals, and leak detection
2. **Flow Tree**: Hierarchical view of ByteBuf flows through methods
3. **Flat Paths**: Linear paths showing each ByteBuf journey
4. **Leak Detection**: `LeakyService.forgetsToRelease` will be highlighted as a leak

Example output:

```
=== ByteBuf Flow Summary ===
Total Root Methods: 2
Total Traversals: 9
Unique Paths: 3
Leak Paths: 1
Leak Percentage: 33.33%

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

## Integrating Into Your Project

To use this in your own project, follow these steps:

### 1. Add the Dependency

Add to your `pom.xml`:

```xml
<dependency>
    <groupId>com.example.bytebuf</groupId>
    <artifactId>bytebuf-flow-tracker</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
```

### 2. Configure the Agent

Option A - **Exec Plugin** (for development):

```xml
<plugin>
    <groupId>org.codehaus.mojo</groupId>
    <artifactId>exec-maven-plugin</artifactId>
    <version>3.1.0</version>
    <configuration>
        <mainClass>com.yourcompany.YourMainClass</mainClass>
        <commandlineArgs>
            -javaagent:${settings.localRepository}/com/example/bytebuf/bytebuf-flow-tracker/${bytebuf.tracker.version}/bytebuf-flow-tracker-${bytebuf.tracker.version}-agent.jar=include=com.yourcompany
        </commandlineArgs>
    </configuration>
</plugin>
```

Option B - **Surefire Plugin** (for testing):

```xml
<plugin>
    <groupId>org.apache.maven.plugins</groupId>
    <artifactId>maven-surefire-plugin</artifactId>
    <configuration>
        <argLine>
            -javaagent:path/to/bytebuf-flow-tracker-agent.jar=include=com.yourcompany
        </argLine>
    </configuration>
</plugin>
```

Option C - **Production** (command line or scripts):

```bash
java -javaagent:/path/to/bytebuf-flow-tracker-agent.jar=include=com.yourcompany \
     -jar your-application.jar
```

### 3. Configure Package Filtering

The agent argument format is: `include=package1,package2;exclude=package3`

Examples:
- `include=com.yourcompany` - Track all ByteBufs in your company's packages
- `include=com.yourcompany,org.yourorg` - Track multiple package trees
- `include=com.yourcompany;exclude=com.yourcompany.legacy` - Exclude legacy code

### 4. Access Results

Programmatically in your code:

```java
import com.example.bytebuf.tracker.ByteBufFlowTracker;
import com.example.bytebuf.tracker.view.TrieRenderer;

ByteBufFlowTracker tracker = ByteBufFlowTracker.getInstance();
TrieRenderer renderer = new TrieRenderer(tracker.getTrie());

System.out.println(renderer.renderIndentedTree());
System.out.println(renderer.renderSummary());
```

Or via JMX at runtime using JConsole or your monitoring tools.

## Customizing for Other Objects

While designed for ByteBuf, you can track any object:

1. Fork the `bytebuf-flow-tracker` module
2. Modify `ByteBufTrackingAdvice.java` to detect your target objects
3. Adjust the tracking metric (refCount or equivalent)
4. The Trie structure and analysis work the same!

## Performance Considerations

The tracker adds minimal overhead:
- ~5-10% in high-throughput scenarios
- No allocations (no stack traces collected)
- Lock-free concurrent data structures
- Can be disabled in production by not loading the agent

## Troubleshooting

**No data appearing:**
- Verify the `include` packages match your code
- Check that ByteBufs are actually being used
- Look for agent startup messages in console output

**Too much data:**
- Narrow the `include` package list
- Add `exclude` patterns for noisy components
- Consider sampling in the advice class

**JMX not working:**
- Ensure JMX properties are set
- Check firewall/port settings
- Try `jconsole` locally first

## License

Apache License 2.0
