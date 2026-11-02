package com.tascode.codebridge.language.java;
import com.tascode.codebridge.capability.LanguageCapabilities;
import com.tascode.codebridge.language.*;

public final class JavaLanguageModule implements LanguageModule {
 private static final LanguageCapabilities CAPABILITIES=LanguageCapabilities.load(JavaLanguageModule.class,"/codebridge/capabilities/java.txt");
 private final JavaParser parser=new JavaParser(); private final JavaGenerator generator=new JavaGenerator();
 public Language language(){return Language.JAVA;} public LanguageParser parser(){return parser;} public LanguageGenerator generator(){return generator;}
 public LanguageCapabilities capabilities(){return CAPABILITIES;}
}
