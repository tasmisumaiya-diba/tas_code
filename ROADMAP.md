# CodeBridge roadmap

Status is based on the repository implementation and test suite, not on planned work. The conversion contract is a verifiable subset of C, C++, and Java; full language coverage is not a goal.

## Completed

- **Phase 1 — Audit and baseline:** Maven multi-module build, language modules, shared IR, web API/UI, validation, Clang frontend, and existing tests have been inspected. Baseline suite was green before this tracking update.
- **Phase 2 — Incremental Clang integration:** `/api/analyze` uses the Docker-backed libclang frontend. `clangLowering: true` optionally lowers a restricted function subset into shared IR and the common pipeline; unsupported syntax is diagnosed.
- **Phase 3 — Core conversion pipeline:** Shared IR analysis, deterministic rules, language-specific generation, diagnostics, and compiler validation are connected.
- **Phase 4 — Six conversion directions:** All six C/C++/Java directions are registered and have golden or integration coverage for a deliberately restricted function-oriented subset.
- **Phase 6 — Validation:** C/C++ syntax checks use GCC/G++; Java uses `javac`; output comparison runs in constrained Docker containers. Docker-dependent tests skip when Docker/images are unavailable.
- **Phase 7 — Frontend/documentation:** The beginner-facing UI keeps conversion and example loading prominent; compiler analysis, Docker comparison, and comparison input are in a collapsed Advanced options section. It also provides output copy/download and diagnostics. README and direction support matrix describe the implemented subset.
- **Production setup increment:** A private-host Compose profile packages the Java app and runtime JARs in a container image behind Nginx TLS and per-IP API rate limits. It requires an API bearer token, applies container limits and health checks, and keeps the app on an internal network without Docker socket access. This deliberately disables Docker-based Clang/run/compare in the deployment profile; see `docs/production-deployment.md`.

## In progress

- **Phase 2 — Clang AST lowering:** Scalar free functions, local variables, expressions, conditionals, and loop forms are supported in the opt-in route. The default C/C++ converters still use restricted parsers.
- **Phase 6 — Semantic verification:** Tests cover representative conversions and compile checks. Actual Docker runtime equivalence depends on Docker being available and does not cover all inputs.
- **Phase 8 — Production preparation:** The private-host deployment profile, setup guide, and end-to-end PowerShell smoke script are implemented. Production release still needs a real certificate, firewall configuration, reviewed pinned image digests, and execution of the smoke script on the owner host.

## Remaining work

1. Extend the shared IR and Clang lowering with typed casts/conversions, arrays, global state, records/enums, and source-level constructs, each with negative and positive tests.
2. Decide and document which default C/C++ parser cases should migrate to Clang lowering; retain the existing parser path until parity for its supported subset is demonstrated.
3. Expand Java semantic lowering and object/runtime modeling in small feature families. Prioritize only semantics that can be preserved and tested.
4. Increase per-direction compile and Docker behavior tests for supported array, I/O, recursion, and control-flow patterns; run Docker-backed checks in an environment with the required images.
5. Continue frontend/API integration tests for browser-executed behavior beyond the validation-warning, comparison-failure, and malformed-request cases now covered.
6. For production: provision the real TLS certificate, restrict the host firewall, review/pin image digests, and validate the deployment on the selected host. The optional bearer token is not a substitute for TLS, rate limits, or a trusted network. Do not expose the current server directly to the internet.

## Known bugs and risks

- No critical regression is currently known from the passing Maven suite. This is not equivalent to exhaustive semantic verification.
- Docker is not available in every development environment; Clang analysis and execution comparisons then cannot be verified live.
- The opt-in Clang lowering rejects most C/C++ constructs that the Clang parser itself can understand. In particular it does not yet model pointer/reference ownership, aggregate layout, exceptions, templates, or C++ object lifetime.
- Current default parsers recognize restricted grammars, so unsupported syntax can fail during parsing before a feature-specific semantic diagnostic is available.
- Outside the Compose profile, the standalone HTTP server does not provide TLS or rate limiting. The Compose profile supplies these at Nginx. The HTTP request size and executor queue are bounded, and Docker executions have limits, but host compiler invocation and public hostile-traffic handling have not been hardened.
- Broad CORS is intentionally absent because the UI and API are served from the same origin. A separate hosted frontend would require an explicit origin allowlist and deployment review.
- The built-in browser security headers reduce common browser-side risks. The inline UI uses a per-response nonce; this does not make the unauthenticated server safe for public exposure.

## Next priorities

1. Build/start the image with Docker Desktop, provision certificate and firewall settings, pin image digests, and execute `deploy/smoke-test.ps1` (including its optional rate-limit check) on the owner host.
2. Add browser-driven tests for warning, comparison failure, and malformed-request rendering.
3. Continue Clang-to-IR expansion from compiler-AST fixtures and Docker-backed tests in a configured environment.

## Latest verification

Full verification: the offline Maven test suite was run from the repository root. Result: **BUILD SUCCESS**, 197 tests, 0 failures, 0 errors, 5 Docker-dependent skips. The suite includes bearer-authentication tests, API warning serialization, inconclusive output comparison handling, malformed JSON and UTF-8 behavior, route limits, conversion, compiler, and sandbox tests. The web packaging command also passed, and `docker compose config --quiet` accepted the production profile. Docker Engine was unavailable, so the image was not built or launched in this environment. The release smoke script passed PowerShell syntax validation but could not be run without the deployed TLS stack.

Owner-run Docker verification: the targeted literal-output C-to-Java sandbox test passed (1 test, 0 failures, 0 skips), confirming compile/run equivalence for that sample on the owner's Docker Desktop environment.
Owner-run IntelliJ verification: project owner reports the full test suite passed with Docker available. The owner also reports the targeted Docker C-to-Java runtime equivalence test passed.
