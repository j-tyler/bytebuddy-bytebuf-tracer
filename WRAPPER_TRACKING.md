# Tracking ByteBuf in Custom Wrapper Objects

## The Problem

When ByteBuf is wrapped in custom objects (Message, Request, Event, etc.), flow tracking breaks because:

1. **Constructors not tracked by default** - Constructor calls are invisible
2. **Wrapper objects not recognized** - Only `obj instanceof ByteBuf` is tracked

**Example of broken flow:**
```java
ByteBuf buffer = allocate();           // ✓ Tracked
Message msg = new Message(buffer);     // ✗ Constructor not tracked
processMessage(msg);                   // ✗ Message is not ByteBuf
```

**Result:** Flow tree shows gaps where ByteBuf is wrapped.

## Why This Matters

Most real applications wrap ByteBuf in domain objects:
- Message/Request/Response wrappers
- Protocol frames (DataFrame, ControlFrame)
- Event objects (MessageReceivedEvent)
- DTO classes holding ByteBuf

Without proper tracking, you lose visibility into the complete ByteBuf lifecycle, making leak detection impossible.

## The Solution: Constructor Tracking

Enable constructor instrumentation for specific wrapper classes:

```bash
-javaagent:tracker.jar=include=com.example;trackConstructors=com.example.Message
```

This makes constructor calls visible in the flow tree, maintaining continuous flow.

## Configuration

### Enable for Specific Classes

```bash
trackConstructors=com.example.Message,com.example.Request,com.example.Response
```

### Use Wildcard Patterns

```bash
# Track all DTOs
trackConstructors=com.example.dto.*

# Track all protocol frames
trackConstructors=com.example.protocol.*

# Track multiple packages
trackConstructors=com.example.dto.*,com.example.events.*
```

### Inner Classes

```bash
# Specific inner class
trackConstructors=com.example.Outer$Inner

# All inner classes
trackConstructors=com.example.Outer$*
```

### Complete Example

```bash
java -javaagent:/path/to/tracker-agent.jar=\
include=com.yourcompany;\
trackConstructors=com.yourcompany.Message,com.yourcompany.dto.* \
-jar your-app.jar
```

## What Gets Tracked

### Class Visibility

**Important:** Class visibility does NOT matter - private inner classes CAN be instrumented:

```java
// ✓ Private inner class CAN be matched and instrumented
private class InternalWrapper {
    private final ByteBuf data;

    // ✓ Public constructor tracked (with trackConstructors)
    public InternalWrapper(ByteBuf data) { this.data = data; }

    // ✗ Private constructor NOT tracked
    private InternalWrapper() { }
}
```

### With Constructor Tracking Enabled

Only **public and protected** constructors are tracked:

```java
public class Message {
    private final ByteBuf data;

    // ✓ Automatically tracked with trackConstructors=com.example.Message
    public Message(ByteBuf data) {
        this.data = data;
    }

    // ✓ Protected constructors also tracked
    protected Message(ByteBuf data, String id) {
        this.data = data;
        this.id = id;
    }

    // ✗ Private constructors excluded (even if class is tracked)
    private Message() { }
}
```

### Methods Still Need Manual Tracking

Methods that receive wrapper objects (not ByteBuf) must be manually tracked:

```java
// ✓ Constructor tracked automatically with trackConstructors
public Message(ByteBuf data) {
    this.data = data;
}

// ✗ Method receives Message, not ByteBuf - needs manual tracking
public void processMessage(Message msg) {
    ByteBuf buf = msg.getData();
    // Manual tracking required
    ByteBufFlowTracker.getInstance().recordMethodCall(
        buf, "Handler", "processMessage", buf.refCnt());
}
```

## Complete Example

### 1. Define Your Wrapper Class

```java
public class Message {
    private final ByteBuf data;
    private final String messageId;

    public Message(ByteBuf data) {
        this.data = data;
        this.messageId = "MSG-" + System.currentTimeMillis();
        // No manual tracking needed - agent handles it!
    }

    public ByteBuf getData() {
        return data;
    }
}
```

### 2. Configure the Agent

```bash
-javaagent:tracker.jar=include=com.example;trackConstructors=com.example.Message
```

### 3. Add Manual Tracking for Wrapper Methods

```java
public void processMessage(Message message) {
    // Extract ByteBuf and track manually
    ByteBuf buffer = message.getData();
    ByteBufFlowTracker.getInstance().recordMethodCall(
        buffer, "MessageHandler", "processMessage", buffer.refCnt());

    // ... process message ...
}
```

### 4. Verify Continuous Flow

```
ROOT: Client.allocate [count=1]
└── Client.prepareBuffer [ref=1, count=1]
    └── Message.<init> [ref=1, count=1]           ← Constructor tracked!
        └── MessageHandler.processMessage [ref=1, count=1]  ← Manual tracking
            └── MessageHandler.validate [ref=1, count=1]    ← Manual tracking
                └── Client.cleanup [ref=0, count=1]
```

**No gaps!** Complete visibility from allocation to release.

## Common Patterns

### Message Wrappers

```java
public class Message {
    private final ByteBuf payload;
    public Message(ByteBuf payload) { this.payload = payload; }
}
```

**Config:** `trackConstructors=com.example.Message`

### Request/Response Objects

```java
public class HttpRequest {
    private final ByteBuf body;
    public HttpRequest(String method, String path, ByteBuf body) { ... }
}

public class HttpResponse {
    private final ByteBuf content;
    public HttpResponse(int status, ByteBuf content) { ... }
}
```

**Config:** `trackConstructors=com.example.HttpRequest,com.example.HttpResponse`

### Protocol Frames

```java
package com.example.protocol;

public class DataFrame {
    private final ByteBuf data;
    public DataFrame(int streamId, ByteBuf data) { ... }
}

public class HeaderFrame {
    private final ByteBuf headers;
    public HeaderFrame(int streamId, ByteBuf headers) { ... }
}
```

**Config:** `trackConstructors=com.example.protocol.*`

### Event Objects

```java
public class MessageReceivedEvent {
    private final ByteBuf message;
    public MessageReceivedEvent(Channel channel, ByteBuf message) { ... }
}
```

**Config:** `trackConstructors=com.example.events.*Event`

## Quick Reference

### What's Tracked Automatically

✓ Methods with ByteBuf parameters
✓ Methods returning ByteBuf
✓ Static methods (with ByteBuf)
✓ Constructors (when configured with `trackConstructors`)

### What Needs Manual Tracking

✗ Methods receiving wrapper objects (Message, Request, etc.)
✗ Methods accessing ByteBuf through fields
✗ Private constructors (always excluded)
✗ Private methods (excluded by default)

### Flow Comparison

**Without constructor tracking:**
```
allocate → prepare
  ⚠️ GAP
→ cleanup
```

**With constructor tracking:**
```
allocate → prepare → Message.<init> → process → cleanup
```

## Troubleshooting

### Constructor Not Appearing in Flow

**Check class name is exact:**
```bash
# ✓ Correct
trackConstructors=com.example.Message

# ✗ Wrong - missing package
trackConstructors=Message
```

**Check for inner classes:**
```bash
# Inner class needs $
trackConstructors=com.example.Outer$Inner
```

**Check constructor visibility:**
- ✓ Public and protected constructors tracked
- ✗ Private constructors excluded

**Verify agent logs:**
```
[ByteBufFlowAgent] Constructor tracking enabled for: [com.example.Message]
```

### Flow Still Has Gaps

Constructor tracking only tracks constructors. Methods receiving wrapper objects need manual tracking:

```java
// This still needs manual tracking
public void process(Message msg) {
    ByteBuf buf = msg.getData();
    tracker.recordMethodCall(buf, "Handler", "process", buf.refCnt());
}
```

### Performance Concerns

Be specific to minimize overhead:

```bash
# ✓ Good - specific classes
trackConstructors=com.example.Message,com.example.Request

# ⚠️ Careful - broader pattern
trackConstructors=com.example.dto.*

# ✗ Avoid - too broad
trackConstructors=com.example.*
```

## Best Practices

1. **Enable constructor tracking for your wrapper classes** - Maintains flow visibility
2. **Add manual tracking to methods receiving wrappers** - Complete the flow
3. **Use specific class names when possible** - Minimize overhead
4. **Use wildcards for related classes** - DTOs, protocol frames, events
5. **Verify in flow tree** - Check for `<init>` entries

## Example Code

See: `bytebuf-flow-example/src/main/java/com/example/demo/ConstructorTrackingExample.java`

## Tests

Run: `mvn test -Dtest=ByteBufFlowTrackerTest#testContinuousFlowWithConstructorTracking`
