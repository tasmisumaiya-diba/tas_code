package com.tascode.codebridge.language;

import com.tascode.codebridge.diagnostic.Diagnostic;
import com.tascode.codebridge.ir.IrNode;
import java.util.List;

/** Generates target-language source from shared IR. */
public interface LanguageGenerator {
    Language language();
    GenerationResult generate(IrNode root);
    record GenerationResult(String source, List<Diagnostic> diagnostics) {
        public GenerationResult { diagnostics = List.copyOf(diagnostics); }
        public boolean successful() { return source != null && diagnostics.stream().noneMatch(d -> d.severity() == com.tascode.codebridge.diagnostic.Severity.ERROR); }
    }
}
