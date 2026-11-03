# Changelog

Meaningful changes are recorded here. This project has not yet adopted tagged releases; entries describe repository work and do not imply a published version.

## 2026-11-03 — Unreleased

- Added a private-host Compose deployment: Nginx terminates TLS and rate-limits API traffic; the Java app runs non-root with container resource limits, a health probe, and no Docker socket. Added deployment instructions and ignored secret/certificate paths. Added API workflow checks for warning diagnostics, inconclusive output comparisons, malformed JSON, and UI status rendering. Docker image execution still requires owner-host verification.
- Verification: full offline Maven suite passed with 197 tests, 0 failures, 0 errors, and 5 Docker-dependent skips. The web module packaging command passed. Compose configuration validation passed; Docker Engine was unavailable, so the image itself was not built here.
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
