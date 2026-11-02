# CodeBridge roadmap

Status is based on the repository implementation and test suite, not on planned work. The conversion contract is a verifiable subset of C, C++, and Java; full language coverage is not a goal.

## Completed

- **Phase 1 — Audit and baseline:** Maven multi-module build, language modules, shared IR, web API/UI, validation, Clang frontend, and existing tests have been inspected. Baseline suite was green before this tracking update.
- **Phase 2 — Incremental Clang integration:** `/api/analyze` uses the Docker-backed libclang frontend. `clangLowering: true` optionally lowers a restricted function subset into shared IR and the common pipeline; unsupported syntax is diagnosed.
- **Phase 3 — Core conversion pipeline:** Shared IR analysis, deterministic rules, language-specific generation, diagnostics, and compiler validation are connected.
- **Phase 4 — Six conversion directions:** All six C/C++/Java directions are registered and have golden or integration coverage for a deliberately restricted function-oriented subset.
- **Phase 6 — Validation:** C/C++ syntax checks use GCC/G++; Java uses `javac`; output comparison runs in constrained Docker containers. Docker-dependent tests skip when Docker/images are unavailable.
- **Phase 7 — Frontend/documentation:** The beginner-facing UI keeps conversion and example loading prominent; compiler analysis, Docker comparison, and comparison input are in a collapsed Advanced options section. It also provides output copy/download and diagnostics. README and direction support matrix describe the implemented subset.
- **Production setup increment:** Server bind address and port are configurable using `CODEBRIDGE_BIND_ADDRESS` and `CODEBRIDGE_PORT`; loopback remains the safe default. Non-loopback binds print a warning about missing authentication/TLS. Same-origin UI/API needs no permissive CORS policy. Responses use a nonce-based CSP for the inline UI plus no-store, no-sniff, frame, referrer, and permissions headers. Closing the server also shuts down its HTTP worker pool. Configuration, HTTP, and worker lifecycle tests pass.

## In progress

- **Phase 2 — Clang AST lowering:** Scalar free functions, local variables, expressions, conditionals, and loop forms are supported in the opt-in route. The default C/C++ converters still use restricted parsers.
- **Phase 6 — Semantic verification:** Tests cover representative conversions and compile checks. Actual Docker runtime equivalence depends on Docker being available and does not cover all inputs.
- **Phase 8 — Production preparation:** Runtime bind settings exist, but the service is not ready for public exposure.

## Remaining work

1. Extend the shared IR and Clang lowering with typed casts/conversions, arrays, global state, records/enums, and source-level constructs, each with negative and positive tests.
2. Decide and document which default C/C++ parser cases should migrate to Clang lowering; retain the existing parser path until parity for its supported subset is demonstrated.
3. Expand Java semantic lowering and object/runtime modeling in small feature families. Prioritize only semantics that can be preserved and tested.
4. Increase per-direction compile and Docker behavior tests for supported array, I/O, recursion, and control-flow patterns; run Docker-backed checks in an environment with the required images.
5. Improve frontend/API integration tests for validation states, warning states, comparison failures, and malformed requests.
6. For production: choose a hosting model, package the Java service, define secrets/configuration and health checks, add authentication or an explicit trusted-network boundary, set reverse-proxy TLS/rate limits, and specify how optional Docker features are isolated. Do not expose the current server directly to the internet.

## Known bugs and risks

- No critical regression is currently known from the passing Maven suite. This is not equivalent to exhaustive semantic verification.
- Docker is not available in every development environment; Clang analysis and execution comparisons then cannot be verified live.
- The opt-in Clang lowering rejects most C/C++ constructs that the Clang parser itself can understand. In particular it does not yet model pointer/reference ownership, aggregate layout, exceptions, templates, or C++ object lifetime.
- Current default parsers recognize restricted grammars, so unsupported syntax can fail during parsing before a feature-specific semantic diagnostic is available.
- The web service has no authentication or TLS. The HTTP request size and executor queue are bounded, and Docker executions have limits, but host compiler invocation and the unauthenticated endpoint have not been hardened for hostile public traffic.
- Broad CORS is intentionally absent because the UI and API are served from the same origin. A separate hosted frontend would require an explicit origin allowlist and deployment review.
- The built-in browser security headers reduce common browser-side risks. The inline UI uses a per-response nonce; this does not make the unauthenticated server safe for public exposure.

## Next priorities

1. Choose the supported production hosting boundary (private network/reverse proxy) before enabling non-loopback binds.
2. Reassess authentication, rate limiting, TLS proxying, and Docker socket boundaries before adding a production container/deployment manifest.
3. Continue Clang-to-IR expansion from compiler-AST fixtures and Docker-backed tests in a configured environment.

## Latest verification

Command: `.\mvnw.cmd -o test` from the repository root. Result: **BUILD SUCCESS**, 187 tests, 0 failures, 0 errors, 5 skips. The five skipped checks require Docker and the `gcc:14` or `eclipse-temurin:21-jdk` images. Across all Maven modules, 187 tests ran with 0 failures and 5 Docker-dependent skips. HTTP route boundary/request-size/security-header tests, malformed UTF-8 rejection, collapsed-options UI assertions, bind-failure diagnostics, Java compiler validation for package and public-type filenames, exact C-to-Java sample generation/compilation, Java sandbox filename and packaged main-class selection, output truncation markers/Unicode safety, bounded compiler and sandbox stdin writes, subprocess timeout while stdin is back-pressured, server configuration/worker shutdown, and Docker source/input limit tests passed. A Docker-backed C-to-Java output-equivalence case was added but skipped because Docker is unavailable here.

Owner-run Docker verification: the targeted literal-output C-to-Java sandbox test passed (1 test, 0 failures, 0 skips), confirming compile/run equivalence for that sample on the owner's Docker Desktop environment.
Owner-run IntelliJ verification: project owner reports the full test suite passed with Docker available. The owner also reports the targeted Docker C-to-Java runtime equivalence test passed.
