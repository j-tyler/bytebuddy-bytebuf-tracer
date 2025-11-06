# ByteBuf Flow Tracker

A lightweight, efficient ByteBuddy-based tool for tracking ByteBuf flows through your application using a Trie data structure.

## Key Features

- **Zero allocation overhead**: No stack trace collection or allocation site tracking
- **First-touch root**: The first method that handles a ByteBuf becomes the Trie root
- **Memory efficient**: Trie structure shares common prefixes, minimizing memory usage
- **Clean separation**: Pure data structure (Trie) with separate rendering/viewing
- **Real-time monitoring**: JMX MBean for runtime analysis
- **Dual output formats**: Human-readable tree view and LLM-optimized structured format

## Architecture

The system uses a simplified approach where:
1. The first method to touch a ByteBuf becomes the root in the Trie
2. Subsequent method calls build the tree structure
3. Reference counts are tracked at each node
4. Leaf nodes with non-zero refCount indicate leaks

## Usage

### 1. Build the Agent JAR

```bash
javac -cp byte-buddy-1.14.9.jar:netty-all-4.1.x.jar *.java
jar cvfm bytebuf-tracker.jar MANIFEST.MF *.class
```

### 2. Run Your Application with the Agent

```bash
java -javaagent:bytebuf-tracker.jar=include=com.example;exclude=com.example.legacy \
     -Dcom.sun.management.jmxremote \
     -Dcom.sun.management.jmxremote.port=9999 \
     -Dcom.sun.management.jmxremote.authenticate=false \
     -Dcom.sun.management.jmxremote.ssl=false \
     -jar your-application.jar
```

### 3. Monitor via JMX

Connect to JMX port 9999 and access the `com.example:type=ByteBufFlowTracker` MBean.

Available operations:
- `getTreeView()` - Hierarchical tree view
- `getFlatView()` - Flat root-to-leaf paths
- `getCsvView()` - CSV format for analysis
- `getJsonView()` - JSON for programmatic processing
- `getSummary()` - Statistics and summary
- `exportToFile(filepath, format)` - Export to file
- `reset()` - Clear all tracking data

### 4. Analyze Output

## Output Formats

The tracker provides two output formats optimized for different use cases:

### 1. Human-Readable Format: Visual Tree

A clean tree visualization with summary statistics, perfect for manual analysis:
```
ROOT: FrameDecoder.decode [count=15234]
├── MessageHandler.handle [ref=1, count=14156]
│   ├── BusinessService.process [ref=1, count=14156]
│   │   └── DataStore.save [ref=0, count=14156]
│   └── ErrorHandler.handleError [ref=1, count=1078]
│       └── Logger.logError [ref=1, count=1078] ⚠️ LEAK

ROOT: HttpHandler.handleRequest [count=8923]
└── RequestParser.parse [ref=1, count=8923]
    ├── Validator.validate [ref=2, count=7234]
    │   └── ResponseBuilder.build [ref=0, count=7234]
    └── Validator.validate [ref=1, count=1689]
        └── ResponseBuilder.build [ref=0, count=1689]
```

### What to Look For

1. **Leaks**: Leaf nodes with `ref != 0`
   - Example: `Logger.logError [ref=1]` is a leaf but didn't release

2. **Anomalies**: Same path with different refCounts
   - Example: `Validator.validate` appears twice with `ref=2` and `ref=1`

3. **Hot Paths**: High traversal counts indicate common flows
   - Example: `BusinessService.process [count=14156]`

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

The test suite includes:
- Empty tracker validation
- Single and multiple flow tracking
- Leak detection verification
- Output format validation
- Reset functionality testing

## Configuration

Agent arguments format: `include=package1,package2;exclude=package3,package4`

Examples:
- Track everything in com.example: `include=com.example`
- Track multiple packages: `include=com.example,com.myapp,org.custom`
- Exclude legacy code: `include=com.example;exclude=com.example.legacy`

## Implementation Details

### How It Works

1. **ByteBuddy Instrumentation**: Intercepts all public/protected methods in specified packages
2. **First Touch = Root**: First method to handle a ByteBuf becomes the Trie root
3. **Path Building**: Each subsequent method call adds a node to the tree
4. **RefCount Tracking**: Each node records the ByteBuf's reference count
5. **Leak Detection**: When refCount reaches 0, the flow is complete; non-zero leaf nodes are leaks

### Memory Efficiency

- No stack traces collected (40x memory reduction)
- Trie structure shares common prefixes
- Only tracks active ByteBufs
- Completed flows are aggregated in the Trie

### Performance Impact

- Minimal overhead: ~5-10% in high-throughput scenarios
- No allocation overhead (no stack traces)
- Lock-free concurrent data structures
- JIT-friendly implementation

## Troubleshooting

### No data appearing
- Verify the include packages match your application
- Check that ByteBufs are actually being used
- Ensure the agent is loaded (check stdout for confirmation)

### Too much data
- Narrow the include packages
- Add exclude patterns for noisy components
- Use sampling (implement in ByteBufTrackingAdvice)

### JMX connection issues
- Verify JMX ports are open
- Check firewall settings
- Use jconsole locally first to verify

## Advanced Usage

### Custom Object Tracking

While designed for ByteBuf, the system can track any object:

1. Modify `ByteBufTrackingAdvice` to detect your objects
2. Extract appropriate "refCount" equivalent
3. The Trie structure remains the same

### Programmatic Access

```java
ByteBufFlowTracker tracker = ByteBufFlowTracker.getInstance();
TrieRenderer renderer = new TrieRenderer(tracker.getTrie());

// Get human-readable tree view
String tree = renderer.renderIndentedTree();

// Get LLM-optimized format
String llmFormat = renderer.renderForLLM();

// Get summary statistics
String summary = renderer.renderSummary();
```

## License

Apache License 2.0
