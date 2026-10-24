package com.tascode.codebridge.language;

import com.tascode.codebridge.diagnostic.Diagnostic;
import com.tascode.codebridge.ir.IrNode;
import java.util.List;

/** Parses source text into the shared IR. */
public interface LanguageParser {
    Language language();
    ParseResult parse(String source);
    record ParseResult(IrNode root, List<Diagnostic> diagnostics) {
        public ParseResult { diagnostics = List.copyOf(diagnostics); }
        public boolean successful() { return root != null && diagnostics.stream().noneMatch(d -> d.severity() == com.tascode.codebridge.diagnostic.Severity.ERROR); }
    }
}
