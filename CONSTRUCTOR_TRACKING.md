# Constructor Tracking

## Overview

Enable constructor instrumentation for specific wrapper classes to maintain continuous ByteBuf flow visibility.

**By default, constructors are NOT tracked.** This creates gaps when ByteBuf is wrapped in custom objects.

## Configuration

### Enable for Specific Classes

```bash
-javaagent:tracker.jar=include=com.example;trackConstructors=com.example.Message,com.example.Request
```

### Wildcard Patterns

```bash
# Track all classes in package
trackConstructors=com.example.dto.*

# Track all inner classes
trackConstructors=com.example.Outer$*
```

### Complete Example

```bash
java -javaagent:/path/to/tracker-agent.jar=\
include=com.yourcompany;\
trackConstructors=com.yourcompany.Message,com.yourcompany.dto.* \
-jar your-app.jar
```

## When to Use

Enable constructor tracking for classes that wrap ByteBuf:

```java
public class Message {
    private final ByteBuf data;

    // With trackConstructors=com.example.Message
    // this constructor is automatically tracked
    public Message(ByteBuf data) {
        this.data = data;
    }
}
```

**Common patterns:**
- Message/Request/Response wrappers
- Protocol frame objects (DataFrame, ControlFrame)
- Event objects (MessageReceivedEvent)
- DTO classes holding ByteBuf

## Flow Comparison

**Without constructor tracking:**
```
allocate → prepareBuffer
  ⚠️ GAP (constructor invisible)
→ cleanup
```

**With constructor tracking:**
```
allocate → prepareBuffer → Message.<init> → processMessage → cleanup
```

## Important Notes

1. **Constructor tracking only affects constructor calls** - methods receiving wrapper objects still need manual tracking:

```java
// Constructor - tracked automatically with trackConstructors
public Message(ByteBuf data) {
    this.data = data;
}

// Method receiving wrapper - needs manual tracking
public void process(Message msg) {
    ByteBuf buf = msg.getData();
    tracker.recordMethodCall(buf, "Handler", "process", buf.refCnt());
}
```

2. **Only public/protected constructors are tracked** - private constructors excluded

3. **Minimal overhead** - only specified classes are instrumented

## Common Configurations

```bash
# Single wrapper class
trackConstructors=com.example.Message

# Multiple wrappers
trackConstructors=com.example.Message,com.example.Request,com.example.Response

# All DTOs
trackConstructors=com.example.dto.*

# Multiple packages
trackConstructors=com.example.dto.*,com.example.protocol.*
```

## Troubleshooting

**Constructor not appearing in flow:**
- Check class name is exact: `com.example.Message` (not just `Message`)
- Inner classes need `$`: `com.example.Outer$Inner`
- Check constructor is public or protected
- Verify agent logs: `[ByteBufFlowAgent] Constructor tracking enabled for: [...]`

**Performance issues:**
- Avoid overly broad patterns: `trackConstructors=com.*` (too broad)
- Be specific: `trackConstructors=com.example.Message` (better)

## Example Code

See: `bytebuf-flow-example/src/main/java/com/example/demo/ConstructorTrackingExample.java`

## Test

Run: `mvn test -Dtest=ByteBufFlowTrackerTest#testContinuousFlowWithConstructorTracking`
