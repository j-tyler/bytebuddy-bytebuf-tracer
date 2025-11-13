# Environment Setup Notes

Environment-specific setup for this sandbox. For project docs see [README.md](README.md) and [ARCHITECTURE.md](ARCHITECTURE.md).

---

## Network Setup (REQUIRED)

**CRITICAL**: DNS fails for Maven repos. Must setup proxy before building.

### Problem
- `repo.maven.apache.org` DNS fails
- Maven/Gradle ignore `HTTP_PROXY` for HTTPS CONNECT

### Solution: Python CONNECT Proxy

CONNECT proxy on port 8899 forwards to upstream HTTP_PROXY. Handles auth, bidirectional relay. Script creates listening socket, accepts CONNECT requests, forwards to upstream with auth, then relays bidirectionally.

```bash
cat > /tmp/working_proxy.py << 'PY'
import socket,select,os,base64,threading
from urllib.parse import urlparse
p=urlparse(os.environ.get('HTTP_PROXY',''));H,P,A=p.hostname,p.port or 8080,None
if p.username and p.password:A=base64.b64encode(f"{p.username}:{p.password}".encode()).decode()
print(f"Starting proxy on 8899, forwarding to {H}:{P}",flush=True)
s=socket.socket();s.setsockopt(socket.SOL_SOCKET,socket.SO_REUSEADDR,1);s.bind(('0.0.0.0',8899));s.listen(100)
def h(c):
 try:
  r=b'';
  while b'\r\n\r\n'not in r:r+=c.recv(1)
  l=r.decode('utf-8','ignore').split('\r\n')
  if not l[0].startswith('CONNECT'):c.close();return
  t=l[0].split()[1];u=socket.socket();u.settimeout(30);u.connect((H,P));q=f"CONNECT {t} HTTP/1.1\r\nHost: {t}\r\n"
  if A:q+=f"Proxy-Authorization: Basic {A}\r\n"
  u.sendall((q+"\r\n").encode());e=b''
  while b'\r\n\r\n'not in e:e+=u.recv(1)
  if b'200'not in e:c.sendall(e);return
  c.sendall(b'HTTP/1.1 200 Connection Established\r\n\r\n');c.setblocking(False);u.setblocking(False)
  while True:
   x,_,z=select.select([c,u],[],[c,u],60)
   if z or not x:break
   for k in x:
    d=k.recv(8192)
    if not d:return
    (u if k is c else c).sendall(d)
 except:pass
 finally:
  try:c.close()
  except:pass
  try:u.close()
  except:pass
while True:threading.Thread(target=h,args=(s.accept()[0],),daemon=True).start()
PY
python3 /tmp/working_proxy.py &
sleep 2  # Expected: "Starting proxy on 8899, forwarding to <upstream>:<port>"

# Configure Maven + Build
cat > ~/.m2/settings.xml << 'EOF'
<settings><proxies><proxy><id>local</id><active>true</active><protocol>http</protocol><host>localhost</host><port>8899</port></proxy></proxies></settings>
EOF
mvn clean install -DskipTests  # Expect BUILD SUCCESS
```

**Gradle**: `mkdir -p ~/.gradle && echo -e "systemProp.http.proxyHost=localhost\nsystemProp.http.proxyPort=8899\nsystemProp.https.proxyHost=localhost\nsystemProp.https.proxyPort=8899" > ~/.gradle/gradle.properties`

---

## Quick Start

Check proxy (`ps aux | grep working_proxy`), run script if missing, build (`mvn clean install -DskipTests`), verify agent JAR exists.

**Run example**: `cd bytebuf-flow-example && mvn dependency:build-classpath -Dmdep.outputFile=/tmp/cp.txt -q && export CP="target/classes:$(cat /tmp/cp.txt)" && java "-javaagent:../bytebuf-flow-tracker/target/bytebuf-flow-tracker-1.0.0-SNAPSHOT-agent.jar=include=com.example.demo" -cp "${CP}" com.example.demo.DemoApplication`

**Commands**: `pkill -f "LISTEN_PORT = 8899"` (kill proxy), `find . -name "*-agent.jar"` (find JAR), `mvn clean test -DskipITs` (unit tests), `mvn clean install -DskipTests && mvn verify` (integration tests)

**Note**: Escape `$` as `\$` in inner class names for `trackConstructors` arg. Agent uses string-based type matching (avoids early class loading).
