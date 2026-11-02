# Clang semantic frontend

The `codebridge-frontend-clang` module adds a compiler-backed syntax and semantic frontend for C17 and C++23. It uses libclang to produce a translation-unit tree that retains source locations, declaration scopes, compiler-resolved type spellings and canonical types, target triple and pointer width, declaration identifiers (USRs), scoped declarations, and resolved references. The Java model is intentionally loss-minimizing and can retain AST node kinds that the current conversion IR does not yet support.

## Build the analysis image

From the repository root, run:

```powershell
docker build -f codebridge-frontend-clang/docker/Dockerfile -t tascode-clang-frontend:1 codebridge-frontend-clang/docker
```

The image is built locally from Ubuntu packages. CodeBridge will not pull an image implicitly; the analyzer checks that this exact local image exists first.

## Analyze source through HTTP

Start the web app as usual, then POST JSON to `http://127.0.0.1:8080/api/analyze`:

```powershell
Invoke-RestMethod -Method Post -Uri http://127.0.0.1:8080/api/analyze -ContentType 'application/json' -Body (@{
  language = 'c'
  source = 'int add(int a, int b) { return a + b; }'
} | ConvertTo-Json)
```

Use `language: "cpp"` for C++23. Success returns a compiler-resolved semantic model plus diagnostics. Invalid source returns Clang diagnostics; unavailable Docker or image setup returns a service error with a build/run explanation.

The frontend starts one analysis container per request with no network, a read-only root filesystem, a read-only single-file source mount, dropped capabilities, an unprivileged user, memory/CPU/process limits, and a time limit. It runs libclang parsing only; it does not execute the submitted program. Only standard headers present in the image are available; arbitrary user include directories and build-system options are not currently accepted.

## Clang AST to shared IR: current incremental subset

The `/api/convert` request may include `"clangLowering": true` for C or C++ input. This runs Clang, lowers supported AST nodes into the existing `Program`/function/expression IR, and then runs the ordinary shared semantic checks, conversion rules, and target generator. Existing requests without this flag keep their current parser and conversion behavior. `"clangPreflight": true` remains a semantic check followed by the existing parser path.

The first lowering stage supports free function definitions with plain scalar return and parameter types (`void`, boolean, `char`, `short`, `int`, `long`, `float`, and `double`), named parameters, local scalar declarations, blocks, returns, `if`/`else`, `while`, `do`/`while`, explicit-clause `for` loops, `break`, `continue`, numeric literals, supported unary and binary arithmetic/comparison/logical operators, simple assignment to a local name, and direct function calls. Clang's resolved type and declaration-reference information is used; source ranges supply operator and literal spellings where the C API does not expose them directly. Supported include and simple object-like literal macro directives are retained through the existing preprocessor metadata pass.

The stage reports located diagnostics for unsupported constructs instead of silently omitting them. Global state, pointers, references, arrays, aggregates, namespaces, templates, overloads, indirect/member/operator calls, casts that change type, `switch`, labels/goto, string/character literals, and unrecognized declarations/statements remain unsupported in this AST lowering path. The `for` lowering currently requires four explicit AST children (initializer, condition, increment, body); omitted clauses, condition declarations, and multi-variable initializers are diagnosed. Function prototypes are accepted only when a matching definition is in the same translation unit. The Clang frontend can parse a wider language than this lowering stage can translate.

The lowering unit tests use compiler-shaped AST fixtures and verify shared IR structure, located rejection, and use of the shared conversion pipeline. A live Docker-backed Clang conversion test still requires Docker Desktop and the local `tascode-clang-frontend:1` image; compiler output validation can separately check generated target syntax.
