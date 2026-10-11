# Supplying the IBM JDBC driver

The IBM Data Server Driver for JDBC and SQLJ is **not** distributed with this
project. You supply it, and you use it under IBM's own terms. The reasoning
is in [ADR 0048](../adr/0048-external-jdbc-driver.md).

This page is the operator-facing part: how to get the driver onto the
classpath, and how to tell whether it worked.

## What happens if you skip this

The application will not start. Context refresh fails with:

```
java.lang.IllegalStateException: Cannot load driver class: com.ibm.db2.jcc.DB2Driver
```

That is deliberate. A missing driver is a deployment error, and it surfaces
once, at startup, rather than as a failure on the first tool call.

## Running the packaged jar

The jar is built with Spring Boot's `ZIP` layout, so its `Main-Class` is
`PropertiesLauncher`, which reads the `loader.path` system property.

```bash
java -Dloader.path=/opt/db2/jcc-12.1.5.0.jar \
     -jar db2-mcp-server-1.0.1.jar
```

`loader.path` accepts a comma-separated list, and directories as well as
individual files — so a directory works if you prefer to drop the driver in
and not name a version:

```bash
java -Dloader.path=/opt/db2/drivers \
     -jar db2-mcp-server-1.0.1.jar
```

The property must come **before** `-jar`: everything after the jar name is
passed to the application as program arguments, not to the JVM.

### Windows

PowerShell:

```powershell
java "-Dloader.path=C:\opt\db2\jcc-12.1.5.0.jar" `
     -jar db2-mcp-server-1.0.1.jar
```

**The quotes are not optional in PowerShell.** Without them the property
never reaches the JVM at all — PowerShell consumes the argument, the JVM
starts with no `loader.path` set, and the server fails with
`Cannot load driver class` as though you had passed nothing. Verified on
Windows PowerShell 5.1 by inspecting `java -XshowSettings:properties`: the
unquoted form shows no `loader.path` property, the quoted form shows it.
Quote the whole `-D…` argument, as above, not just the path.

`cmd.exe`:

```bat
java -Dloader.path=C:\opt\db2\jcc-12.1.5.0.jar -jar db2-mcp-server-1.0.1.jar
```

Separate multiple paths with a comma on every platform — **not** with the
platform's classpath separator. `loader.path` is comma-separated; a Windows
`;` or a Unix `:` silently fails to load the driver. Verified both ways
against a real Db2: comma gives `Db2 connectivity check: OK`, semicolon
gives `Cannot load driver class`.

```powershell
java "-Dloader.path=C:\opt\db2\jcc-12.1.5.0.jar,C:\opt\db2\db2jcc_license_cisuz.jar" `
     -jar db2-mcp-server-1.0.1.jar
```

Never put database credentials on the command line: on both platforms they
are visible to anyone who can list processes, and shells record them in
history. Use the environment, or your MCP client's `env` block
([ADR 0004](../adr/0004-credentials-env-vars.md)).

### Db2 for z/OS and Db2 for i

Those platforms may require additional IBM licence activation files
alongside the driver. If yours does, add it to `loader.path` with the driver:

```bash
java -Dloader.path=/opt/db2/jcc-12.1.5.0.jar,/opt/db2/db2jcc_license_cisuz.jar \
     -jar db2-mcp-server-1.0.1.jar
```

Whether your entitlement requires this, and which file, is a question for
your IBM terms and your DBAs. This project does not supply, bundle or
validate activation files, and takes no position on what your entitlement
covers.

## Configuring the MCP client

MCP clients launch the server as a subprocess, so the flag goes in the client
configuration rather than on a shell command line. The `args` array is
ordered — `-Dloader.path` must precede `-jar`:

```json
{
  "mcpServers": {
    "db2": {
      "command": "java",
      "args": [
        "-Dloader.path=/opt/db2/jcc-12.1.5.0.jar",
        "-jar",
        "/opt/db2-mcp-server/db2-mcp-server-1.0.1.jar"
      ],
      "env": {
        "DB2_HOST": "db2.example.internal",
        "DB2_PORT": "50000",
        "DB2_DATABASE": "ITSM",
        "DB2_USER": "mcp_reader",
        "DB2_PASSWORD": "..."
      }
    }
  }
}
```

Credentials belong in the client's environment block or a secret manager,
never in a committed file ([ADR 0004](../adr/0004-credentials-env-vars.md)).

## Running from source

**The test suites need no extra flag.** The driver is a `test`-scope
dependency, so Maven puts it on the test classpath for you.

**`spring-boot:run` does need it**, because `test` scope keeps the driver off
the run classpath as well:

```bash
./mvnw spring-boot:run \
  -Dspring-boot.run.additionalClasspathElements=/opt/db2/jcc-12.1.5.0.jar
```

```powershell
./mvnw spring-boot:run `
  "-Dspring-boot.run.additionalClasspathElements=C:\opt\db2\jcc-12.1.5.0.jar"
```

Note that `spring-boot:run` uses the project classpath, so it takes
`additionalClasspathElements` - **not** `loader.path`, which only the
packaged jar's `PropertiesLauncher` reads.

## What the build downloads

Running the build or the tests **does resolve the IBM driver from Maven
Central** into your local Maven repository. This project does not redistribute
it, but it does declare it, so the bytes arrive from IBM's publication on
Maven Central under IBM's own terms. Stated plainly because "we don't ship
it" is sometimes read as "it is never downloaded", and that is not the case.

If your organization needs to avoid that resolution, install your entitled
copy into your own repository manager under the same coordinates
(`com.ibm.db2:jcc:12.1.5.0`) and build against that. Packaging alone
(`./mvnw -DskipTests package`) does not need the driver at all.

## Vulnerability monitoring is yours

Because the driver is `test` scope, it is **not** in the CycloneDX SBOM this
build publishes, and therefore not covered by the project's CI
dependency-vulnerability scan. The SBOM describes what this project
delivers, and it does not deliver a driver.

That means **you** are responsible for tracking security advisories for the
driver version you supply, and for updating it. This is the ordinary
consequence of supplying your own driver, and it is the same position you are
in for the JVM and the database itself.

## Verifying it worked

On startup the server logs a connectivity check to **stderr** (stdout is
reserved for the MCP JSON-RPC stream — [ADR 0001](../adr/0001-stdio-transport.md)):

```
Db2 connectivity check: OK
```

Anything else means the driver loaded but the connection did not succeed —
check host, port, database, credentials and network path. A
`Cannot load driver class` failure instead means `loader.path` is not
reaching the JVM: check the flag's position relative to `-jar`, and that the
path exists inside the container or host where the JVM actually runs.

`scripts/smoke-test.sh` performs this check automatically against the built
jar, and resolves the driver from `DB2MCP_JDBC_DRIVER_PATH` if set.

## For the maintainer

`scripts/verify-no-jcc.sh` fails the build if an IBM driver can reach anyone
through a shipped artifact. It checks every distributable archive under
`target/`, matches driver artifacts by name anywhere inside them, and opens
nested jars to catch a driver shaded into a bundled library. It runs in CI
and as part of `scripts/verify-release.sh`, so a future dependency or plugin
change that silently re-bundles the driver fails the gate rather than
shipping.

Run it against any archive directly:

```bash
scripts/verify-no-jcc.sh path/to/artifact.jar
```
