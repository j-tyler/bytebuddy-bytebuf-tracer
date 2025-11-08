# ByteBuf Flow Tracker - Usage Examples

This directory contains realistic examples of how to integrate the ByteBuf Flow Tracker into your own project.

## Overview

This example demonstrates the **recommended** way to use the tracker in real projects:

1. **Build the agent from source** using Gradle composite builds
2. **Configure tracking via build parameters** (no code changes needed)
3. **Run different tracking scenarios** via Gradle tasks

## Quick Start

### Using Gradle (Recommended)

```bash
# List all available examples
./gradlew listExamples

# Run the basic ByteBuf tracking example
./gradlew runBasicExample

# Run custom object tracking (programmatic)
./gradlew runCustomObjectExample

# Run custom object tracking (Gradle configuration)
./gradlew runCustomObjectViaGradle

# Run with advanced configuration (exclusions, JMX)
./gradlew runAdvancedExample

# Run wrapped object example (constructor tracking)
./gradlew runWrappedObjectExample
```

### Using Maven (Alternative)

```bash
# Run basic example
mvn exec:java

# Run custom object example
mvn exec:java -Dexec.mainClass="com.example.demo.custom.CustomObjectExample"
```

## Examples Explained

### 1. Basic ByteBuf Tracking (`runBasicExample`)

**What it shows:**
- Basic ByteBuf flow tracking
- Proper cleanup vs memory leaks
- How to read the flow tree

**Configuration:**
```gradle
jvmArgs = ["-javaagent:${agentJar}=include=com.example.demo"]
```

**Key Code:** `DemoApplication.java`

### 2. Custom Object Tracking - Programmatic (`runCustomObjectExample`)

**What it shows:**
- Tracking custom objects (FileHandle)
- Programmatic handler registration
- Leak detection for file handles

**Configuration:**
```gradle
jvmArgs = ["-javaagent:${agentJar}=include=com.example.demo"]
```

**Key Code:**
```java
// In CustomObjectExample.java
ObjectTrackerRegistry.setHandler(new FileHandleTracker());
```

**Handler:** `FileHandleTracker.java`

### 3. Custom Object Tracking - Gradle Config (`runCustomObjectViaGradle`)

**What it shows:**
- **Zero-code-change approach** to custom object tracking
- Configuration entirely via Gradle/system properties
- Tracking database connections

**Configuration:**
```gradle
jvmArgs = [
    "-javaagent:${agentJar}=include=com.example.demo",
    "-Dobject.tracker.handler=com.example.demo.custom.DatabaseConnectionTracker"
]
```

**Why this is important:**
- No code changes needed
- Can be toggled on/off per environment
- Works with legacy code that can't be modified

**Key Code:** `CustomObjectViaGradleExample.java` (no handler registration needed!)

**Handler:** `DatabaseConnectionTracker.java`

### 4. Advanced Configuration (`runAdvancedExample`)

**What it shows:**
- Package exclusions
- Constructor tracking
- JMX integration for runtime monitoring

**Configuration:**
```gradle
def advancedAgentArgs = "include=com.example.demo" +
    ";exclude=com.example.demo.internal" +
    ";trackConstructors=com.example.demo.Message,com.example.demo.Request"

jvmArgs = [
    "-javaagent:${agentJar}=${advancedAgentArgs}",
    "-Dcom.sun.management.jmxremote",
    "-Dcom.sun.management.jmxremote.port=9999",
    // ... other JMX settings
]
```

**JMX Access:**
```bash
jconsole localhost:9999
# Navigate to: MBeans → com.example → ByteBufFlowTracker
```

### 5. Wrapped Object Example (`runWrappedObjectExample`)

**What it shows:**
- Tracking ByteBuf when wrapped in custom classes (Message, Request, etc.)
- Constructor tracking to maintain flow continuity

**Configuration:**
```gradle
def wrappedAgentArgs = "include=com.example.demo" +
    ";trackConstructors=com.example.demo.Message"

jvmArgs = ["-javaagent:${agentJar}=${wrappedAgentArgs}"]
```

**Key Code:** `WrappedObjectFlowExample.java`

## Build Configuration Explained

### Composite Build Approach

This example uses Gradle **composite builds** to build the tracker from source:

**settings.gradle:**
```gradle
includeBuild('../bytebuf-flow-tracker')
```

**build.gradle:**
```gradle
dependencies {
    implementation 'com.example.bytebuf:bytebuf-flow-tracker:1.0.0-SNAPSHOT'
}
```

**Benefits:**
1. **Always builds from source** - No need to publish to Maven Central
2. **Automatic dependency resolution** - Gradle handles everything
3. **Fast iteration** - Changes to tracker automatically picked up
4. **Realistic integration** - Shows how users would actually integrate this

### Agent Configuration

The agent is configured via JVM arguments:

```gradle
def getAgentJar() {
    return file("../bytebuf-flow-tracker/build/libs/bytebuf-flow-tracker-${version}-agent.jar")
}

jvmArgs = ["-javaagent:${getAgentJar()}=include=com.example.demo"]
```

**Agent Arguments Format:**
```
-javaagent:/path/to/agent.jar=include=pkg1,pkg2;exclude=pkg3;trackConstructors=Class1,Class2
```

## Custom Object Tracking - Two Approaches

### Approach 1: Programmatic (Code Change Required)

```java
public static void main(String[] args) {
    // Must be called BEFORE any tracked objects are created
    ObjectTrackerRegistry.setHandler(new FileHandleTracker());

    // ... rest of your application
}
```

**Pros:**
- Full control in code
- Can use complex logic to decide which handler to use

**Cons:**
- Requires code changes
- Handler must be set before objects are created

### Approach 2: System Property (No Code Changes)

**build.gradle:**
```gradle
jvmArgs = [
    "-javaagent:${agentJar}=include=com.example",
    "-Dobject.tracker.handler=com.example.custom.MyTracker"
]
```

**Pros:**
- **Zero code changes**
- Can toggle tracking on/off per environment
- Works with legacy code

**Cons:**
- Only one handler can be registered this way
- Handler class must have a no-arg constructor

## Example Handler Implementation

```java
public class FileHandleTracker implements ObjectTrackerHandler {

    @Override
    public boolean shouldTrack(Object obj) {
        return obj instanceof RandomAccessFile;
    }

    @Override
    public int getMetric(Object obj) {
        RandomAccessFile file = (RandomAccessFile) obj;
        try {
            file.getFD();  // Throws if closed
            return 1;      // Open
        } catch (Exception e) {
            return 0;      // Closed
        }
    }

    @Override
    public String getObjectType() {
        return "FileHandle";
    }
}
```

**Metric Guidelines:**
- **0 = Released/Closed/Done** - Object is properly cleaned up
- **Non-zero = Active/Open/Retained** - Object still in use
- Leaf nodes with non-zero metrics indicate leaks

## Integration into Your Project

### Option 1: Composite Build (Recommended for Gradle users)

1. Clone the tracker as a sibling directory to your project:
   ```bash
   cd your-project/
   git clone https://github.com/j-tyler/bytebuddy-bytebuf-tracer.git ../bytebuddy-bytebuf-tracer
   ```

2. Update your `settings.gradle`:
   ```gradle
   includeBuild('../bytebuddy-bytebuf-tracer/bytebuf-flow-tracker')
   ```

3. Add dependency in your `build.gradle`:
   ```gradle
   dependencies {
       implementation 'com.example.bytebuf:bytebuf-flow-tracker:1.0.0-SNAPSHOT'
   }
   ```

4. Configure agent in your run task:
   ```gradle
   task runWithAgent(type: JavaExec) {
       mainClass = 'com.yourcompany.Main'
       classpath = sourceSets.main.runtimeClasspath

       def agentJar = file("../bytebuddy-bytebuf-tracer/bytebuf-flow-tracker/build/libs/bytebuf-flow-tracker-1.0.0-SNAPSHOT-agent.jar")

       jvmArgs = [
           "-javaagent:${agentJar}=include=com.yourcompany",
           "-Dobject.tracker.handler=com.yourcompany.custom.YourTracker"  // Optional
       ]
   }
   ```

### Option 2: Local Maven Repository

```bash
# Build and install to local Maven repository
cd bytebuddy-bytebuf-tracer
mvn clean install
```

Then in your `build.gradle`:
```gradle
repositories {
    mavenLocal()
    mavenCentral()
}

dependencies {
    implementation 'com.example.bytebuf:bytebuf-flow-tracker:1.0.0-SNAPSHOT'
}
```

## Troubleshooting

### Build fails with "Could not resolve dependency"

**Cause:** Dependencies not in Gradle cache and no network access

**Solution:**
- Ensure network access for first build
- Or use `repositories { mavenLocal() }` if dependencies are in `~/.m2/repository`

### Agent JAR not found

**Cause:** Tracker module not built yet

**Solution:**
```bash
# Build the tracker first
cd ../bytebuf-flow-tracker
gradle build

# Then build the example
cd ../bytebuf-flow-example
gradle build
```

### No tracking data appears

**Cause:** Include packages don't match your code

**Solution:** Verify `include` argument matches your package structure:
```gradle
// Wrong
jvmArgs = ["-javaagent:${agentJar}=include=com.example"]

// Right (if your code is in com.yourcompany)
jvmArgs = ["-javaagent:${agentJar}=include=com.yourcompany"]
```

### Custom handler not working (Gradle approach)

**Cause:** Handler class not found or has no no-arg constructor

**Solution:**
```java
// Ensure handler has public no-arg constructor
public class MyTracker implements ObjectTrackerHandler {
    public MyTracker() {  // Must have this!
    }
    // ... implementation
}
```

## Further Reading

- **Main README:** `../README.md` - Full documentation
- **Architecture:** `../ARCHITECTURE.md` - Technical details
- **Source Code:** `../bytebuf-flow-tracker/src/` - Implementation

## License

Apache License 2.0
