package com.tascode.codebridge.rules.standard;

import com.tascode.codebridge.rules.ConversionRule;
import com.tascode.codebridge.rules.ConversionRuleProvider;
import java.util.List;

/** Supplies the built-in, resource-backed standard-library translation rules. */
public final class StandardLibraryRuleProvider implements ConversionRuleProvider {
    @Override
    public List<ConversionRule> rules() {
        return List.of(new StringEqualityRule(), new CoutConversionRule(), new StandardLibraryCallRule(), new NumericConditionRule(), new IntegerInputRule());
    }
}