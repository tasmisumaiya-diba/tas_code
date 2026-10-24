package com.tascode.codebridge.language.cpp;
import com.tascode.codebridge.capability.LanguageCapabilities;
import com.tascode.codebridge.language.*;
import java.util.Set;
public final class CppLanguageModule implements LanguageModule {
 private final CppSubsetParser parser=new CppSubsetParser(); private final CppGenerator generator=new CppGenerator();
 public Language language(){return Language.CPP;} public LanguageParser parser(){return parser;} public LanguageGenerator generator(){return generator;}
 public LanguageCapabilities capabilities(){return new LanguageCapabilities(Set.of("functions","variables","integer-arithmetic","calls","return","blocks"));}
}
