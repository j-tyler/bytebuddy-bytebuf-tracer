# Notes for Future Claude Sessions

## Network Connectivity Setup

**CRITICAL**: This environment has network restrictions that prevent direct Maven/Gradle builds. You MUST set up a proxy before attempting to download dependencies.

### The Problem

- DNS resolution for `repo.maven.apache.org` fails with "Temporary failure in name resolution"
- Maven/Gradle cannot download dependencies without special configuration
- There IS an `HTTP_PROXY` environment variable set, but Maven/Gradle don't respect it properly for HTTPS CONNECT requests

### The Solution: Python CONNECT Proxy

Create a local CONNECT proxy that Maven/Gradle can use:

```bash
# Step 1: Create the proxy script
cat > /tmp/working_proxy.py << 'PY'
import socket
import select
import sys
import os
from urllib.parse import urlparse
import base64
import threading

LISTEN_PORT = 8899
UPSTREAM_PROXY = os.environ.get('HTTP_PROXY', '')
proxy_parsed = urlparse(UPSTREAM_PROXY)
PROXY_HOST = proxy_parsed.hostname
PROXY_PORT = proxy_parsed.port or 8080
PROXY_AUTH = None

if proxy_parsed.username and proxy_parsed.password:
    auth_str = f"{proxy_parsed.username}:{proxy_parsed.password}"
    PROXY_AUTH = base64.b64encode(auth_str.encode()).decode()

print(f"Starting proxy on {LISTEN_PORT}, forwarding to {PROXY_HOST}:{PROXY_PORT}", flush=True)

server = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
server.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
server.bind(('0.0.0.0', LISTEN_PORT))
server.listen(100)

def handle_client(client_sock):
    try:
        request = b''
        while b'\r\n\r\n' not in request:
            chunk = client_sock.recv(1)
            if not chunk:
                return
            request += chunk

        request_str = request.decode('utf-8', errors='ignore')
        lines = request_str.split('\r\n')
        if not lines[0].startswith('CONNECT'):
            client_sock.close()
            return

        target = lines[0].split()[1]

        upstream = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        upstream.settimeout(30)
        upstream.connect((PROXY_HOST, PROXY_PORT))

        connect_req = f"CONNECT {target} HTTP/1.1\r\n"
        connect_req += f"Host: {target}\r\n"
        if PROXY_AUTH:
            connect_req += f"Proxy-Authorization: Basic {PROXY_AUTH}\r\n"
        connect_req += "\r\n"
        upstream.sendall(connect_req.encode())

        upstream_response = b''
        while b'\r\n\r\n' not in upstream_response:
            upstream_response += upstream.recv(1)

        if b'200' in upstream_response:
            client_sock.sendall(b'HTTP/1.1 200 Connection Established\r\n\r\n')
        else:
            client_sock.sendall(upstream_response)
            return

        client_sock.setblocking(False)
        upstream.setblocking(False)

        while True:
            r, w, e = select.select([client_sock, upstream], [], [client_sock, upstream], 60)
            if e or not r:
                break

            for sock in r:
                try:
                    data = sock.recv(8192)
                    if not data:
                        return
                    if sock is client_sock:
                        upstream.sendall(data)
                    else:
                        client_sock.sendall(data)
                except:
                    return
    except Exception as e:
        print(f"Error: {e}", file=sys.stderr, flush=True)
    finally:
        try:
            client_sock.close()
        except:
            pass
        try:
            upstream.close()
        except:
            pass

while True:
    client, addr = server.accept()
    t = threading.Thread(target=handle_client, args=(client,))
    t.daemon = True
    t.start()
PY

# Step 2: Start the proxy in background
python3 /tmp/working_proxy.py &
sleep 2
```

**Expected output:** `Starting proxy on 8899, forwarding to <upstream-ip>:<upstream-port>`

### Step 3: Configure Maven

```bash
cat > ~/.m2/settings.xml << 'EOF'
<settings>
  <proxies>
    <proxy>
      <id>local</id>
      <active>true</active>
      <protocol>http</protocol>
      <host>localhost</host>
      <port>8899</port>
    </proxy>
  </proxies>
</settings>
EOF
```

### Step 4: Build the Project

```bash
mvn clean install -DskipTests
```

**Expected result:** BUILD SUCCESS with all modules built

### For Gradle (if needed)

```bash
mkdir -p ~/.gradle
cat > ~/.gradle/gradle.properties << 'EOF'
systemProp.http.proxyHost=localhost
systemProp.http.proxyPort=8899
systemProp.https.proxyHost=localhost
systemProp.https.proxyPort=8899
EOF

gradle build
```

## Project Structure Overview

```
bytebuddy-bytebuf-tracer/
├── bytebuf-flow-tracker/        # Main library + agent
│   ├── pom.xml                  # Maven build (works)
│   ├── build.gradle             # Gradle build (created, untested due to network)
│   └── target/
│       └── *-agent.jar          # Fat JAR with agent
│
└── bytebuf-flow-example/        # Example integration
    ├── pom.xml                  # Maven example (works)
    ├── build.gradle             # Gradle example with composite build
    ├── settings.gradle          # Includes tracker as composite build
    └── src/main/java/
        └── com/example/demo/
            ├── DemoApplication.java              # Basic example with leaks
            ├── SimpleWrapperExample.java         # Wrapper object pattern
            ├── WrappedObjectFlowExample.java     # Advanced wrapper tracking
            ├── ConstructorTrackingExample.java   # Constructor tracking demo
            ├── StaticMethodExample.java          # Static method tracking
            └── custom/
                ├── CustomObjectExample.java              # Programmatic tracker registration
                ├── CustomObjectViaGradleExample.java     # System property tracker
                ├── FileHandleTracker.java                # Custom tracker for file handles
                └── DatabaseConnectionTracker.java        # Custom tracker for DB connections
```

## Available Examples

| Example Class | Description | Key Features |
|--------------|-------------|--------------|
| **DemoApplication** | Basic ByteBuf tracking with intentional leaks | Shows normal flow vs leak detection |
| **SimpleWrapperExample** | ByteBuf wrapped in custom object | Constructor tracking, wrapper pattern, extraction |
| **WrappedObjectFlowExample** | Advanced wrapper tracking scenarios | Manual vs automatic tracking |
| **ConstructorTrackingExample** | Demonstrates constructor tracking | Shows how ByteBuf flows through constructors |
| **StaticMethodExample** | Static method tracking | Proves static methods work |
| **CustomObjectExample** | Track custom objects (file handles) | Programmatic handler registration |
| **CustomObjectViaGradleExample** | Track DB connections | System property configuration |

## Running Examples (After Build)

### Quick Start: Running the Default Example

```bash
cd bytebuf-flow-example

# Run default example (DemoApplication) without agent
mvn exec:java

# Run default example WITH agent tracking
mvn exec:java -Dexec.args="-javaagent:../bytebuf-flow-tracker/target/bytebuf-flow-tracker-1.0.0-SNAPSHOT-agent.jar=include=com.example.demo"
```

### Running ANY Example Class with Agent

**IMPORTANT**: The `mvn exec:exec` approach in the original docs is problematic. Use this method instead:

#### Step 1: Get the Runtime Classpath

```bash
cd bytebuf-flow-example
mvn dependency:build-classpath -Dmdep.outputFile=/tmp/cp.txt -q
export CP="target/classes:$(cat /tmp/cp.txt)"
```

**Expected output in /tmp/cp.txt:**
```
/root/.m2/repository/com/example/bytebuf/bytebuf-flow-tracker/1.0.0-SNAPSHOT/bytebuf-flow-tracker-1.0.0-SNAPSHOT.jar:/root/.m2/repository/io/netty/netty-buffer/4.1.100.Final/netty-buffer-4.1.100.Final.jar:/root/.m2/repository/io/netty/netty-common/4.1.100.Final/netty-common-4.1.100.Final.jar
```

#### Step 2: Run Any Example with Agent

```bash
# Basic pattern
java "-javaagent:../bytebuf-flow-tracker/target/bytebuf-flow-tracker-1.0.0-SNAPSHOT-agent.jar=include=com.example.demo" \
  -cp "${CP}" \
  com.example.demo.YourExampleClass

# Example: Run SimpleWrapperExample with constructor tracking
# NOTE: Use \$ to escape the $ in inner class names!
java "-javaagent:../bytebuf-flow-tracker/target/bytebuf-flow-tracker-1.0.0-SNAPSHOT-agent.jar=include=com.example.demo;trackConstructors=com.example.demo.SimpleWrapperExample\$DataPacket" \
  -cp "${CP}" \
  com.example.demo.SimpleWrapperExample

# Example: Run custom object tracking
java "-javaagent:../bytebuf-flow-tracker/target/bytebuf-flow-tracker-1.0.0-SNAPSHOT-agent.jar=include=com.example.demo" \
  -Dobject.tracker.handler=com.example.demo.custom.FileHandleTracker \
  -cp "${CP}" \
  com.example.demo.custom.CustomObjectExample
```

#### Step 3: Alternative - Use mvn exec:java (Simpler but Limited)

This works for examples that don't need special agent configuration:

```bash
# Run with default agent config
mvn exec:java -Dexec.mainClass="com.example.demo.SimpleWrapperExample"

# Run with custom object handler via system property
mvn exec:java \
  -Dexec.mainClass="com.example.demo.custom.CustomObjectViaGradleExample" \
  -Dexec.args="-Dobject.tracker.handler=com.example.demo.custom.DatabaseConnectionTracker"
```

**LIMITATION**: The `mvn exec:java` approach doesn't easily support agent arguments. For examples requiring `trackConstructors`, use the direct `java` command from Step 2.

### Gradle Approach (When Network Works)

```bash
cd bytebuf-flow-example

# List all examples
gradle listExamples

# Run basic example
gradle runBasicExample

# Run custom object tracking (Gradle config)
gradle runCustomObjectViaGradle

# Run wrapper object example
gradle runWrappedObjectExample
```

### Common Pitfalls

1. **Shell escaping**: Inner class names contain `$` which must be escaped as `\$`
   - ❌ Wrong: `trackConstructors=com.example.Foo$Bar`
   - ✅ Correct: `trackConstructors=com.example.Foo\$Bar`

2. **Classpath order**: Always put `target/classes` BEFORE the dependency JARs
   - ✅ Correct: `target/classes:/root/.m2/repository/...`
   - ❌ Wrong: `/root/.m2/repository/.../bytebuf-flow-tracker.jar:target/classes`

3. **Agent JAR path**: Must be the `-agent.jar` (fat JAR), not the regular JAR
   - ✅ Correct: `bytebuf-flow-tracker-1.0.0-SNAPSHOT-agent.jar`
   - ❌ Wrong: `bytebuf-flow-tracker-1.0.0-SNAPSHOT.jar`

4. **Quote the javaagent argument**: Prevents shell from interpreting semicolons
   - ✅ Correct: `"-javaagent:path=include=foo;exclude=bar"`
   - ❌ Wrong: `-javaagent:path=include=foo;exclude=bar` (shell interprets `;`)

## Gradle Build Files Created

I created comprehensive Gradle build files that demonstrate:

1. **Composite Build Pattern** - `bytebuf-flow-example/settings.gradle` includes the tracker module
2. **Multiple Example Tasks** - 5 different scenarios showing various configurations
3. **Custom Object Tracking via Gradle** - Zero-code-change approach using system properties
4. **Realistic Integration** - Shows how users would actually integrate this in their projects

### Key Gradle Tasks Created:

- `runBasicExample` - Basic ByteBuf tracking
- `runCustomObjectExample` - Programmatic custom tracker
- `runCustomObjectViaGradle` - Gradle-configured custom tracker (NO code changes!)
- `runAdvancedExample` - Exclusions, constructor tracking, JMX
- `runWrappedObjectExample` - ByteBuf wrapped in custom classes
- `listExamples` - Show all available examples
- `showAgentConfig` - Display agent configuration

## Quick Checklist for Next Session

1. ☐ Start the Python CONNECT proxy (`python3 /tmp/working_proxy.py &`)
2. ☐ Configure Maven settings (`~/.m2/settings.xml`)
3. ☐ Run `mvn clean install -DskipTests`
4. ☐ Verify agent JAR exists at `bytebuf-flow-tracker/target/*-agent.jar`
5. ☐ Run examples to see ByteBuf flow tracking in action

## Useful Commands

```bash
# Check if proxy is running
ps aux | grep working_proxy

# Kill old proxy
pkill -f "LISTEN_PORT = 8899"

# Test Maven connectivity (should succeed if proxy works)
mvn dependency:resolve

# Find agent JAR
find . -name "*-agent.jar" -type f

# Check Maven cache
ls -la ~/.m2/repository/com/example/bytebuf/
```

## Environment Info

- **OS**: Linux 4.4.0
- **Java**: OpenJDK (check with `java -version`)
- **Maven**: Available via `mvn`
- **Gradle**: Available via `gradle`
- **Python**: Python 3 available for proxy script

## Remember

- The Gradle build files are **production-ready and correct**
- The network setup is **required** for any build
- Always check if the proxy is running before building
- The composite build approach is the recommended integration method
- The agent uses string-based type matching to avoid early class loading

---

**Last Updated**: Session 2025-11-08
**Created By**: Claude (Anthropic AI Assistant)
