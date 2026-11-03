# CodeBridge Completion Plan and Repository Audit

**Audit date:** 2026-11-04  
**Scope:** Read-only technical audit plus this document. No implementation files were changed for this audit. Existing uncommitted repository changes were present and were left untouched.

## Executive summary

CodeBridge is a working Maven multi-module application with a static browser UI, a Java HTTP server, a shared IR, six registered conversion directions, compiler validation, and optional Docker-based Clang analysis and program comparison. It is a **restricted-subset converter**, not a general-purpose C/C++/Java compiler or source-to-source translator.

The primary architecture gap is that the normal C/C++ conversion route still uses a hand-written subset parser; the compiler-grade Clang route is optional and lowers only a narrow scalar-function subset. Java uses javac's public tree API for parsing and attribution, but the active conversion lowering still uses source-text normalization and a restricted parser. The shared IR has useful function, array, enum, and partial object-model nodes, but does not encode many essential language semantics. Consequently, all six directions work only for documented, tested subsets.

The offline full Maven test run completed successfully: **212 tests, 0 failures, 0 errors, 7 skipped**. The seven skips are Docker-dependent behavior/compile checks and were skipped because the Docker engine/images were unavailable to this audit process. A prior user-provided targeted test run reported `CodeBridgePipelineTest`: **146 tests, 0 skipped**, with Docker available in the user's environment; that confirms those cases passed in that run, but is distinct from the current audit run.

## Current architecture

| Module | Observed responsibility |
|---|---|
| `codebridge-core` | Versioned shared IR, diagnostics, parsers/generators interfaces, analyzers, preprocessing, and the conversion pipeline |
| `codebridge-lang-c` | C-specific restricted parser wrapper and C module/generator registration |
| `codebridge-lang-cpp` | C++ restricted parser wrapper and C++ module/generator registration |
| `codebridge-lang-java` | javac tree frontend/indexing, Java parser/lowering adapter, Java module/generator registration |
| `codebridge-frontend-clang` | Docker-backed Clang/libclang semantic frontend, AST decoder, and incremental AST-to-IR lowering |
| `codebridge-rules` | Shared conversion rules and runtime/library mappings |
| `codebridge-validation` | Compiler checks, Docker sandbox execution, and stdout/exit-code comparison |
| `codebridge-web` | JDK `HttpServer`, JSON API, and static HTML/CSS/JavaScript UI |
| `codebridge-integration-tests` | Cross-module tests, golden files, diagnostics, HTTP workflows, compiler checks, and sandbox tests |

The root Maven reactor targets Java 21 and contains nine modules. `CodeBridge.createPipeline()` discovers language modules and rules through `ServiceLoader`. The common route is:

```text
HTTP request or CodeBridge API
  -> C preprocessor (for C-family source)
  -> registered source parser
  -> shared Program IR
  -> symbol/type/feature/I/O checks
  -> ordered conversion rules
  -> target generator
  -> optional compiler validation or Docker output comparison
```

`/api/analyze` is a separate Clang-backed analysis route. `/api/convert` uses the restricted parsers by default. For C/C++ input, `clangPreflight` analyzes source before the normal conversion parser; `clangLowering` opts into the narrow Clang AST lowering route. Neither flag makes Clang the default conversion frontend.

The UI offers language selectors, source/output panes, copy/download controls, diagnostics, optional compile validation, optional Clang controls, and optional Docker behavior comparison. The deploy profile is deliberately private-host oriented; it does not mount the Docker socket, so Clang analysis and Docker comparison are unavailable inside that app container.

## Six-direction implementation status

“Working subset” below means the current implementation and tests cover only the listed patterns. It does not imply all valid source in that language family can be converted.

| Direction | Implemented and evidenced subset | Main gaps / risk |
|---|---|---|
| **C → C++** | Scalar free functions, supported expressions/control flow, selected one-dimensional arrays, selected enums, simple preprocessor cases, and checked console I/O mappings. C++ output can use idiomatic streams for supported mapped output. | C/C++ parser is restricted, not Clang-backed by default. Pointers, aggregates, globals, function pointers, switch/goto, broad declarations, and full preprocessing are absent. C integer ABI/undefined behavior can affect equivalence. |
| **C → Java** | Scalar function subset, supported control flow/expressions/arrays, normalized entry point, and selected `printf`/`scanf` patterns. | Pointer/address semantics, aggregate layouts, global state, C enum representation, goto, broad headers/macros, and C-vs-Java integer/character behavior are unsupported or unsafe. |
| **C++ → C** | Shared scalar free-function subset for C++ written in the subset accepted by both parsers. | This is primarily a common-syntax intersection. Streams, strings/vectors, classes, RAII/lifetimes, references, templates, exceptions, namespaces, lambdas, and most STL are not representable. |
| **C++ → Java** | Scalar functions and supported control flow; selected arrays/strings and console stream patterns; entry-point conversion; supported C++ output chains. | No general object/lifetime/template/STL/exception/reference/pointer model. C++ destruction and Java GC differ; stream formatting, array behavior, overloads, and integer semantics are constrained. |
| **Java → C** | Static/function-oriented subset, primitive values, supported statements/expressions/arrays, normalized `main`, a single supported integer Scanner input, and selected print calls. | General Java objects/classes/state, object identity, null, inheritance, exceptions, generics, collections, lambdas, and most Scanner APIs are rejected or unavailable. Java `long`, `char`, overflow, and runtime behavior need care. |
| **Java → C++** | Widest object subset: supported primitive/static functions, constrained single-class primitive fields and instance methods, local no-argument objects, limited field/method access, arrays, basic I/O, selected string/math mappings, and recursion. Some runtime mismatches generate warnings/diagnostics. | No general inheritance/interfaces, dispatch, parameterized/initializing constructors, aliases/object-valued APIs, generics, collections, exceptions, lambdas, null, switch, or arbitrary Java libraries. Java parser/indexer breadth exceeds what conversion lowering handles. |

The authoritative documented feature boundaries are in `docs/conversion-support-matrix.md`, `docs/c-to-cpp-support.md`, and `docs/java-to-cpp-support.md`. C++-to-C and the other direction-specific pages are less detailed than Java-to-C++ and C-to-C++.

## Frontend, IR, and lowering findings

### C/C++ frontend

- The active default conversion parsers are `SubsetFunctionParser` plus C/C++ wrappers. They tokenize/parse a limited grammar and include regex-based unsupported-construct guards and limited declaration/preprocessor handling.
- The Clang frontend is real compiler-backed parsing/semantic analysis for C17 and C++23 in a Docker image. Its semantic AST retains node kinds, source locations, type/canonical type spellings, value categories, symbol/reference IDs, and attributes.
- `ClangIrLowerer` currently accepts free function definitions with a narrow set of scalar types, local scalar declarations, supported control flow, and selected operators/calls. It rejects global state, aggregates, templates, namespaces, pointer/reference semantics, overloads, and many other AST forms.
- The frontend requires the exact local `tascode-clang-frontend:1` image and Docker access. Standard headers are limited to those present in the image; arbitrary compilation databases/include paths/options are not modeled.

### Java frontend

- `JavaAstFrontend` uses `JavacTask` at Java 21, parses and calls `analyze()`, and indexes trees, resolved symbols, attributed types, package/import names, and source spans.
- This semantic model is not the conversion IR. `JavaParser` performs extensive text-based screening/rewrites and feeds normalized source to the restricted function parser; separate extraction code lowers a constrained set of class fields and instance methods.
- Therefore successful javac attribution does not imply the converter understands or preserves the construct. Textual transforms and pattern checks can diverge from javac's resolved tree, scope, and overload decisions.

### Shared IR

The IR includes a `Program` with functions, includes, globals, enums, and classes; statements cover blocks, declarations, arrays, returns, conditionals, loops, break, and continue; expressions cover literals, names, calls, unary/binary operations, assignment, updates, and array indexing. There are partial class, constructor, field, method, generic type-parameter, and recursive type-reference records.

Missing or insufficiently explicit models include:

- Pointer/reference/ownership/aliasing/qualifier types, function pointers, object allocation, member selection as an expression, and symbol identity for overloaded declarations/calls.
- Switch/case, labels/goto, exceptions/cleanup/finally/try-with-resources, lambdas/captures/method references, and explicit control-flow/basic blocks.
- Enum classes, unions/struct layout, typedef/using aliases, namespaces/linkage/storage duration, template specialization/instantiation, and generic substitution semantics.
- Java nullability, object identity, array bounds exceptions, UTF-16 string/char semantics, and class initialization order as general IR semantics.
- Explicit target compatibility/semantic-difference metadata and a general IR serializer/deserializer/version migration path.

IR source spans/version checks exist, but the model is not yet a complete language-independent semantic representation. Some analyses are explicitly scoped to function-oriented syntax and do not uniformly walk all class/global nodes.

## Placeholders and incomplete seams

No broad `TODO`/`FIXME` marker cluster was found. The main incompleteness is structural rather than empty stubs:

- `clangLowering` is an explicit opt-in partial path, not the production/default C/C++ lowering route.
- The Java parser's semantic frontend is primarily an index/diagnostic source; conversion uses a separate restricted source transformation/lowering path.
- Several C/C++ declaration guards are regex-based and can identify some unsupported syntax, but they do not replace grammar-aware parse trees.
- `SubsetFunctionGenerator` has error-returning fallback paths for unmodeled IR node kinds; generation is not a substitute for validating semantic completeness before codegen.
- Module-local test coverage is uneven: language parser/generator tests are concentrated in the integration module rather than directly in the language modules. The Java and Clang frontends have their own unit tests.

## Critical architectural problems

1. **Two competing C/C++ frontend routes.** Users can get different behavior depending on the `clangLowering` flag, while ordinary `/api/convert` does not use compiler-resolved ASTs. Documentation must keep the flag's limits clear until a migration is verified.
2. **Semantic model is not the lowering input for Java.** Javac resolves symbols/types, but regex/text rewriting and a restricted parser drive conversion. Overload resolution, scopes, implicit conversions, and syntax transformations can be lost or reinterpreted.
3. **IR expressiveness is ahead of conversion coverage, but not sufficient for language semantics.** Class/type records exist, but pointer/reference ownership, exceptions, switch, lambdas, object expressions, and linkage/control-flow semantics are missing. Adding more parser acceptance before these models would risk incorrect output.
4. **Stringly typed semantics.** Many types, operator identities, helper calls, and language-specific markers are represented by strings. This makes type compatibility, overloads, conversions, and diagnostics fragile across backends.
5. **Validation levels can be confused.** Generated-code compilation only checks target syntax/type acceptance. Docker comparison tests only sample stdout and exit status for selected input; they cannot prove equivalence for all inputs. The UI/API reports these as distinct optional checks, but conversion success itself is not semantic proof.
6. **Docker environment differs by profile and runner.** Local test runs can execute Docker comparison only when Docker is reachable and both execution images are present. The private deployment intentionally omits Docker socket access, so local behavior tests do not exercise that deployment profile.
7. **Support documentation is uneven.** Java→C++ and C→C++ have detailed pages; comparable direction-specific audits for the other four routes are less complete. The roadmap's status should be checked against the currently modified source and refreshed when support changes.

## Build and test results

Command run from the repository root on 2026-11-04:

```powershell
.\mvnw.cmd -o test
```

Result: **BUILD SUCCESS**. Summed Surefire reports show **212 tests, 0 failures, 0 errors, 7 skipped** across the reactor. All modules built, including web and integration tests.

The seven skipped cases and their observed reason:

| Test | Required image reported unavailable |
|---|---|
| `dockerSandboxCanCompareCAndGeneratedJavaWhenDockerIsAvailable` | `gcc:14` |
| `recursiveNestedControlFlowAndShadowingCompileAndCompareWhenDockerIsAvailable` | `gcc:14` |
| `dockerSandboxCanCompareLiteralOutputCProgramWithGeneratedJavaWhenDockerIsAvailable` | `gcc:14` |
| `dockerSandboxCanCompareCScanfProgramWithGeneratedCppWhenDockerIsAvailable` | `gcc:14` |
| `dockerSandboxCanCompareJavaAndGeneratedCppWhenDockerIsAvailable` | `eclipse-temurin:21-jdk` |
| `dockerSandboxCanCompareJavaScannerOutputWithGeneratedCAndCppWhenAvailable` | `eclipse-temurin:21-jdk` |
| `javaPrimitiveInstanceFieldsAndMethodsRemainInsideGeneratedCppClass` | `gcc:14` |

The assumption messages instruct pulling the relevant image. In this audit process, `docker version` showed the client but failed to connect to `npipe:////./pipe/dockerDesktopLinuxEngine` with permission denied; consequently `docker image ls` could not verify images and tests treated the images as unavailable. The unit tests for Docker command construction/status handling and Clang frontend behavior all passed, but those are not live container equivalence runs.

Separately, the user previously supplied a run of `CodeBridgePipelineTest` with **146 tests, 0 failures, 0 skipped** and a successful full Maven run, which indicates Docker-dependent tests have passed in the user's Docker-enabled environment. This audit rerun did not reproduce that environment. Earlier seven skips were therefore diagnosed as environment access/image availability, not failing conversion assertions.

## Prioritized implementation roadmap and dependencies

| Priority | Work item | Why / deliverable | Depends on |
|---|---|---|---|
| **P0** | Stabilize an auditable baseline and feature contract | Record the exact build/test environment, retain current worktree edits, and keep support matrix claims tied to tests. Add direction-specific audit pages for all six routes. | None |
| **P1** | Define semantic IR foundations | Add typed declarations/references, explicit types/qualifiers, ownership/reference models, member access/object construction, linkage/storage, and semantic-difference metadata; define invariants and serialization strategy. | P0; schema decisions before parser expansion |
| **P2** | Make frontends produce common semantic input | Integrate Clang AST lowering into normal C/C++ conversion behind a controlled compatibility rollout; lower Java attributed trees into shared IR rather than re-parsing normalized text. Preserve a fallback until differential coverage is established. | P1 |
| **P3** | Repair common semantic analysis and diagnostics | Make symbol/type/control-flow analysis traverse functions, classes, fields, globals, and declarations; resolve overloaded symbols and call targets by IDs/types; validate IR between passes. | P1, partial P2 |
| **P4** | Complete soundness for existing supported subsets | Build table-driven positive/negative tests for each direction, compile generated outputs, and add Docker behavior cases for integer/string/arrays/I/O and edge conditions. Reject any syntax not represented by IR. | P1–P3; Docker images/access for runtime verification |
| **P5** | Expand features one semantic family at a time | Prioritize arrays/aggregates and globals, then object identity/inheritance, exceptions, switch, generics/collections, and lambdas. Each family needs IR, frontend lowering, rules, generators, diagnostics, and cross-direction tests before being marked supported. | P1–P4 |
| **P6** | Deployment-profile verification and release evidence | Distinguish local Docker worker from private deployment without socket; document unavailable features per profile and re-run smoke tests after image/proxy changes. | P4; deployment environment/certificates |

Core dependency chain: **semantic IR and contracts → semantic frontends → common analysis/lowering → generators → compiler and behavior verification → support claims/deployment release**. Avoid widening advertised conversion coverage before P1–P4 are sound.

## Audit conclusion

The project is buildable and has meaningful coverage for a useful subset, with especially active Java→C++ work and good diagnostics/test infrastructure. It is not complete across all six languages at full-language scope. The highest-value next step is not adding isolated syntax: it is aligning both frontends with one sufficiently semantic IR and making the default C/C++ conversion path compiler-backed, then validating the documented subset end-to-end. The seven skips in this audit were environment-gated; a separate Docker-enabled run supplied earlier by the user passed those pipeline checks.
