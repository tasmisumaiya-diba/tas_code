package com.tascode.codebridge.language.c;
import com.tascode.codebridge.language.Language;
import com.tascode.codebridge.language.LanguageParser;
import com.tascode.codebridge.language.subset.SubsetFunctionParser;
public final class CParser implements LanguageParser {
 private final SubsetFunctionParser delegate=new SubsetFunctionParser(Language.C);
 public Language language(){return Language.C;}
 public ParseResult parse(String source){return delegate.parse(source);}
}
