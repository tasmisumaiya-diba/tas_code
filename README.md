# tasCode / CodeBridge

CodeBridge is a local web application that converts supported subsets of C, C++, and Java using a deterministic shared-IR pipeline. It reports unsupported syntax instead of intentionally dropping it. Conversion success means the converter produced output for its implemented subset; it does not by itself prove that source and target behavior are equivalent.

The application serves a browser UI and HTTP API from one Java process. It does not currently have a separate frontend build, framework, or source-file upload control. Use the editor to paste code, convert it, inspect diagnostics, and copy or download output.

## Project overview and architecture

```text
Browser UI (static HTML/CSS/JavaScript)
              │ HTTP
              ▼
CodeBridgeHttpServer ── /api/convert, /api/analyze, /api/health
              │
              ├── Language parser → shared typed IR → analysis/rules → language generator
              ├── optional compiler syntax validation (does not run the program)
              └── optional Docker execution and output comparison
```

The default conversion path uses the project's restricted language parsers. An optional Clang frontend analyzes C17/C++23 source and can lower a documented small subset of its AST into the shared IR using `clangLowering: true`. Clang parsing and the default C/C++ conversion path are separate today; the AST path is not a full replacement for the default parsers.

| Module | Responsibility |
|---|---|
| `codebridge-core` | Shared IR, diagnostics, language interfaces, analyzers, preprocessing, and conversion pipeline |
| `codebridge-lang-c` | Restricted C parser/module and C generation support |
| `codebridge-lang-cpp` | Restricted C++ parser/module and C++ generation support |
| `codebridge-lang-java` | Java frontend/indexing, supported-subset parsing and generation |
| `codebridge-frontend-clang` | Docker-backed libclang analysis and incremental Clang AST-to-IR lowering |
| `codebridge-rules` | Cross-language rewrites and data-driven standard-library mappings |
| `codebridge-validation` | Compiler checks, isolated Docker execution, and output comparison |
| `codebridge-web` | Local HTTP server and static browser interface |
| `codebridge-integration-tests` | Cross-module, golden, diagnostic, compiler, and sandbox tests |

The language modules depend on the shared core, not on one another. The pipeline separates parsing, IR analysis/transformation, generation, and validation. Some language mappings remain deliberately narrow. The hand-written subset parsers are not full C/C++/Java grammars; regex-based checks are not substitutes for the language parser.

## Technology stack

| Area | Current implementation |
|---|---|
| Language/runtime | Java 21 (JDK) |
| Build | Maven multi-module project with Maven Wrapper |
| C/C++ semantic analysis | Clang/libclang in a locally built Docker image |
| Java syntax frontend | JDK compiler tree API |
| Web backend | JDK `HttpServer`; no Spring Boot dependency in the current app |
| Web frontend | Static HTML, CSS, and JavaScript; no separate Node/Next.js build |
| Tests | JUnit 5, integration/golden tests, compiler checks |
| C/C++ compiler validation | `gcc` and `g++` from `PATH` |
| Java compiler validation | `javac` from the JDK |
| Program execution comparison | Docker containers with resource limits and networking disabled |

## Requirements and installation

### Required for build and web app

- JDK 21. Set `JAVA_HOME` to the JDK and ensure `java` and `javac` are on `PATH`.
- Windows PowerShell or a POSIX shell.
- The included Maven Wrapper downloads/runs the Maven version configured by the wrapper; a separate Maven installation is not required. The first build may need network access to download Maven and project dependencies. Use `-o` only after dependencies are present locally.

Check the JDK:

```powershell
java -version
javac -version
```

### Required for compiler validation and tests

C/C++ compilation checks require `gcc` and `g++` on `PATH`; ordinary conversion and starting the UI do not. On Windows, install an x64 MinGW-w64 toolchain (for example, MSYS2 UCRT64), install its GCC package, and add its `ucrt64\bin` directory to `PATH`. Open a new terminal and verify:

```powershell
gcc --version
g++ --version
```

A JDK is used to compile generated Java. Compiler validation checks syntax; it does not execute the source or generated program.

### Optional Docker features

Docker Desktop with its Linux engine is needed for:

- The Clang analysis frontend (`/api/analyze`, `clangPreflight`, and `clangLowering`).
- Runtime/output equivalence checks (`verifyOutput: true`).

Start Docker Desktop and confirm `docker version` shows both Client and Server. For output comparison, pull the execution images:

```powershell
docker pull gcc:14
docker pull eclipse-temurin:21-jdk
```

For Clang analysis, build the frontend image once from the repository root:

```powershell
docker build -f codebridge-frontend-clang/docker/Dockerfile -t tascode-clang-frontend:1 codebridge-frontend-clang/docker
```

CodeBridge does not pull images automatically. Clang parses source in a constrained container and does not run submitted programs. Only headers available in that image are present; arbitrary include directories and build-system options are not accepted.

## Development setup

From the project root, build the web app and its dependent modules:

```powershell
.\mvnw.cmd -pl codebridge-web -am package
```

Run the local UI and API:

```powershell
.\run-codebridge.ps1
```

Open [http://127.0.0.1:8080](http://127.0.0.1:8080). To select a different port, pass it through the helper script:

```powershell
.\run-codebridge.ps1 9090
```

If the UI reports `Unknown field: clangLowering`, the browser is talking to an older CodeBridge server. Rebuild and restart the server, then open the URL for that exact port; if port 8080 is already occupied, either keep using the existing server or start on a new port and browse to that port.

The server binds to loopback (`127.0.0.1`) by default. The UI and backend are served by the same process. Stop it with Ctrl+C in the terminal running the server. To run directly from IntelliJ, import the root `pom.xml`, select JDK 21 as the project SDK, build the Maven modules, and run `com.tascode.codebridge.web.CodeBridgeHttpServer`; it uses port 8080 unless a port argument is supplied.

### Runtime configuration

The server reads these environment variables; the optional command-line port overrides `CODEBRIDGE_PORT`:

| Variable | Default | Purpose |
|---|---|---|
| `CODEBRIDGE_BIND_ADDRESS` | `127.0.0.1` | Network interface/address to bind |
| `CODEBRIDGE_PORT` | `8080` | HTTP listening port (`0` may be used for an ephemeral port in tests) |

PowerShell example:

```powershell
$env:CODEBRIDGE_BIND_ADDRESS = '127.0.0.1'
$env:CODEBRIDGE_PORT = '8080'
.\run-codebridge.ps1
```

For a reverse proxy on the same machine, the app can bind to `127.0.0.1`; let the proxy terminate TLS and forward requests to CodeBridge. If a container or separate proxy must reach it, set the bind address to the required private interface (often `0.0.0.0`) and restrict network access at the host/firewall. Do not expose this unauthenticated server directly to the public internet. The browser UI and API share the same origin, so the current deployment does not need cross-origin CORS access; no permissive CORS policy is enabled.

At startup, CodeBridge prints a warning to standard error if it binds to a non-loopback address because the HTTP service has no authentication or TLS.

HTTP responses include `nosniff`, frame denial, no-referrer, no-store caching, and a restrictive content security policy. The UI's inline script and style use per-response nonces. These browser protections do not replace authentication, TLS, a reverse proxy, or network restrictions.

## HTTP API

- `GET /api/health` reports service status and version.
- `POST /api/convert` converts source using `from`, `to`, and `source`.
- `POST /api/analyze` returns a Clang semantic model for C or C++ source; it requires Docker and the local Clang image.

Example PowerShell request:

```powershell
$body = @{
  from = 'c'
  to = 'cpp'
  source = 'int add(int a, int b) { return a + b; }'
  validate = $true
} | ConvertTo-Json
Invoke-RestMethod -Method Post -Uri http://127.0.0.1:8080/api/convert `
  -ContentType 'application/json' -Body $body
```

Both JSON endpoints cap request reads at 1,000,001 bytes and return HTTP 413 when the 1,000,000-byte request limit is exceeded.

Optional `/api/convert` JSON fields:

| Field | Meaning |
|---|---|
| `validate` | Compile-check the generated target (`gcc`/`g++` or `javac`); does not execute it |
| `verifyOutput` | Compile and run both programs in Docker, then compare stdout and exit code |
| `input` | Standard input passed to both programs during output comparison |
| `clangPreflight` | Ask Clang to check C/C++ source, then use the existing default conversion parser |
| `clangLowering` | Use the incremental Clang AST-to-IR subset for C/C++ conversion |

A successful conversion returns `success: true` and generated `output`. Rejected source returns `success: false`, diagnostics with stable codes/locations where available, and no generated output. If compilation validation is unavailable, the UI reports that separately from conversion failure. Warnings are displayed as warnings and are not hidden. Output comparison reports matching, different, or inconclusive results separately from conversion status.

Output comparison currently expects runnable programs with a C/C++ zero-argument integer `main` or Java `main(String[] args)`. Java command-line arguments, files, and network access are outside the comparison contract. Compilation success alone does not establish semantic equivalence.

## Supported conversion directions

All six directions are registered:

- C → C++
- C → Java
- C++ → C
- C++ → Java
- Java → C
- Java → C++

The converters support language subsets rather than every valid source program. See the [six-direction feature support matrix](docs/conversion-support-matrix.md), [C-to-C++ audit](docs/c-to-cpp-support.md), [Java-to-C++ audit](docs/java-to-cpp-support.md), and [Clang frontend guide](docs/clang-semantic-frontend.md) for the current boundaries.

## Examples

### Successful subset conversion

Input (C):

```c
int add(int left, int right) {
    return left + right;
}
```

Select **C → C++** (or use the API example above). The result is a C++ function with the same supported scalar operations. Check **Compile-check output** to ask `g++` to validate the generated syntax.

Input (Java):

```java
class Calculator {
    public static int add(int left, int right) {
        return left + right;
    }
}
```

Select **Java → C++**. This static primitive method is inside the supported subset and should produce a C++ free function.

### Unsupported conversion

Input (C):

```c
int read_value(int *value) {
    return *value;
}
```

A C → Java request is rejected with a pointer-related diagnostic (for the current C parser, `CB-C-POINTER-UNSUPPORTED`). The converter does not reinterpret pointer aliasing as a Java object reference.

Likewise, Java inheritance and C++ inheritance are rejected where the target/object model is not implemented. For example, `class Child extends Parent { public static int value() { return 1; } }` is rejected for Java → C++ with `CB-JAVA-INHERITANCE-UNSUPPORTED`.

## Testing and verification

Run all Maven tests from the repository root:

```powershell
.\mvnw.cmd test
```

Useful focused commands:

```powershell
.\mvnw.cmd -pl codebridge-frontend-clang -am test
.\mvnw.cmd -pl codebridge-integration-tests -am test
.\mvnw.cmd -pl codebridge-web -am package
```

Tests cover positive conversions, negative diagnostics, golden examples for all six directions, parser/IR behavior, round trips, edge cases such as recursion and nested control flow, compiler validation, and Docker-based output comparison. Docker-dependent tests are skipped when Docker or required images are unavailable. A parser/generator test is not proof of behavior equivalence; use the opt-in Docker comparison for supported runnable examples. Never execute arbitrary submitted programs directly on the web server.

## Known limitations

- The default C and C++ converters use a restricted hand-written grammar, not full compiler frontends. Clang is an optional analyze/preflight path, and `clangLowering` currently translates only a limited scalar free-function subset. It does not yet represent pointers, aggregates, global state, classes, exceptions, templates, or all AST constructs in the shared IR.
- Java syntax can be parsed/indexed more broadly than it can be converted. Generics, collections, lambdas, exceptions, general object allocation, inheritance, and most instance behavior remain unsupported.
- C/C++ pointers and references, global state, structs/unions, function pointers, `switch`, `goto`, and arbitrary preprocessor behavior are not generally translated.
- Java object identity, null/GC behavior, UTF-16 details, C++ deterministic destruction/RAII, and C undefined or implementation-defined behavior can differ fundamentally; unsupported cases should be diagnosed rather than guessed.
- Compiler checks validate generated syntax and types only. Behavioral equivalence requires executable entry points and the Docker comparison path; even then it compares stdout and exit status for the supplied input, not every possible input.
- There is no file upload control, authentication, or public hosting configuration. The server is intended for local use and binds to loopback by default.
- Environment-based bind configuration is available, but production deployment is not complete: there is no authentication, TLS termination, reverse-proxy configuration, container image for the Java service, or orchestrator/service definition. Keep it behind a trusted reverse proxy/network until those pieces are designed and verified.

## Development rules

Preserve working behavior; keep parsing, semantic analysis, IR lowering, generation, and validation separated; prefer data-driven cross-language mappings; keep the converter deterministic; return stable diagnostics for unsupported semantics; and add positive, negative, compiler, and behavior tests before claiming feature support. Do not use regular expressions as a replacement for a real language parser, silently discard syntax, weaken tests, or run arbitrary source programs on the server without isolation. Update this README and the relevant support audit whenever actual implementation support changes.

## Extending CodeBridge

See [docs/adding-language.md](docs/adding-language.md) for the module, IR, rule registration, capability, and testing workflow for adding another language.
