package com.tascode.codebridge.language.c;
import com.tascode.codebridge.capability.LanguageCapabilities;
import com.tascode.codebridge.language.*;
import java.util.Set;
public final class CLanguageModule implements LanguageModule {
 private final CParser parser=new CParser(); private final CGenerator generator=new CGenerator();
 public Language language(){return Language.C;} public LanguageParser parser(){return parser;} public LanguageGenerator generator(){return generator;}
 public LanguageCapabilities capabilities(){return new LanguageCapabilities(Set.of("functions","variables","integer-arithmetic","calls","conditionals","loops","loop-control","arrays","assignments","boolean-logic","comparisons","return","blocks"));}
}
