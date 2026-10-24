package com.tascode.codebridge.language.c;
import com.tascode.codebridge.language.Language;
import com.tascode.codebridge.language.LanguageGenerator;
import com.tascode.codebridge.language.subset.SubsetFunctionGenerator;
import com.tascode.codebridge.ir.IrNode;
public final class CGenerator implements LanguageGenerator {
 private final SubsetFunctionGenerator delegate=new SubsetFunctionGenerator(Language.C);
 public Language language(){return Language.C;}
 public GenerationResult generate(IrNode root){return delegate.generate(root);}
}
