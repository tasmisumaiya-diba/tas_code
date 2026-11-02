package com.tascode.codebridge.language.c;
import com.tascode.codebridge.capability.LanguageCapabilities;
import com.tascode.codebridge.language.*;

public final class CLanguageModule implements LanguageModule {
 private static final LanguageCapabilities CAPABILITIES=LanguageCapabilities.load(CLanguageModule.class,"/codebridge/capabilities/c.txt");
 private final CParser parser=new CParser(); private final CGenerator generator=new CGenerator();
 public Language language(){return Language.C;} public LanguageParser parser(){return parser;} public LanguageGenerator generator(){return generator;}
 public LanguageCapabilities capabilities(){return CAPABILITIES;}
}
