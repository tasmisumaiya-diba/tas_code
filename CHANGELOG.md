# Changelog

Meaningful changes are recorded here. This project has not yet adopted tagged releases; entries describe repository work and do not imply a published version.

## 2026-11-04 — Unreleased

- Java-to-C++ now maps `Math.abs` to `std::abs`, validates its numeric input, emits the required `<cmath>` include, and warns about integral minimum-value overflow.
- Java-to-C++ integral `/` and `%` reject literal zero divisors and warn when a non-literal divisor may be zero, reflecting Java's exception versus C++ undefined behavior.
- Java-to-C++ now rejects multiple `Scanner(System.in)` instances with `CB-JAVA-SCANNER-MULTIPLE-UNSUPPORTED`; Java Scanner instances can buffer the shared input independently, unlike the single C++ stream mapping.
- Java Scanner input now rejects a read after any Scanner closes `System.in`, instead of silently removing `close()` and generating a program that keeps reading. Closing after the final recognized read remains supported.
- Java-to-C++ now accepts side-effect-free boolean `&`, `|`, and `^` expressions. It rejects forms with calls, assignments, or updates in either operand because Java and C++ do not guarantee the same operand evaluation order.
- Java `>>>` now receives the located `CB-JAVA-UNSIGNED-SHIFT-UNSUPPORTED` diagnostic instead of falling through to a generic expression parse error.
- Conversion pipeline diagnostics now block output only when severity is `ERROR`; warnings can accompany successful generated code and flow to the existing UI warning state. This enables Java-to-C++ semantic warnings without discarding otherwise generated output.
- Java-to-C++ local objects can now read/write public primitive fields from surrounding functions. Field types are resolved for assignment compatibility; unknown, private, or protected external fields produce a located unsupported diagnostic. The Java/C++ Maven modules compile successfully after this change; focused behavior tests were not run in this step.
- Instance methods may now spell supported same-class field and method access explicitly as `this.field` and `this.method()`. The lowering removes the redundant receiver while preserving source offsets; bare `this` and `super` remain unsupported.
- Calls written as `ClassName.staticMethod()` and references to known static fields written as `ClassName.staticField` now lower to their existing free-function/global representations. Compilation succeeded; focused behavior verification remains outstanding.
- Supported `System.out.print`, `println`, and `printf` calls are now allowed in otherwise supported primitive instance methods and use the existing output conversion rules. Other qualified library access remains rejected.
- Java array `.length` now lowers to `static_cast<int>(array.size())` in C++ and is rejected for C, whose current array model cannot preserve a runtime length. The changed Java/C++ code compiles; behavior tests remain outstanding.
- Java array indexing now emits `CB-JAVA-CPP-ARRAY-BOUNDS` when a typed array access maps to unchecked C++ vector indexing; integral-index checks still run. This warns about the Java exception-versus-C++ undefined-behavior difference instead of silently suggesting equivalent bounds behavior.
- `CB-JAVA-CPP-INTEGER-OVERFLOW-POSSIBLE` now also covers integral unary negation and increment/decrement in Java-to-C++, where Java wraparound is not preserved by C++ signed arithmetic.
- Java-to-C++ integral `<<` and `>>` now emit `CB-JAVA-CPP-SHIFT-DISTANCE` because Java masks oversized or negative shift distances while C++ requires a valid non-negative count.
- Shift expression type resolution now follows the promoted left operand, instead of incorrectly widening to the shift-count type. This matches Java and C++ shift result typing and improves subsequent compatibility checks.

## 2026-11-03 — Unreleased

- Java-to-C++ now supports local stack construction of the supported class using a no-argument constructor, plus calls to supported instance methods on those local objects. Object alias/copy operations and object-valued method signatures are rejected to avoid Java-reference/C++-value semantic mismatches.

- C generation now emits function declarations before definitions, enabling direct and mutual recursion in supported C programs. Recursion depth and termination limits are documented.
- Function-call analysis now retains same-name overloads and selects a viable supported signature by arity and argument types, including recursive and mutually recursive calls; ambiguous or unmatched overload calls receive a diagnostic.

- Java-to-C++ runtime compatibility now maps Java `String.length()` through a UTF-16 code-unit helper for well-formed UTF-8, and emits located warnings for possible signed integer overflow, Java `char` width mismatches, and floating-point Math rounding differences. The support guide records remaining ABI, encoding, object-lifetime, and library limits.

- Java-to-C++ console input now lowers `Scanner.nextLine()` to `std::getline`, supports multiple local Scanner variables over `System.in`, and retains explicit rejection for other unsupported Scanner calls. Added data-driven C++ mappings for `Math.sqrt`, `pow`, `floor`, and `ceil`.

- Java-to-C++ Scanner input now covers matching `int`, `long`, `float`, and `double` reads plus `String` token reads via `next()`; native output supports these values and booleans, with `std::boolalpha` preserving Java `true`/`false` text. C-target String input and line-based `nextLine()` remain explicitly unsupported.
- Java-to-C++ basic I/O now handles matching `Scanner.nextInt()`, `nextLong()`, `nextFloat()`, and `nextDouble()` declarations with native `std::cin` reads. Output supports primitive numeric types, strings, chars, and booleans; generated boolean literals use `true`/`false`, and printed booleans use `std::boolalpha`. Java-long Scanner input is rejected for C due to platform-dependent C `long` width. Focused C++ compile tests pass.
- Java instance methods with distinct supported primitive signatures are verified to retain overloads in generated C++ and compile successfully; documented the supported boundary.
- C++ string-prefixed `std::cout` chains with supported integer, character, and string operands ending in `std::endl` now generate one Java `System.out.println` expression, preserving left-to-right evaluation and avoiding split `print` calls. The focused conversion test verifies generated Java compiles.
- Java-to-C++ now lowers primitive instance methods into shared class IR alongside primitive fields, and the C++ generator preserves member access and emits methods in the class. The lowering rejects unsupported signatures and qualified/library calls with located diagnostics. Added generated-C++ compile/runtime coverage and updated the Java-to-C++ support matrix. `CodeBridgePipelineTest` passed with 133 tests and 5 Docker-dependent skips; the new Docker-backed class behavior assertion still needs an owner-host run.
- Added a private-host Compose deployment: Nginx terminates TLS and rate-limits API traffic; the Java app runs non-root with container resource limits, a health probe, and no Docker socket. Added deployment instructions and ignored secret/certificate paths. Added API workflow checks for warning diagnostics, inconclusive output comparisons, malformed JSON, and UI status rendering. Docker image execution still requires owner-host verification.
- Verification: full offline Maven suite passed with 197 tests, 0 failures, 0 errors, and 5 Docker-dependent skips. The web module packaging command passed. Compose configuration validation passed; Docker Engine was unavailable, so the image itself was not built here.
- Added an end-to-end PowerShell release smoke script that verifies TLS health/UI, bearer-authenticated conversion and Java compile validation, malformed JSON rejection, and optional Nginx rate limiting. The script syntax was validated; live execution requires Docker Desktop, a running stack, and a certificate.
- Finished wiring optional API bearer authentication: `CODEBRIDGE_AUTH_TOKEN` protects conversion and analysis APIs, `/api/health` advertises whether auth is required, and the UI prompts without CodeBridge persisting the token. Tokens are length/character validated and redacted from configuration text; startup warnings and README state TLS is still required for remote access. All six HTTP/configuration auth regression tests pass; see the roadmap for the full-suite result.
- HTTP API now rejects malformed UTF-8 request bodies with a clear 400 response instead of silently replacing invalid byte sequences before JSON parsing; added coverage for both `/api/convert` and `/api/analyze`.
- Java sandbox execution now selects the public top-level type when choosing the `.java` filename, avoiding false compile failures when nested or helper classes appear first; tests cover nested types, records, fallback selection, and comments.
- Java sandbox execution now launches packaged programs using their fully qualified main-class name; added coverage for a package declaration that appears after a misleading comment.
- Sandbox output capture now marks truncation even when the cap is reached exactly and avoids retaining half of a UTF-16 surrogate pair; output comparison already treats marked output as inconclusive.
- Docker subprocess stdin is now written concurrently with process waiting, so a child that does not consume a large input cannot block past the execution timeout; a child-process regression test exercises a full stdin pipe.
- Native compiler validation now streams source asynchronously while enforcing its process timeout, and timeout/error cleanup terminates compiler descendants as well as the compiler process.
- In-memory Java compiler validation now chooses the source filename from the public top-level type, allowing valid package and non-default public class names to compile successfully.
- Verification: full `.\mvnw.cmd -o test` passed across all modules with 187 tests, 0 failures, 0 errors, and 5 Docker-dependent skips.
- Verification: targeted HTTP security tests passed (5 tests), validation tests passed (13 tests), and full `.\mvnw.cmd -o test` passed across all modules with 185 tests, 0 failures, 0 errors, and 5 Docker-dependent skips.

## 2026-11-02 — Unreleased

- Added `ROADMAP.md` with completed phases, current limitations, production risks, and next priorities.
- Added environment-based HTTP bind settings through `CODEBRIDGE_BIND_ADDRESS` and `CODEBRIDGE_PORT`; retained the loopback/8080 defaults and existing command-line port override.
- Added configuration tests for defaults, environment settings, CLI precedence, and invalid ports.
- Expanded README setup and production notes, including same-origin CORS behavior and the warning against exposing the unauthenticated server publicly.
- Added a loopback-only HTTP health test using an ephemeral configured port; no external interface is exposed by this test.
- Added restrictive HTTP security headers, per-response nonce CSP for the inline UI, and tests that verify same-origin behavior without CORS headers.
- API handlers now return 404 for unregistered suffix paths instead of accepting JDK HttpServer prefix matches; added route-boundary regression coverage.
- Startup prints a warning when configured to listen on a non-loopback address without authentication or TLS.
- API endpoints cap request-body reads at 1,000,001 bytes and return 413 when requests exceed the 1,000,000-byte limit; added chunked-body coverage.
- Startup now explains when the configured address or port cannot be bound, with a regression test for an occupied port.
- HTTP server shutdown now also stops its fixed worker executor; a regression test verifies worker threads exit.
- Added an exact C sample regression test covering comments, literal `printf` calls, and `return(0)`; it generates compilable Java. The HTTP integration test now exercises the same optional fields sent by the current UI. Advanced compiler and Docker controls are tucked under a collapsed Advanced options section. Added a Docker output-equivalence case for the literal-output C-to-Java sample.
- Verification after HTTP lifecycle hardening: `.\mvnw.cmd -o test` passed with 180 tests across all Maven modules, 0 failures, 0 errors, and 5 Docker-dependent skips. The skipped tests require the Docker daemon and compiler runtime images.
- Added validation tests confirming oversized source and test input are rejected before Docker is invoked.
- Owner-run verification: the targeted Docker C-to-Java literal-output equivalence test passed (1 test, 0 failures, 0 skips); the owner reports the full IntelliJ test suite passes with Docker available.
- Java-to-C++ now preserves explicit empty no-argument constructors on primitive-field classes and retains their public/protected/private access. Parameterized constructors, package-private constructors, and constructors containing initialization logic receive located diagnostics; added positive and negative pipeline tests.
- C++ generation no longer emits an unnecessary `main` forward declaration, and C string literals used as `printf` formats no longer trigger an unused `<string>` header. Added a C-to-C++ output/compile regression test.
- C-to-C++ now rewrites simple side-effect-free `printf` calls with literal `%d`/`%%` formats to idiomatic `std::cout`, adds `<iostream>`, and drops a no-longer-needed `<stdio.h>`. Complex formats and side-effecting arguments retain `printf`. C++ output also omits unused `<string>` inference and redundant `main` prototypes. Focused conversion and compiler-validation tests passed.
- C-to-C++ basic formatted output now also maps `%s` for string literals and `%c` for character values, and emits line breaks as `\\n` character output. Numeric conversions are limited to integer types where stream output matches `printf`; nontrivial format flags and unsafe conversions stay as `printf`.
- Java `Scanner(System.in)` with `nextInt()` local initializers and optional standalone `close()` now lowers to C `scanf` or C++ `std::cin`; Java `println` maps to target-specific C/C++ output. Scanner recognition tolerates compact statements and comments after Javac validates the source, and an unused Scanner import no longer blocks conversion. Other Scanner methods are rejected with a located diagnostic. Added C/C++ compile tests and optional Docker runtime-equivalence coverage using the Even/Odd sample.
- C `printf("%d\\n", intExpression);` now becomes Java `System.out.println(intExpression);` for standalone print statements, while formatted calls without a trailing newline and `printf` return-value uses preserve their existing behavior. Added a generated-Java compile regression and updated the C-to-Java support matrix.
- C++ `std::cout << value << std::endl` now becomes one Java `System.out.println(value)` for supported integer, character, and string values. Multi-value stream chains retain ordered print calls; added Java compile coverage.
