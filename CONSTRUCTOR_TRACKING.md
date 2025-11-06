# Constructor Tracking Configuration

## Overview

Constructor tracking enables continuous ByteBuf flow visibility when ByteBuf instances are wrapped in custom objects. This feature allows you to **selectively enable** constructor instrumentation only for specific wrapper classes, avoiding the performance overhead of tracking all constructors.

## The Problem

By default, constructors are excluded from instrumentation:

```java
.and(not(isConstructor()))  // Constructors NOT tracked
```

This creates gaps in flow tracking when ByteBuf is wrapped:

```java
ByteBuf buffer = allocate();        // ✓ Tracked
Message msg = new Message(buffer);   // ✗ NOT tracked - constructor excluded
processMessage(msg);                 // ✗ NOT tracked - Message is not ByteBuf
```

**Result:** Flow appears broken with gaps where ByteBuf is wrapped in custom objects.

## The Solution

Enable constructor tracking **only for specific wrapper classes**:

```bash
-javaagent:tracker.jar=include=com.example;trackConstructors=com.example.Message,com.example.Request
```

Now constructors of `Message` and `Request` are instrumented:

```java
ByteBuf buffer = allocate();        // ✓ Tracked
Message msg = new Message(buffer);   // ✓ Tracked - constructor instrumented!
processMessage(msg);                 // Still need manual tracking for wrapper methods
```

## Configuration

### Agent Argument Format

```
-javaagent:tracker.jar=include=PACKAGES;trackConstructors=CLASSES
```

**Parameters:**
- `include` - Packages to instrument (existing parameter)
- `trackConstructors` - Comma-separated list of class names for constructor tracking

### Configuration Examples

#### 1. Single Class

Track constructors for one specific class:

```bash
trackConstructors=com.example.demo.Message
```

#### 2. Multiple Classes

Track constructors for multiple classes:

```bash
trackConstructors=com.example.Message,com.example.HttpRequest,com.example.Command
```

#### 3. Wildcard Pattern

Track constructors for all classes in a package:

```bash
trackConstructors=com.example.dto.*
```

This matches all classes starting with `com.example.dto.`

#### 4. Inner Classes

Track inner class constructors:

```bash
trackConstructors=com.example.ConstructorTrackingExample$TrackedMessage
```

Track all inner classes with wildcard:

```bash
trackConstructors=com.example.ConstructorTrackingExample$*
```

### Complete Example

```bash
java -javaagent:/path/to/bytebuf-flow-tracker-agent.jar=\
include=com.example,com.myapp;\
exclude=com.example.legacy;\
trackConstructors=com.example.Message,com.example.Request,com.example.dto.* \
-jar your-application.jar
```

## How It Works

### Architecture

1. **AgentConfig.parse()** - Parses `trackConstructors` parameter
2. **getConstructorTrackingMatcher()** - Creates ByteBuddy matcher for specified classes
3. **ConstructorTrackingTransformer** - Separate transformer for constructors
4. **ByteBufTrackingAdvice** - Same advice used for both methods and constructors

### Code Flow

```java
// 1. Agent parses configuration
trackConstructors=com.example.Message

// 2. Creates matcher
.or(named("com.example.Message"))

// 3. Instruments constructors for matched classes
.constructor(isPublic().or(isProtected()))
.intercept(Advice.to(ByteBufTrackingAdvice.class))

// 4. Constructor calls tracked like regular methods
Message.<init> [ref=1, count=1]
```

### What Gets Tracked

With `trackConstructors=com.example.Message`:

```java
public class Message {
    private ByteBuf data;

    // ✓ TRACKED - public constructor with ByteBuf parameter
    public Message(ByteBuf data) {
        this.data = data;
    }

    // ✓ TRACKED - protected constructor
    protected Message(ByteBuf data, String id) {
        this.data = data;
        this.id = id;
    }

    // ✗ NOT tracked - private constructor (excluded)
    private Message() { }
}
```

## Flow Visualization

### Without Constructor Tracking (Broken Flow)

```
ROOT: allocate [count=1]
└── prepareBuffer [ref=1, count=1]

    ⚠️ GAP - Message constructor not visible
    ⚠️ GAP - processMessage not visible (receives Message, not ByteBuf)

    └── extractAndRelease [ref=1, count=1]
```

### With Constructor Tracking (Continuous Flow)

```
ROOT: allocate [count=1]
└── prepareBuffer [ref=1, count=1]
    └── Message.<init> [ref=1, count=1]           ← Constructor tracked!
        └── processMessage [ref=1, count=1]        ← Manual tracking
            └── validateMessage [ref=1, count=1]   ← Manual tracking
                └── cleanup [ref=0, count=1]
```

**Note:** Methods receiving wrapper objects still need manual tracking (see below).

## Combining with Manual Tracking

Constructor tracking solves the constructor visibility problem, but methods receiving wrapper objects still need manual tracking:

```java
public class MessageHandler {

    // Constructor tracked automatically with trackConstructors
    public TrackedMessage(ByteBuf buffer) {
        this.buffer = buffer;
        // No manual tracking needed - agent handles it!
    }

    // Method needs manual tracking (receives wrapper object)
    public void processMessage(TrackedMessage message) {
        ByteBuf buffer = message.getBuffer();
        // Manual tracking required
        ByteBufFlowTracker.getInstance().recordMethodCall(
            buffer, "MessageHandler", "processMessage", buffer.refCnt());
    }
}
```

## Performance Considerations

### Selective Tracking Benefits

Only specified constructors are instrumented:

```bash
# Only Message and Request constructors instrumented
trackConstructors=com.example.Message,com.example.Request

# NOT these (no overhead):
# - java.lang.String constructors
# - java.util.ArrayList constructors
# - All other constructors
```

### Performance Impact

- **Minimal overhead** when tracking specific classes
- **No overhead** for constructors not in trackConstructors list
- Same instrumentation cost as regular method tracking
- ByteBuddy applies advice only to matched constructors

### Best Practices

1. **Be specific** - Only track wrapper classes that hold ByteBuf
2. **Avoid wildcards** unless truly needed
3. **Start narrow** - Add classes as needed
4. **Monitor impact** - Use JMX to check tracking overhead

```bash
# Good - specific classes
trackConstructors=com.example.Message,com.example.Request

# Be careful - all DTOs
trackConstructors=com.example.dto.*

# Avoid - too broad
trackConstructors=com.example.*
```

## Common Patterns

### Pattern 1: Message Wrappers

```java
public class Message {
    private final ByteBuf payload;
    public Message(ByteBuf payload) { this.payload = payload; }
}
```

**Config:** `trackConstructors=com.example.Message`

### Pattern 2: Request/Response Objects

```java
public class HttpRequest {
    private final ByteBuf body;
    public HttpRequest(String method, String path, ByteBuf body) { ... }
}
```

**Config:** `trackConstructors=com.example.HttpRequest,com.example.HttpResponse`

### Pattern 3: Protocol Frames

```java
public class DataFrame {
    private final ByteBuf data;
    public DataFrame(int streamId, ByteBuf data) { ... }
}
```

**Config:** `trackConstructors=com.example.protocol.*`

### Pattern 4: Event Objects

```java
public class MessageReceivedEvent {
    private final ByteBuf message;
    public MessageReceivedEvent(Channel channel, ByteBuf message) { ... }
}
```

**Config:** `trackConstructors=com.example.events.*Event`

## Testing

### Unit Test

See `ByteBufFlowTrackerTest.testContinuousFlowWithConstructorTracking()`:

```java
@Test
public void testContinuousFlowWithConstructorTracking() {
    // Simulates agent behavior with constructor tracking enabled
    ByteBuf buffer = Unpooled.buffer(256);

    // Constructor tracking simulated via manual tracking
    TrackedMessage message = new TrackedMessage(buffer);

    // Verify continuous flow
    assertTrue("Single continuous path", summary.contains("Total Root Methods: 1"));
}
```

Run with:
```bash
mvn test -Dtest=ByteBufFlowTrackerTest#testContinuousFlowWithConstructorTracking
```

### Example Application

See `ConstructorTrackingExample.java`:

```bash
cd bytebuf-flow-example
mvn exec:java -Dexec.mainClass="com.example.demo.ConstructorTrackingExample"
```

For full agent test with constructor tracking:
```bash
java -javaagent:../bytebuf-flow-tracker/target/bytebuf-flow-tracker-agent.jar=\
include=com.example.demo;\
trackConstructors=com.example.demo.ConstructorTrackingExample$TrackedMessage \
-cp target/classes:... com.example.demo.ConstructorTrackingExample
```

## Troubleshooting

### Constructor Not Tracked

**Symptoms:** Constructor doesn't appear in flow tree

**Solutions:**

1. **Check class name is exact:**
   ```bash
   # Correct
   trackConstructors=com.example.Message

   # Wrong (missing package)
   trackConstructors=Message
   ```

2. **Check for inner classes:**
   ```bash
   # Inner class needs $
   trackConstructors=com.example.Outer$Inner
   ```

3. **Check constructor visibility:**
   ```java
   public Message(ByteBuf buf) { }   // ✓ Tracked
   protected Message(ByteBuf buf) { } // ✓ Tracked
   private Message(ByteBuf buf) { }   // ✗ NOT tracked
   ```

4. **Check agent logs:**
   ```
   [ByteBufFlowAgent] Constructor tracking enabled for: [com.example.Message]
   ```

### Flow Still Broken

If constructors are tracked but flow still broken:

1. **Methods receiving wrappers need manual tracking:**
   ```java
   public void process(Message msg) {
       // Need to manually track the ByteBuf inside
       ByteBuf buf = msg.getBuffer();
       tracker.recordMethodCall(buf, ...);
   }
   ```

2. **Check ByteBuf is actually in constructor parameters:**
   ```java
   // ✓ ByteBuf parameter - tracked
   public Message(ByteBuf data) { }

   // ✗ No ByteBuf parameter - won't be tracked
   public Message(String data) { this.data = parse(data); }
   ```

### Performance Issues

If tracking too many constructors:

1. **Review trackConstructors list:**
   ```bash
   # Too broad
   trackConstructors=com.example.*

   # Better - specific classes
   trackConstructors=com.example.Message,com.example.Request
   ```

2. **Use JMX to monitor:**
   ```bash
   jconsole localhost:9999
   # Check: com.example → ByteBufFlowTracker → getSummary()
   ```

## Migration Guide

### From No Constructor Tracking

**Before:**
```java
// Manual tracking in every constructor
public Message(ByteBuf data) {
    this.data = data;
    ByteBufFlowTracker.getInstance().recordMethodCall(
        data, "Message", "<init>", data.refCnt());
}
```

**After:**
```java
// No manual tracking needed!
public Message(ByteBuf data) {
    this.data = data;
    // Agent handles tracking automatically
}
```

**Agent config:**
```bash
trackConstructors=com.example.Message
```

### Gradual Migration

1. **Start with critical wrappers:**
   ```bash
   trackConstructors=com.example.Message
   ```

2. **Add more as needed:**
   ```bash
   trackConstructors=com.example.Message,com.example.Request
   ```

3. **Use patterns for related classes:**
   ```bash
   trackConstructors=com.example.Message,com.example.protocol.*
   ```

4. **Remove manual tracking from constructors** (but keep for wrapper methods)

## Summary

**Constructor tracking solves:**
- ✓ Constructor visibility in flow tracking
- ✓ Gaps when ByteBuf wrapped in constructors
- ✓ Continuous flow from allocation to release

**Constructor tracking does NOT solve:**
- ✗ Methods receiving wrapper objects (still need manual tracking)
- ✗ Private constructors (excluded by design)
- ✗ Wrapper detection without ByteBuf parameters

**Configuration:**
```bash
-javaagent:tracker.jar=trackConstructors=com.example.YourWrapperClass
```

**Best used with:**
- Specific wrapper classes (Message, Request, Event, etc.)
- Manual tracking for methods receiving wrappers
- Patterns for related classes (com.example.dto.*)

**Result:** Complete ByteBuf flow visibility with minimal overhead!
