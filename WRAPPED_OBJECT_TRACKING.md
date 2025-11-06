# Wrapped Object Tracking Analysis

## Question

**"If a ByteBuf is allocated, passed to a method, that method wraps ByteBuf in a tracked wrapped object, and that wrapped object is passed to a different method - would we see this as one continuous flow in the tree?"**

**Short Answer: NO** - The flow **BREAKS** when the ByteBuf is wrapped in a custom object.

## The Problem

There are actually **two separate issues** that prevent continuous flow tracking:

### Issue 1: Constructors Not Tracked

**Location:** `ByteBufFlowAgent.java:75`

```java
.method(
    isPublic()
    .or(isProtected())
    .and(not(isConstructor()))  // <-- Constructors excluded!
)
```

**Impact:** When a ByteBuf is passed to a constructor, that call is invisible to the tracker.

```java
ByteBuf buffer = Unpooled.buffer(256);
processBuffer(buffer);           // ✓ Tracked (ByteBuf parameter)
Message msg = new Message(buffer); // ✗ NOT tracked (constructor)
```

### Issue 2: Wrapped Objects Not Recognized

**Location:** `ByteBufObjectHandler.java:14-16`

```java
@Override
public boolean shouldTrack(Object obj) {
    return obj instanceof ByteBuf;  // <-- Only ByteBuf instances
}
```

**Impact:** When methods receive wrapper objects (not ByteBuf), the agent doesn't intercept them.

```java
void processMessage(Message msg) {
    // ✗ NOT tracked - Message is not a ByteBuf
    // even though it CONTAINS a ByteBuf
}
```

## Example Scenario

```java
// Step 1: Allocate ByteBuf
ByteBuf buffer = Unpooled.buffer(256);
tracker.recordMethodCall(buffer, "Client", "allocate", buffer.refCnt());
// ✓ Tracked

// Step 2: Pass to method
wrapInMessage(buffer);
// ✓ Tracked - ByteBuf is parameter

// Step 3: Inside wrapInMessage - create wrapper
Message message = new Message(buffer);
// ✗ NOT tracked - constructor excluded
// ✗ FLOW BREAKS HERE

// Step 4: Pass wrapper to another method
processMessage(message);
// ✗ NOT tracked - Message is not a ByteBuf
// ✗ FLOW STILL BROKEN

// Step 5: Extract ByteBuf
ByteBuf extracted = message.getBuffer();
// ✓ Tracked - ByteBuf return value
// ✓ Flow resumes, but with gap
```

## What Gets Tracked vs What Doesn't

### ✓ Automatically Tracked

1. **Methods with ByteBuf parameters**
   ```java
   public void process(ByteBuf buffer) { }  // ✓
   ```

2. **Methods returning ByteBuf**
   ```java
   public ByteBuf create() { }  // ✓
   ```

3. **Static methods with ByteBuf** (after our fix)
   ```java
   public static void compress(ByteBuf buffer) { }  // ✓
   ```

4. **ByteBuf method entry and exit**
   ```java
   // Both enter and exit tracked for ByteBuf parameters
   ```

### ✗ NOT Automatically Tracked

1. **Constructors** (even with ByteBuf parameter)
   ```java
   public Message(ByteBuf buffer) { }  // ✗
   ```

2. **Methods with wrapper parameters**
   ```java
   public void process(Message msg) { }  // ✗ (Message contains ByteBuf but isn't ByteBuf)
   ```

3. **Internal field access**
   ```java
   ByteBuf b = message.buffer;  // ✗ (field access not tracked)
   ```

4. **Private methods** (excluded by default)
   ```java
   private void process(ByteBuf buffer) { }  // ✗
   ```

## Flow Tree Visualization

### Without Manual Tracking (Broken Flow)

```
ROOT: Client.allocate [count=1]
└── Client.wrapInMessage [ref=1, count=1]

    ⚠️ GAP HERE ⚠️
    - Message constructor not visible
    - processMessage not visible
    - validateMessage not visible

    └── Client.extractAndRelease [ref=1, count=1]
        └── Client.cleanup [ref=0, count=1]
```

### With Manual Tracking (Continuous Flow)

```
ROOT: Client.allocate [count=1]
└── Client.wrapInMessage [ref=1, count=1]
    └── Message.<init> [ref=1, count=1]           ← Manually tracked
        └── Client.processMessage [ref=1, count=1]  ← Manually tracked
            └── Client.validateMessage [ref=1, count=1]  ← Manually tracked
                └── Client.extractAndRelease [ref=1, count=1]
                    └── Client.cleanup [ref=0, count=1]
```

## Solutions

### Solution 1: Manual Tracking (Current Best Practice)

**For Constructors:**
```java
public class MessageWithTracking {
    private final ByteBuf buffer;

    public MessageWithTracking(ByteBuf buffer) {
        this.buffer = buffer;
        // Manually track
        ByteBufFlowTracker.getInstance().recordMethodCall(
            buffer, "MessageWithTracking", "<init>", buffer.refCnt());
    }
}
```

**For Wrapper Methods:**
```java
public void processMessage(Message message) {
    // Extract and manually track
    ByteBuf buffer = message.getBuffer();
    ByteBufFlowTracker.getInstance().recordMethodCall(
        buffer, "Client", "processMessage", buffer.refCnt());
    // ... process ...
}
```

**Pros:**
- Works now without code changes
- Fine-grained control
- No performance overhead from instrumenting everything

**Cons:**
- Requires discipline
- Easy to forget
- Boilerplate code
- Not transparent

### Solution 2: Enable Constructor Tracking

**Change in ByteBufFlowAgent.java:**
```java
.method(
    isPublic()
    .or(isProtected())
    // Remove: .and(not(isConstructor()))
)
```

**Pros:**
- Constructors with ByteBuf parameters automatically tracked
- More complete flow visibility
- No manual tracking needed for constructors

**Cons:**
- Constructors are called frequently (performance impact)
- Many constructors don't need tracking
- May create noise in flow tree
- Need to be careful with initialization order

**Recommendation:** Worth enabling if constructors are important to your flow analysis.

### Solution 3: Custom ObjectTrackerHandler for Wrappers

**Create a handler that tracks both ByteBuf and wrapper objects:**

```java
public class WrapperAwareHandler implements ObjectTrackerHandler {

    @Override
    public boolean shouldTrack(Object obj) {
        // Track ByteBuf directly
        if (obj instanceof ByteBuf) {
            return true;
        }
        // Track wrapper objects that contain ByteBuf
        if (obj instanceof ByteBufContainer) {
            return true;
        }
        return false;
    }

    @Override
    public int getMetric(Object obj) {
        if (obj instanceof ByteBuf) {
            return ((ByteBuf) obj).refCnt();
        }
        if (obj instanceof ByteBufContainer) {
            ByteBuf buffer = ((ByteBufContainer) obj).getBuffer();
            return buffer != null ? buffer.refCnt() : 0;
        }
        return 0;
    }

    @Override
    public String getObjectType() {
        return "ByteBuf/Wrapper";
    }
}

// Wrapper interface
public interface ByteBufContainer {
    ByteBuf getBuffer();
}
```

**Pros:**
- Tracks wrapper objects automatically
- Continuous flow visibility
- Clean separation of concerns

**Cons:**
- Requires wrapper classes to implement interface
- More complex tracking logic
- May track same ByteBuf twice (as ByteBuf and as wrapper)
- Need to deduplicate in flow analysis

**Recommendation:** Best for applications with consistent wrapper patterns.

### Solution 4: Reflection-Based Field Detection

**Automatically detect ByteBuf fields in wrapper objects:**

```java
public class ReflectiveHandler implements ObjectTrackerHandler {

    @Override
    public boolean shouldTrack(Object obj) {
        if (obj instanceof ByteBuf) return true;

        // Check if object has ByteBuf fields
        return hasByteBufField(obj);
    }

    private boolean hasByteBufField(Object obj) {
        for (Field field : obj.getClass().getDeclaredFields()) {
            if (ByteBuf.class.isAssignableFrom(field.getType())) {
                return true;
            }
        }
        return false;
    }

    @Override
    public int getMetric(Object obj) {
        if (obj instanceof ByteBuf) {
            return ((ByteBuf) obj).refCnt();
        }

        // Find and check ByteBuf field
        ByteBuf buffer = extractByteBuf(obj);
        return buffer != null ? buffer.refCnt() : 0;
    }
}
```

**Pros:**
- Works with any wrapper class
- No interface implementation needed
- Fully automatic

**Cons:**
- High performance overhead (reflection)
- May match unintended classes
- Complex field extraction logic
- Security/module access issues

**Recommendation:** Too expensive for production use.

## Recommendations

### For Development/Testing
Use **Solution 3 (Custom Handler with Interface)** if you have a small set of wrapper types.

### For Production
Use **Solution 1 (Manual Tracking)** for critical paths where you need complete visibility.

### For Enhanced Visibility
Enable **Solution 2 (Constructor Tracking)** if constructor calls are important to your analysis.

## Testing

See `ByteBufFlowTrackerTest.java` for three test cases:

1. **testConstructorTracking()** - Shows constructors aren't tracked
2. **testWrappedObjectFlowTracking()** - Shows flow breaks with wrappers
3. **testConstructorWithByteBuffParameter()** - Shows manual tracking workaround

Run with:
```bash
mvn test -pl bytebuf-flow-tracker -Dtest=ByteBufFlowTrackerTest#testWrappedObjectFlowTracking
```

## Example Application

See `WrappedObjectFlowExample.java` for a complete demonstration:

```bash
cd bytebuf-flow-example
mvn exec:java -Dexec.mainClass="com.example.demo.WrappedObjectFlowExample"
```

This shows side-by-side comparison of:
- Automatic tracking (with gaps)
- Manual tracking (continuous flow)

## Summary

**Question:** Would we see continuous flow when ByteBuf is wrapped?

**Answer:**
- ✗ **NO** with default configuration
- ✓ **YES** with manual tracking
- ✓ **PARTIAL** if constructors enabled (still need wrapper handling)
- ✓ **YES** with custom ObjectTrackerHandler (if wrappers implement interface)

The **root cause** is that `shouldTrack()` only recognizes `instanceof ByteBuf`, and constructors are excluded from instrumentation.

**Best current practice:** Use manual tracking in critical paths where ByteBuf is wrapped in custom objects.
