package com.tascode.codebridge.language.subset;
import com.tascode.codebridge.diagnostic.Diagnostic;
import com.tascode.codebridge.diagnostic.Severity;
import com.tascode.codebridge.ir.*;
import com.tascode.codebridge.language.Language;
import com.tascode.codebridge.language.LanguageGenerator;
import java.util.List;
public final class SubsetFunctionGenerator implements LanguageGenerator {
 private final boolean cppStyle;
 private final Language targetLanguage;
 public SubsetFunctionGenerator(){this(Language.C);}
 public SubsetFunctionGenerator(Language targetLanguage){this.targetLanguage=java.util.Objects.requireNonNull(targetLanguage);this.cppStyle=targetLanguage==Language.CPP;}
 public Language language(){return targetLanguage;}
 public GenerationResult generate(IrNode root){if(!(root instanceof Program p))return new GenerationResult(null,List.of(Diagnostic.withoutLocation(Severity.ERROR,"CB-GEN-C-ROOT","C generation requires a program node.")));StringBuilder b=new StringBuilder();try{if(targetLanguage==Language.C||targetLanguage==Language.CPP){for(String include:p.includes())b.append("#include <").append(include).append(">\n");if(!p.includes().isEmpty()&&!p.functions().isEmpty())b.append('\n');}for(FunctionDeclaration f:p.functions()){if(b.length()>0&&b.charAt(b.length()-1)!='\n')b.append('\n');function(b,f);}return new GenerationResult(b.toString(),List.of());}catch(UnsupportedOperationException e){return new GenerationResult(null,List.of(Diagnostic.withoutLocation(Severity.ERROR,"CB-GEN-C-UNSUPPORTED",e.getMessage())));}}
 private String typeName(String type){if(cppStyle&&type.equals("_Bool"))return "bool";if(!cppStyle&&type.equals("bool"))return "_Bool";return type;}
 private void function(StringBuilder b,FunctionDeclaration f){b.append(typeName(f.returnType())).append(' ').append(f.name()).append('(');if(f.parameters().isEmpty()){if(!cppStyle)b.append("void");}else for(int i=0;i<f.parameters().size();i++){if(i>0)b.append(", ");Parameter p=f.parameters().get(i);b.append(typeName(p.type())).append(' ').append(p.name());}b.append(") ");block(b,f.body(),0);b.append('\n');}
 private void block(StringBuilder b,BlockStatement x,int d){b.append("{\n");for(Statement s:x.statements()){b.append("    ".repeat(d+1));statement(b,s,d+1);b.append('\n');}b.append("    ".repeat(d)).append('}');}
 private void statement(StringBuilder b,Statement s,int d){if(s instanceof BlockStatement x)block(b,x,d);else if(s instanceof VariableDeclaration x){b.append(typeName(x.type())).append(' ').append(x.name());x.initializer().ifPresent(e->{b.append(" = ");expression(b,e);});b.append(';');}else if(s instanceof ExpressionStatement x){expression(b,x.expression());b.append(';');}else if(s instanceof ReturnStatement x){b.append("return");x.value().ifPresent(e->{b.append(' ');expression(b,e);});b.append(';');}else throw new UnsupportedOperationException("Unsupported C statement node: "+s.getClass().getSimpleName());}
 private void expression(StringBuilder b,Expression e){if(e instanceof LiteralExpression x){if(x.value() instanceof String s)b.append('"').append(s.replace("\\","\\\\").replace("\"","\\\"")).append('"');else if(x.value() instanceof Boolean v)b.append(v?"1":"0");else b.append(x.value());}else if(e instanceof NameExpression x)b.append(x.name());else if(e instanceof BinaryExpression x){b.append('(');expression(b,x.left());b.append(' ').append(x.operator()).append(' ');expression(b,x.right());b.append(')');}else if(e instanceof CallExpression x){b.append(x.callee()).append('(');for(int i=0;i<x.arguments().size();i++){if(i>0)b.append(", ");expression(b,x.arguments().get(i));}b.append(')');}else throw new UnsupportedOperationException("Unsupported C expression node: "+e.getClass().getSimpleName());}
}



