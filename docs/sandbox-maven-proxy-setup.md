# Maven Proxy Configuration for Claude Code Sandbox

## Problem

Maven builds fail with "Temporary failure in name resolution" or "407 Proxy Authentication Required" in the Claude Code remote sandbox environment. This is because:

1. The sandbox routes all outbound traffic through an authenticated HTTP proxy
2. Maven 3.9+ uses a native HTTP transport that doesn't properly handle proxy authentication from JVM system properties
3. While `curl` works (it uses `HTTP_PROXY` env vars), Maven's Java process needs explicit proxy configuration

## Solution

Two things are needed:

### 1. Create `~/.m2/settings.xml` with proxy credentials

Extract proxy details from environment variables and create a Maven settings file:

```bash
PROXY_URL="$HTTP_PROXY"
PROXY_USERPASS=$(echo "$PROXY_URL" | sed -n 's|http://\(.*\)@.*|\1|p')
PROXY_USER=$(echo "$PROXY_USERPASS" | cut -d: -f1)
PROXY_PASS=$(echo "$PROXY_USERPASS" | cut -d: -f2-)
PROXY_HOST="21.0.0.119"
PROXY_PORT="15004"

mkdir -p ~/.m2

cat > ~/.m2/settings.xml << XMLEOF
<settings xmlns="http://maven.apache.org/SETTINGS/1.2.0"
          xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
          xsi:schemaLocation="http://maven.apache.org/SETTINGS/1.2.0 https://maven.apache.org/xsd/settings-1.2.0.xsd">
  <proxies>
    <proxy>
      <id>http-proxy</id>
      <active>true</active>
      <protocol>http</protocol>
      <host>${PROXY_HOST}</host>
      <port>${PROXY_PORT}</port>
      <username>${PROXY_USER}</username>
      <password>${PROXY_PASS}</password>
      <nonProxyHosts>localhost|127.0.0.1</nonProxyHosts>
    </proxy>
    <proxy>
      <id>https-proxy</id>
      <active>true</active>
      <protocol>https</protocol>
      <host>${PROXY_HOST}</host>
      <port>${PROXY_PORT}</port>
      <username>${PROXY_USER}</username>
      <password>${PROXY_PASS}</password>
      <nonProxyHosts>localhost|127.0.0.1</nonProxyHosts>
    </proxy>
  </proxies>
</settings>
XMLEOF
```

Note: The `${PROXY_HOST}` etc. in the heredoc are shell variables expanded at write time, not Maven property references.

### 2. Force Maven to use the Wagon HTTP transport

Maven 3.9+ defaults to a native HTTP transport that has a bug with authenticated proxies (works for small POM downloads but fails with 407 on larger JAR downloads). Force the older Wagon transport:

```bash
cat > ~/.mavenrc << 'EOF'
MAVEN_OPTS="$MAVEN_OPTS -Dmaven.resolver.transport=wagon"
EOF
```

Alternatively, pass `-Dmaven.resolver.transport=wagon` on every `mvn` command.

### Quick one-liner setup

```bash
# Extract proxy info and create both config files
PROXY_USERPASS=$(echo "$HTTP_PROXY" | sed -n 's|http://\(.*\)@.*|\1|p') && \
PROXY_USER=$(echo "$PROXY_USERPASS" | cut -d: -f1) && \
PROXY_PASS=$(echo "$PROXY_USERPASS" | cut -d: -f2-) && \
mkdir -p ~/.m2 && \
echo "<settings><proxies><proxy><id>https-proxy</id><active>true</active><protocol>https</protocol><host>21.0.0.119</host><port>15004</port><username>$PROXY_USER</username><password>$PROXY_PASS</password></proxy></proxies></settings>" > ~/.m2/settings.xml && \
echo 'MAVEN_OPTS="$MAVEN_OPTS -Dmaven.resolver.transport=wagon"' > ~/.mavenrc && \
echo "Done — Maven proxy configured"
```

## Verification

After setup, all three should succeed:

```bash
mvn compile          # compiles sources
mvn test             # runs unit tests (~127 tests)
mvn verify           # runs unit + E2E integration tests (~162 tests total)
```

## Notes

- The proxy credentials are JWT tokens with expiration — they're set fresh in `JAVA_TOOL_OPTIONS` and `HTTP_PROXY` at sandbox start. If a long-running session expires them, a new sandbox session is needed.
- The proxy host/port (`21.0.0.119:15004`) appear to be stable across sessions. The credentials change each session.
- The `JAVA_TOOL_OPTIONS` environment variable already sets JVM-level proxy properties, but Maven's resolver doesn't use those for artifact downloads — it needs `settings.xml`.
