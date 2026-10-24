package com.tascode.codebridge.rules;

import java.util.List;

/** Service-loaded source of deterministic conversion rules. */
@FunctionalInterface
public interface ConversionRuleProvider {
    List<ConversionRule> rules();
}