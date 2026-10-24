package com.tascode.codebridge.language.cpp;
import com.tascode.codebridge.ir.IrNode;
import com.tascode.codebridge.language.Language;
import com.tascode.codebridge.language.LanguageGenerator;
import com.tascode.codebridge.language.subset.SubsetFunctionGenerator;
public final class CppGenerator implements LanguageGenerator {
 private final SubsetFunctionGenerator delegate=new SubsetFunctionGenerator(Language.CPP);
 public Language language(){return Language.CPP;}
 public GenerationResult generate(IrNode root){return delegate.generate(root);}
}
