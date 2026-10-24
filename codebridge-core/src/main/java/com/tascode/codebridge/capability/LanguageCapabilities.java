package com.tascode.codebridge.capability;

import java.util.Set;

/** Declared feature support for a language backend. */
public record LanguageCapabilities(Set<String> supportedFeatures) {
    public LanguageCapabilities { supportedFeatures = Set.copyOf(supportedFeatures); }
    public boolean supports(String feature) { return supportedFeatures.contains(feature); }
}
