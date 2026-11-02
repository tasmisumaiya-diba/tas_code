# Changelog

Meaningful changes are recorded here. This project has not yet adopted tagged releases; entries describe repository work and do not imply a published version.

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
- Added an exact C sample regression test covering comments, literal `printf` calls, and `return(0)`; it generates compilable Java. The HTTP integration test now exercises the same optional fields sent by the current UI. Advanced compiler and Docker controls are tucked under a collapsed Advanced options section.
- Verification after beginner UI and request-limit hardening: `.\mvnw.cmd -o test` passed with 154 tests, 0 failures, 0 errors, and 4 Docker-dependent skips. The skipped tests require the Docker daemon and compiler runtime images.
