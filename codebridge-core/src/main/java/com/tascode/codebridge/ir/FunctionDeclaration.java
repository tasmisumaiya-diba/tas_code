package com.tascode.codebridge.ir;
import com.tascode.codebridge.diagnostic.SourceSpan;
import java.util.List;
import java.util.Objects;
public record FunctionDeclaration(int irVersion,SourceSpan sourceSpan,String returnType,String name,List<Parameter> parameters,BlockStatement body) implements IrNode {
 public FunctionDeclaration { IrVersions.requireSupported(irVersion); Objects.requireNonNull(sourceSpan); Objects.requireNonNull(returnType); Objects.requireNonNull(name); parameters=List.copyOf(Objects.requireNonNull(parameters)); Objects.requireNonNull(body); if(returnType.isBlank()||name.isBlank())throw new IllegalArgumentException("Function return type and name are required"); parameters.forEach(p->IrVersions.requireMatching(irVersion,p)); IrVersions.requireMatching(irVersion,body); }
}
