package com.tascode.codebridge.language.cpp;
import com.tascode.codebridge.language.Language;
import com.tascode.codebridge.language.LanguageParser;
import com.tascode.codebridge.language.subset.SubsetFunctionParser;
public final class CppSubsetParser implements LanguageParser {
 private final SubsetFunctionParser delegate=new SubsetFunctionParser(Language.CPP);
 public Language language(){return Language.CPP;}
 public ParseResult parse(String source){return delegate.parse(source);}
}
