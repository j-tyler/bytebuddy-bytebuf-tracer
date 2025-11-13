# Notes for Future Claude Sessions

**Purpose**: This document contains environment-specific setup instructions for working in this sandboxed environment. For project documentation, see:
- **User documentation**: [README.md](README.md)
- **Architecture details**: [ARCHITECTURE.md](ARCHITECTURE.md)

---

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

---

## Quick Start Checklist

Before building or running examples:

1. ☐ Check if proxy is running: `ps aux | grep working_proxy`
2. ☐ If not running: Start proxy with the Python script above
3. ☐ Verify Maven settings: `cat ~/.m2/settings.xml`
4. ☐ Build project: `mvn clean install -DskipTests`
5. ☐ Verify agent JAR exists: `ls bytebuf-flow-tracker/target/*-agent.jar`

---

## Running Examples

See [README.md](README.md) for detailed instructions on running examples. Quick reference:

```bash
cd bytebuf-flow-example

# Build classpath
mvn dependency:build-classpath -Dmdep.outputFile=/tmp/cp.txt -q
export CP="target/classes:$(cat /tmp/cp.txt)"

# Run basic example
java "-javaagent:../bytebuf-flow-tracker/target/bytebuf-flow-tracker-1.0.0-SNAPSHOT-agent.jar=include=com.example.demo" \
  -cp "${CP}" \
  com.example.demo.DemoApplication
```

**Important**: Use `\$` to escape `$` in inner class names when using `trackConstructors` argument.

---

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

---

## Environment Info

- **OS**: Linux 4.4.0
- **Java**: OpenJDK (check with `java -version`)
- **Maven**: Available via `mvn`
- **Gradle**: Available via `gradle`
- **Python**: Python 3 available for proxy script

---

## Testing

```bash
# Unit tests only (fast)
mvn clean test -DskipITs

# Integration tests (requires agent JAR built first)
mvn clean install -DskipTests  # Build agent JAR first
mvn verify -pl bytebuf-flow-integration-tests

# All tests
mvn clean install
```

See [ARCHITECTURE.md](ARCHITECTURE.md) for test structure details.

---

## Remember

- The network setup is **required** for any build that needs to download dependencies
- Always check if the proxy is running before building
- The agent uses string-based type matching to avoid early class loading
- For project features and architecture, refer to README.md and ARCHITECTURE.md

---

**Last Updated**: Session 2025-11-13 (Documentation cleanup)
**Created By**: Claude (Anthropic AI Assistant)
