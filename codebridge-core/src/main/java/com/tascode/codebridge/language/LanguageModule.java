package com.tascode.codebridge.language;

import com.tascode.codebridge.capability.LanguageCapabilities;

/** One language backend. Language modules implement this and depend only on core. */
public interface LanguageModule {
    Language language();
    LanguageParser parser();
    LanguageGenerator generator();
    LanguageCapabilities capabilities();
}
