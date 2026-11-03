# C to C++ support audit

This page describes the implemented C-to-C++ converter subset in the current shared-IR pipeline. It is an explicit subset converter, not a complete ISO C parser or a proof that arbitrary C behavior can be translated. Unsupported constructs must be rejected or caught by compilation; do not assume accepted syntax is a complete C language implementation.

## Implemented

| C construct | Current handling |
|---|---|
| Function definitions and prototypes | Prototypes are removed from the IR; generated C++ emits declarations for the parsed functions so calls may precede definitions. |
| Recursion | Direct self-recursion and mutual recursion are supported for functions in the supported scalar subset. Generated C and C++ place function declarations before definitions, and supported overloads are resolved by argument types. Recursion depth, termination, and stack limits remain program-dependent; no tail-call optimization is promised. |
| Local primitive values | `void`, `char`, `short`, `int`, `long`, `float`, `double`, `_Bool`/`bool` within the parser's supported forms. C `long` stays C++ `long`; Java `long` stays C++ `long long`. |
| Expressions | Literals (decimal, supported octal/hex, suffixed long), names, calls, one-dimensional array access, assignment and supported compound assignment, prefix/postfix increment/decrement, unary `+ - ! ~`, arithmetic, comparisons, bitwise and logical operators. |
| Statements | Blocks, declarations, return, expression statements, `if/else`, `for`, `while`, `do/while`, `break`, and `continue`. |
| Arrays | Local one-dimensional arrays, including runtime lengths, become `std::vector` and include `<vector>`. C array parameters are represented as mutable vector references. |
| Enums | File-scope enum definitions with implicit enumerators or explicit signed 32-bit integer literals are retained in the IR and generated as C++ enums. Named enum tag uses in declarations lower to `int`, matching C's integer-compatible enumerator model. C-to-Java enum conversion is explicitly rejected. |
| Console I/O | The implemented `printf`/`scanf` mappings and recognized C++ stream forms use shared IR/rule mappings; only validated format/argument patterns are accepted. |
| Limited preprocessor | Recognized headers are retained for generated output; simple object-like literal macros are expanded. Function-like/arbitrary macros and unsupported directives are diagnosed. |
| C++ validity protections | C identifiers that collide with C++ keywords are rejected with a located `CB-CPP-IDENTIFIER-RESERVED` diagnostic. Generated output is compiler-validated when `g++` is available. |

The current shared parser and generator are in `codebridge-core/.../SubsetFunctionParser.java` and `SubsetFunctionGenerator.java`; C-specific guards are in `codebridge-lang-c/.../CParser.java`; C++ guards and prototype parsing/generation are in `codebridge-lang-cpp/.../CppSubsetParser.java` and `CppGenerator.java`. The `CodeBridgePipelineTest` suite has positive, compile, round-trip, and negative diagnostic cases.

## Explicitly unsupported / not yet modeled

- Structs, unions, typedefs, global variables, pointers, pointer arithmetic, and function pointers. Enum expressions, forward declarations, repeated tags, and out-of-range values are unsupported.
- `switch/case`, labels and `goto`, bit-fields, compound literals, designated initializers, flexible array members, variadic functions, and C complex types.
- Storage/type qualifiers and signed/unsigned integer forms; `long double`; atomics; threads; inline assembly.
- Full C declarators, multidimensional arrays, aggregate initialization, function-pointer declarations, and arbitrary declaration ordering.
- Full preprocessor semantics: conditional compilation, token pasting, stringification, include expansion, recursive macro semantics, and arbitrary/function-like macros.
- C implementation-defined behavior cannot generally be proven portable. The converter is designed to preserve behavior only within its accepted subset and the same compiler/target assumptions.

Syntax outside the subset may produce a specific preflight diagnostic for recognized constructs, or a located parse diagnostic. It must never be treated as successfully converted just because text was emitted.

## Remaining work before calling the backend mature

1. Replace the hand-written subset parser with a full C grammar frontend and lower supported grammar productions into the IR.
2. Extend the IR for aggregate types, `switch`, initializers, declarator forms, pointer/reference semantics, and translation-unit state before adding those conversions.
3. Expand C-to-C++ mappings for standard library and headers from reviewed data, with format and type checks.
4. Build a compiler-backed conformance corpus across compilers/targets, especially integer widths, overflow, char signedness, floating-point, and I/O behavior.
5. Add broader differential execution cases in the Docker sandbox. Compiler success alone checks syntax; it does not establish behavioral equivalence.

“All C syntax” is not a finite completion target for the current IR: ISO C has many declaration and execution forms, and C++ has additional constraints and semantics. Each newly supported family needs an IR representation, a lowering/generation rule, diagnostics for non-equivalent cases, and positive/negative compile and equivalence tests.
