package com.tascode.codebridge.capability;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.Set;

/** Declared feature support for a language backend. */
public record LanguageCapabilities(Set<String> supportedFeatures) {
    public LanguageCapabilities { supportedFeatures = Set.copyOf(supportedFeatures); }
    public boolean supports(String feature) { return supportedFeatures.contains(feature); }

    /** Loads one backend's declared features from its own packaged resource. */
    public static LanguageCapabilities load(Class<?> owner, String resourceName) {
        try (InputStream stream = owner.getResourceAsStream(resourceName)) {
            if (stream == null) throw new IllegalStateException("Missing capability resource '" + resourceName + "'.");
            Set<String> features = new LinkedHashSet<>();
            for (String line : new String(stream.readAllBytes(), StandardCharsets.UTF_8).split("\\R")) {
                String feature = line.split("#", 2)[0].trim();
                if (!feature.isEmpty()) features.add(feature);
            }
            if (features.isEmpty()) throw new IllegalStateException("Capability resource '" + resourceName + "' is empty.");
            return new LanguageCapabilities(features);
        } catch (IOException exception) {
            throw new IllegalStateException("Could not read capability resource '" + resourceName + "'.", exception);
        }
    }
}
