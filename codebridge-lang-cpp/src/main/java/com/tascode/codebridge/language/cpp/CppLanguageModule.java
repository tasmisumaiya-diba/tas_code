package com.tascode.codebridge.language.cpp;
import com.tascode.codebridge.capability.LanguageCapabilities;
import com.tascode.codebridge.language.*;

public final class CppLanguageModule implements LanguageModule {
 private static final LanguageCapabilities CAPABILITIES=LanguageCapabilities.load(CppLanguageModule.class,"/codebridge/capabilities/cpp.txt");
 private final CppSubsetParser parser=new CppSubsetParser(); private final CppGenerator generator=new CppGenerator();
 public Language language(){return Language.CPP;} public LanguageParser parser(){return parser;} public LanguageGenerator generator(){return generator;}
 public LanguageCapabilities capabilities(){return CAPABILITIES;}
}
