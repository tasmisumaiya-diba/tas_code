# Adding a language backend

CodeBridge shares one IR between language backends. A backend parses one language into that IR and generates the target language from it. Language modules depend on `codebridge-core`; they must not depend on another language module.

## 1. Add the Maven module

Create `codebridge-lang-<id>` with a dependency on `com.tascode:codebridge-core:${project.version}` and add the module to the parent `pom.xml`. Keep parser and generator dependencies inside this module.

## 2. Register the language ID

Add the language to `Language` with a stable lowercase ID. IDs are used by the HTTP API and UI, so keep them stable once released.

## 3. Implement the backend

Implement `LanguageModule` with:

- `language()` returning the new enum value.
- `parser()` returning a `LanguageParser` that produces versioned IR nodes with source spans.
- `generator()` returning a `LanguageGenerator` that emits only constructs represented by the IR.
- `capabilities()` loading the IR features the backend can generate safely from a language-owned data file (for example `src/main/resources/codebridge/capabilities/py.txt`). Keep these declarations out of Java conditionals so the capability set is reviewable and extensible.

For syntax outside the supported subset, return a located `Diagnostic` with a stable code. Do not silently drop or approximate unsupported semantics.

## 4. Register it with `ServiceLoader`

Add this resource to the new module:

`src/main/resources/META-INF/services/com.tascode.codebridge.language.LanguageModule`

Put the fully qualified backend class name in the file, for example `com.example.codebridge.language.py.PythonLanguageModule`. `CodeBridge.createPipeline()` discovers modules from this resource.

## 5. Add conversion rules only when needed

Put cross-language transformations in `codebridge-rules`, not in another language module. Implement `ConversionRule` and register its provider through `ServiceLoader` at:

`src/main/resources/META-INF/services/com.tascode.codebridge.rules.ConversionRuleProvider`

Rules should inspect the source and target language in `RuleContext`, preserve source spans and IR versions, and avoid mutable shared state. Keep standard-library name mappings in the rules resource data where possible.

## 6. Add validation and tests

Add parser and generator unit tests, an input-to-IR-to-output round trip, and integration tests for every affected direction. Include unsupported-feature tests that assert the stable diagnostic code and source location. Compile representative generated output when a compiler is available; compilation must not execute the generated program.

Update the UI language selectors, HTTP/API tests, and README whenever the new language becomes user-selectable. Run the full suite with `mvnw.cmd test` and build the app with `mvnw.cmd -pl codebridge-web -am package`.
