package com.tascode.codebridge.rules;

import com.tascode.codebridge.ir.IrNode;
import com.tascode.codebridge.language.Language;

/** A deterministic IR rewrite for one or more language directions. */
public interface ConversionRule {
    boolean appliesTo(Language from, Language to, IrNode node);
    int priority();
    IrNode rewrite(IrNode node, RuleContext context);
}
