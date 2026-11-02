# Java to C++ support audit

CodeBridge currently translates a deliberately restricted Java subset into C++ through the shared IR. The Java compiler frontend attributes the submitted source, but the converter is not a full Java compiler and does not claim to support every Java grammar production or runtime behavior. Accepted syntax is the subset listed below; recognized unsupported constructs are rejected with stable diagnostics, and other unsupported syntax should fail parsing rather than be emitted as successful output.

## Implemented

| Java construct | Current handling |
|---|---|
| Top-level type | One simple top-level class. Primitive instance fields with optional literal initializers convert to a C++ class; uninitialized fields use value initialization to preserve Java defaults. Primitive static fields with literal initializers remain translation-unit globals; `final` maps to `const`. Static methods are emitted as free functions. Constructors, instance methods, nested types, non-primitive fields, inheritance, interfaces, and enums remain rejected. |
| Methods | Supported primitive and `String` return/parameter types in the function IR; `void` methods; method calls for supported functions. Java `main(String[] args)` is normalized only when it does not use the command-line argument array. |
| Primitive values | `byte`, `short`, `int`, `long`, `char`, `float`, `double`, and `boolean` in parsed forms. `byte` is represented as `std::int8_t`; Java `long` as C++ `long long`; boolean as C++ boolean. Compile-time int-to-byte narrowing is accepted only in range. |
| Expressions | Supported literals, names, function calls, array indexing, assignment/compound assignment, increment/decrement, unary operators, arithmetic, comparison, bitwise, and logical operators. Java `String.equals`, `length`, `isEmpty`, `startsWith`, `endsWith`, `contains`, `concat`, and concatenation are supported for the non-null string subset. Reference equality (`==`) for strings is rejected because C++ compares string values. |
| Statements | Blocks, local declarations, returns, expression statements, `if/else`, `for`, `while`, `do/while`, `break`, and `continue`. |
| Arrays | One-dimensional supported primitive arrays map to `std::vector`, including runtime-sized arrays and initializer lists. `String[]` is accepted only with an explicit initializer list consisting entirely of string literals; Java's default-null array elements and null entries are rejected. `char[]` and multidimensional arrays are rejected. |
| Console output | Supported `System.out.print`/`println` forms use the shared output mapping. Unsupported formatting/type combinations are diagnosed. |
| Validation | Generated C++ is passed to the compiler validation service when `g++` is available. Docker output comparison is a separate opt-in validation path; compilation alone does not prove behavioral equivalence. |

Java 21 grammar parsing is provided by `codebridge-lang-java/.../JavaAstFrontend.java` using the JDK compiler tree API. It produces a structural index and shared recursive `TypeReference`/`TypeParameter` IR values for packages, imports, declarations, generic bounds, and method signatures. The lowering stage still accepts only the subset described above; parsing a Java construct is not a claim that the converter can lower it. Java String-to-C++ operation mappings are read from `codebridge-rules/src/main/resources/java-cpp-runtime-mappings.tsv`; the prefix, suffix, and containment mappings emit small C++ runtime helpers. The subset parser/generator are in `codebridge-core/.../SubsetFunctionParser.java` and `SubsetFunctionGenerator.java`, and C++ generation is in `codebridge-lang-cpp/.../CppGenerator.java`. Positive conversions, C++ compile checks, and negative diagnostics are covered by `CodeBridgePipelineTest` and the integration tests.

## Explicitly unsupported or restricted

- Packages, imports, multiple top-level declarations, annotations, records, sealed declarations, native methods, and arbitrary modifiers.
- Object behavior remains restricted: constructor bodies, explicit object allocation/use, `this`, `super`, inheritance, interfaces, dynamic dispatch, nested classes, and non-literal field initializers are unsupported. Simple primitive class layout is represented, but object identity and Java reference semantics are not modeled. Static fields are limited to primitive literal declarations.
- Generics and collections; lambdas and method references; `var`; object allocation; `null`; switch statements/expressions; enhanced-for loops; exceptions; synchronization and threading.
- Multidimensional arrays, `char[]`, and `String[]` without a fully explicit non-null literal initializer.
- Arbitrary Java library APIs, reflection, annotations processors, serialization, streams, and JVM-specific behavior. String helpers model the documented subset only; Java UTF-16 behavior for arbitrary Unicode strings is not guaranteed by `std::string`.
- The frontend parses Java 21 syntax, but its current conversion lowering does not model most of the features below. The Java and C++ type systems are not identical. In particular, UTF-16 `char` and Java string indexing/length semantics cannot be assumed equivalent to native C++ `char`/`std::string` for arbitrary Unicode data. The current implementation's scalar char and string support is a constrained convenience subset, not a guarantee for every Unicode input.

## What “complete Java syntax” would require

“All Java syntax” is not a finite add-on to this parser. Full support requires a Java grammar and semantic frontend, symbol/type resolution, a substantially richer IR (classes, object identity, overload resolution, generics, exceptions, arrays, and library/runtime models), and target runtime shims where C++ has no direct equivalent. Every syntax family then needs positive, negative, compiler, and behavior-equivalence tests. Until those pieces exist, this page is the supported contract; rejected or unparsed Java must not be reported as converted.
