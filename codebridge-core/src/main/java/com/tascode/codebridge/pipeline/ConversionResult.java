package com.tascode.codebridge.pipeline;

import com.tascode.codebridge.diagnostic.Diagnostic;
import com.tascode.codebridge.language.Language;
import java.util.List;
import java.util.Optional;

public record ConversionResult(Language from, Language to, Optional<String> output, List<Diagnostic> diagnostics) {
    public ConversionResult { output = output == null ? Optional.empty() : output; diagnostics = List.copyOf(diagnostics); }
    public boolean successful() { return output.isPresent() && diagnostics.stream().noneMatch(d -> d.severity() == com.tascode.codebridge.diagnostic.Severity.ERROR); }
}
