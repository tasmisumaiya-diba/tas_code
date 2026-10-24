package com.tascode.codebridge.language.java;
import com.tascode.codebridge.capability.LanguageCapabilities;
import com.tascode.codebridge.language.*;
import java.util.Set;
public final class JavaLanguageModule implements LanguageModule {
 private final JavaParser parser=new JavaParser(); private final JavaGenerator generator=new JavaGenerator();
 public Language language(){return Language.JAVA;} public LanguageParser parser(){return parser;} public LanguageGenerator generator(){return generator;}
 public LanguageCapabilities capabilities(){return new LanguageCapabilities(Set.of("functions","variables","integer-arithmetic","calls","return","blocks"));}
}
