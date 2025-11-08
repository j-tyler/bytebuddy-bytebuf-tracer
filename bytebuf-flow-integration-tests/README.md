# ByteBuf Flow Tracker - Integration Tests

This module contains end-to-end integration tests that verify the ByteBuf tracking agent actually works by:
1. Spawning separate JVMs with the `-javaagent` parameter
2. Running test applications that use ByteBuf
3. Verifying the agent automatically instruments methods and tracks flows
4. Checking that leaks are correctly detected

## Prerequisites

Before running integration tests, you **must** build the agent JAR:

```bash
# From project root
mvn clean install -DskipTests -pl bytebuf-flow-tracker -am
```

This creates: `bytebuf-flow-tracker/target/bytebuf-flow-tracker-1.0.0-SNAPSHOT-agent.jar`

## Running Integration Tests

### With Maven

```bash
# From project root - runs all integration tests
mvn verify -pl bytebuf-flow-integration-tests

# Run specific integration test
mvn verify -pl bytebuf-flow-integration-tests -Dit.test=BasicInstrumentationIT

# Skip integration tests
mvn install -DskipITs
```

### With Gradle

```bash
# From integration-tests module
cd bytebuf-flow-integration-tests
gradle test

# Or from project root
gradle :bytebuf-flow-integration-tests:test
```

## Test Structure

### Test Applications (`src/main/java/testapp/`)

These are simple applications that exercise different tracking scenarios:

- **BasicFlowApp** - Simple ByteBuf allocation → processing → cleanup
- **LeakDetectionApp** - Intentional leak to verify detection
- **StaticMethodApp** - Static and instance method tracking
- **ConstructorTrackingApp** - Constructor tracking with `trackConstructors` config
- **WrapperObjectApp** - ByteBuf wrapped in custom objects

### Utilities (`src/main/java/utils/`)

- **AppLauncher** - Spawns JVM with agent and captures output
- **OutputVerifier** - Parses tracking output and provides assertions

### Integration Tests (`src/test/java/*IT.java`)

- **BasicInstrumentationIT** - Agent loads and instruments methods
- **LeakDetectionIT** - Leak detection works correctly
- **StaticMethodTrackingIT** - Static methods are tracked
- **ConstructorTrackingIT** - Constructor tracking config works
- **WrapperObjectTrackingIT** - Wrapper object flow tracking
- **PackageFilteringIT** - Include/exclude configs work

## How Integration Tests Work

Each test:
1. Uses `AppLauncher` to spawn a new JVM with:
   - `-javaagent:/path/to/agent.jar=<config>`
   - Test application as main class
2. Captures stdout/stderr from the spawned JVM
3. Uses `OutputVerifier` to parse and verify:
   - Agent startup messages
   - Flow tree structure
   - Leak detection
   - Method tracking

Example:
```java
@Test
public void testBasicInstrumentation() throws Exception {
    AppLauncher.AppResult result = launcher.launch(
        "com.example.bytebuf.tracker.integration.testapp.BasicFlowApp");

    OutputVerifier verifier = new OutputVerifier(result.getOutput());

    assertThat(verifier.hasAgentStarted()).isTrue();
    assertThat(verifier.hasMethodInFlow("allocate")).isTrue();
    assertThat(verifier.getLeakPaths()).isEqualTo(0);
}
```

## Key Differences from Unit Tests

| Unit Tests | Integration Tests |
|------------|-------------------|
| Manual `tracker.recordMethodCall()` | Automatic bytecode instrumentation |
| No agent required | Requires `-javaagent` JAR |
| Test tracking logic | Test real-world agent behavior |
| Fast (milliseconds) | Slower (seconds) |

## Troubleshooting

### Agent JAR not found

```
Error: Agent JAR not found at: .../bytebuf-flow-tracker-1.0.0-SNAPSHOT-agent.jar
```

**Solution**: Build the agent first:
```bash
mvn install -DskipTests -pl bytebuf-flow-tracker -am
```

### Process timeout

```
Process timed out after 30 seconds
```

**Solution**: Check test application for infinite loops or deadlocks. You can increase timeout in `AppLauncher.launch()`.

### No methods tracked

```
Expected methods to be tracked but none found
```

**Possible causes**:
1. Agent config `include` doesn't match package
2. Agent failed to load (check for startup messages)
3. Methods are private/protected (only public methods tracked by default)

### Network Issues (Maven Central unreachable)

If you encounter Maven network issues in restricted environments:

```bash
# The project is designed to run in environments with Maven access.
# If Maven Central is unreachable, you'll need to:
# 1. Build on a machine with internet access
# 2. Copy the agent JAR to the target location
# 3. Run tests with the pre-built agent JAR
```

Workaround:
```bash
# On a machine with internet:
mvn install -DskipTests

# Copy agent JAR to restricted environment
scp bytebuf-flow-tracker/target/*-agent.jar user@restricted:/path/

# On restricted machine, run tests pointing to agent JAR
mvn verify -Dagent.jar.path=/path/to/agent.jar
```

## Test Coverage

| Feature | Test Class |
|---------|------------|
| Basic instrumentation | BasicInstrumentationIT |
| Leak detection | LeakDetectionIT |
| Static methods | StaticMethodTrackingIT |
| Constructor tracking | ConstructorTrackingIT |
| Wrapper objects | WrapperObjectTrackingIT |
| Package filtering | PackageFilteringIT |

## Success Criteria

All integration tests pass when:
- ✅ Agent JAR loads without errors
- ✅ Bytecode instrumentation occurs
- ✅ Methods are automatically intercepted
- ✅ Flow trees match expected structure
- ✅ Leaks are correctly identified
- ✅ All config options work (include/exclude/trackConstructors)

## Future Enhancements

Potential additions:
- JMX functionality tests
- High-volume stress tests
- Multiple threads/concurrency tests
- Custom ObjectTrackerHandler tests
- Output format validation (CSV, JSON)
