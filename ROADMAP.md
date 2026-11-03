# CodeBridge roadmap

Status is based on the repository implementation and test suite, not on planned work. The conversion contract is a verifiable subset of C, C++, and Java; full language coverage is not a goal.

## Completed

- **Phase 1 — Audit and baseline:** Maven multi-module build, language modules, shared IR, web API/UI, validation, Clang frontend, and existing tests have been inspected. Baseline suite was green before this tracking update.
- **Phase 2 — Incremental Clang integration:** `/api/analyze` uses the Docker-backed libclang frontend. `clangLowering: true` optionally lowers a restricted function subset into shared IR and the common pipeline; unsupported syntax is diagnosed.
- **Phase 3 — Core conversion pipeline:** Shared IR analysis, deterministic rules, language-specific generation, diagnostics, and compiler validation are connected.
- **Recursive functions:** The supported function subset handles self and mutual recursion; C and C++ generators emit declarations before definitions. Stack exhaustion, nontermination, and lack of guaranteed tail-call optimization remain runtime constraints.
- **Overloaded recursive calls:** The call analyzer preserves all function signatures and resolves a unique viable overload by argument types; ambiguous or unmatched calls are diagnosed.
- **Phase 4 — Six conversion directions:** All six C/C++/Java directions are registered and have golden or integration coverage for a deliberately restricted function-oriented subset.
- **Java-to-C++ object subset:** Primitive-field classes can contain supported primitive instance methods, distinct primitive-signature overloads, and explicit empty no-argument constructors. C++ generation preserves member access. Constructors with parameters or initialization behavior remain diagnosed. Qualified Java library/member calls remain diagnosed. See `docs/java-to-cpp-support.md`.
- **Java console input subset:** A single `Scanner(System.in)` with `int` `nextInt()` reads lowers to C `scanf` or C++ `std::cin`; supported print calls generate native C/C++ output. Compact statement formatting and comments are covered. Other Scanner methods remain explicitly unsupported.
- **Java-to-C++ basic console I/O:** Matching Scanner `int`, `long`, `float`, and `double` token reads plus String `next()`/`nextLine()` lower to `std::cin`/`std::getline`; multiple Scanner variables initialized from `System.in` are rejected because independent Java buffering is not preserved. Reads after `close()` also receive a diagnostic. `Math.sqrt/pow/floor/ceil` map through the runtime mapping data. Print/println supports primitive values, strings, chars, and booleans. Java `long` and String Scanner input remain unavailable for C; unsupported Scanner methods are diagnosed.
- **Java-to-C++ runtime differences:** `String.length()` counts UTF-16 units for valid UTF-8 input; possible Java integer overflow, Java `char` width mismatches, and Math rounding differences produce located warnings. The support guide documents ABI and encoding assumptions plus unsupported object lifetime semantics.
- **Java-to-C++ integer overflow warnings:** The warning now also covers integral unary negation and increment/decrement, where Java wraparound and C++ signed-overflow behavior differ.
- **Java-to-C++ shift-distance warning:** Integral `<<` and `>>` now warn about Java's masked shift distance versus C++'s valid-count requirement.
- **Warning-preserving conversion pipeline:** Analyzer and semantic warnings no longer suppress generated code; errors still stop conversion.
- **Java-to-C++ objects and arrays:** Basic local `new ClassName()` lowers to a stack object; supported instance-method calls, public primitive field reads/writes, explicit `this.member`, basic `System.out` calls inside instance methods, class-qualified static members, and Java array `.length` to C++ `static_cast<int>(vector.size())` are lowered. Array length is rejected for C. Typed Java array indexing emits `CB-JAVA-CPP-ARRAY-BOUNDS` because generated C++ uses unchecked `std::vector::operator[]`. Aliasing/copying, object-valued signatures, bare `this`, and private/protected external field access remain rejected or unsupported. The latest changes compile-check; behavior tests remain to be run.
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

Java-to-C++ basic input/output verification (2026-11-04): the complete `CodeBridgePipelineTest` class passed with 144 tests, 0 failures, 0 errors, and 7 Docker-dependent skips; targeted follow-up tests for long Scanner width diagnostics and primitive boolean literal output also passed. `codebridge-web` packaging passed.

Full verification (2026-11-03): the offline Maven integration-test reactor completed with **BUILD SUCCESS**, 177 tests, 0 failures, 0 errors, and 7 Docker-dependent skips. This run covered API/security workflows, conversion, compiler checks, and sandbox-service tests. `codebridge-web` packaging also passed. The Java Scanner-to-C/C++ runtime-equivalence test is present but skipped because the required `gcc:14` sandbox image is unavailable in this environment; owner-host Docker verification is recorded below.

Java-to-C++ instance-method change verification: the full `CodeBridgePipelineTest` class passed (133 tests, 0 failures, 0 errors, 5 Docker-dependent skips). Focused object-method tests passed; the Docker execution assertion is skipped in this Codex process because its Docker named-pipe access is restricted. Owner-run Docker execution is still required for this added class-method example.

Owner-run Docker verification: the targeted literal-output C-to-Java sandbox test passed (1 test, 0 failures, 0 skips), confirming compile/run equivalence for that sample on the owner's Docker Desktop environment.
Owner-run IntelliJ verification: project owner reports the full test suite passed with Docker available. The owner also reports the targeted Docker C-to-Java runtime equivalence test passed.
