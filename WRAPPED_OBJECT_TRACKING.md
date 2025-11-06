# Wrapped Object Tracking

## The Limitation

When ByteBuf is wrapped in a custom object, **flow tracking breaks** because:

1. **Constructors not tracked by default** - Constructor calls are invisible
2. **Wrapper objects not recognized** - Methods receiving wrappers aren't automatically tracked

The tracker only recognizes `obj instanceof ByteBuf`.

## Example of Broken Flow

```java
ByteBuf buffer = allocate();           // ✓ Tracked
Message msg = new Message(buffer);     // ✗ Constructor not tracked
processMessage(msg);                   // ✗ Message is not ByteBuf, not tracked
```

**Result:** Flow appears broken with gaps.

## Solutions

### Solution 1: Enable Constructor Tracking (Recommended)

Enable constructor tracking for your wrapper classes:

```bash
-javaagent:tracker.jar=trackConstructors=com.example.Message
```

This tracks the constructor, reducing gaps:

```java
ByteBuf buffer = allocate();           // ✓ Tracked
Message msg = new Message(buffer);     // ✓ Tracked with trackConstructors
processMessage(msg);                   // ✗ Still needs manual tracking
```

**See:** [CONSTRUCTOR_TRACKING.md](CONSTRUCTOR_TRACKING.md)

### Solution 2: Manual Tracking

For methods that receive wrapper objects, manually track the contained ByteBuf:

```java
public void processMessage(Message message) {
    ByteBuf buffer = message.getBuffer();
    ByteBufFlowTracker.getInstance().recordMethodCall(
        buffer, "Handler", "processMessage", buffer.refCnt());
    // ... process ...
}
```

### Solution 3: Combine Both (Best)

Enable constructor tracking + manual tracking for wrapper methods = continuous flow:

```java
// Constructor - automatically tracked with trackConstructors
public Message(ByteBuf data) {
    this.data = data;
}

// Method receiving wrapper - manual tracking
public void processMessage(Message msg) {
    ByteBuf buf = msg.getBuffer();
    tracker.recordMethodCall(buf, "Handler", "processMessage", buf.refCnt());
}
```

**Result:** Complete continuous flow from allocation to release.

## What Gets Tracked Automatically

✓ Methods with ByteBuf parameters
✓ Methods returning ByteBuf
✓ Static methods (with ByteBuf parameters/returns)
✓ Constructors (when configured with `trackConstructors`)

## What Needs Manual Tracking

✗ Methods receiving wrapper objects (Message, Request, etc.)
✗ Methods that access ByteBuf through fields
✗ Private methods (excluded by default)

## Flow Comparison

**Without any fixes:**
```
allocate → wrapMethod
  ⚠️ GAP (constructor + wrapper methods invisible)
→ cleanup
```

**With constructor tracking only:**
```
allocate → wrapMethod → Message.<init>
  ⚠️ GAP (wrapper methods still invisible)
→ cleanup
```

**With constructor tracking + manual tracking:**
```
allocate → wrapMethod → Message.<init> → processMessage → validateMessage → cleanup
```

## Common Wrapper Patterns

```java
// Message wrapper
public class Message {
    private final ByteBuf data;
    public Message(ByteBuf data) { this.data = data; }
}

// HTTP Request wrapper
public class HttpRequest {
    private final ByteBuf body;
    public HttpRequest(String method, String path, ByteBuf body) { ... }
}

// Event wrapper
public class MessageReceivedEvent {
    private final ByteBuf message;
    public MessageReceivedEvent(Channel channel, ByteBuf message) { ... }
}
```

**Configuration:**
```bash
trackConstructors=com.example.Message,com.example.HttpRequest,com.example.MessageReceivedEvent
```

## Example Code

See: `bytebuf-flow-example/src/main/java/com/example/demo/WrappedObjectFlowExample.java`

## Tests

- `testWrappedObjectFlowTracking()` - Shows flow breaking with wrappers
- `testContinuousFlowWithConstructorTracking()` - Shows continuous flow with fixes
