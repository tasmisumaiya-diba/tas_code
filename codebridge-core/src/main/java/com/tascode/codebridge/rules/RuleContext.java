package com.tascode.codebridge.rules;

import com.tascode.codebridge.language.Language;
import com.tascode.codebridge.ir.IrNode;
import java.util.Objects;

public record RuleContext(Language from, Language to) {
    public RuleContext { Objects.requireNonNull(from); Objects.requireNonNull(to); }
}
