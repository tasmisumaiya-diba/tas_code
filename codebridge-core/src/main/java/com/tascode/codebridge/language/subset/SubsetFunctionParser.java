package com.tascode.codebridge.language.subset;

import com.tascode.codebridge.diagnostic.Diagnostic;
import com.tascode.codebridge.diagnostic.Severity;
import com.tascode.codebridge.diagnostic.SourceSpan;
import com.tascode.codebridge.ir.*;
import com.tascode.codebridge.language.Language;
import com.tascode.codebridge.language.LanguageParser;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Recursive-descent parser for the initial, intentionally small C subset. */
public final class SubsetFunctionParser implements LanguageParser {
    private static final int V=1;
    private final Language language;
    public SubsetFunctionParser(Language language) { this.language = java.util.Objects.requireNonNull(language); }
    private List<Token> tokens; private int at;
    @Override public Language language(){ return language; }
    @Override public synchronized ParseResult parse(String source){
        List<Diagnostic> diagnostics=new ArrayList<>();
        if(source==null||source.isBlank()) return new ParseResult(null,List.of(Diagnostic.withoutLocation(Severity.ERROR,"CB-PARSE-EMPTY","Source code is empty.")));
        try { tokens=lex(source); at=0; List<FunctionDeclaration> fs=new ArrayList<>(); while(!peek(Kind.EOF)) fs.add(function()); return new ParseResult(new Program(V,span(fs.isEmpty()?peek():tokens.get(0),previous()),fs),diagnostics); }
        catch(ParseFailure e){diagnostics.add(Diagnostic.at(Severity.ERROR,"CB-PARSE-C-UNSUPPORTED",e.getMessage(),e.token.span())); return new ParseResult(null,diagnostics);}
    }
    private FunctionDeclaration function(){Token start=expect(Kind.ID,"expected function return type"); String type=start.text; Token name=expect(Kind.ID,"expected function name"); expect("("); List<Parameter> ps=new ArrayList<>(); if(!check(")")){ if(checkId("void")&&look(1).text.equals(")")){advance();} else do {Token t=expect(Kind.ID,"expected parameter type");Token n=expect(Kind.ID,"expected parameter name");ps.add(new Parameter(V,n.span(),t.text,n.text));}while(match(",")); } expect(")"); BlockStatement body=block(); return new FunctionDeclaration(V,span(start,previous()),type,name.text,ps,body); }
    private BlockStatement block(){Token start=expect("{");List<Statement> ss=new ArrayList<>();while(!check("}")&&!peek(Kind.EOF))ss.add(statement());expect("}");return new BlockStatement(V,span(start,previous()),ss);}
    private Statement statement(){if(check("{"))return block(); if(matchId("return")){Token s=previous();Optional<Expression> e=check(";")?Optional.empty():Optional.of(expression());expect(";");return new ReturnStatement(V,span(s,previous()),e);} if(isType(peek().text)){Token t=advance();Token n=expect(Kind.ID,"expected variable name");Optional<Expression> init=match("=")?Optional.of(expression()):Optional.empty();expect(";");return new VariableDeclaration(V,span(t,previous()),n.text,t.text,init);}Expression e=expression();expect(";");return new ExpressionStatement(V,e.sourceSpan(),e);}
    private Expression expression(){return binary(0);}
    private static final String[][] OPS={{"==","!=" ,"<",">","<=",">="},{"+","-"},{"*","/","%"}};
    private Expression binary(int min){Expression left=primary();while(true){int p=precedence(peek().text);if(p<min)break;Token op=advance();Expression right=binary(p+1);left=new BinaryExpression(V,span(tokenAt(left.sourceSpan()),previous()),op.text,left,right);}return left;}
    private Token tokenAt(SourceSpan s){return new Token(Kind.ID,"",s);}
    private Expression primary(){Token t=advance();if(t.kind==Kind.NUMBER)return new LiteralExpression(V,t.span(),Integer.parseInt(t.text));if(t.kind==Kind.STRING)return new LiteralExpression(V,t.span(),t.text.substring(1,t.text.length()-1));if(t.kind==Kind.ID){if(t.text.equals("true")||t.text.equals("false"))return new LiteralExpression(V,t.span(),Boolean.parseBoolean(t.text));StringBuilder callee=new StringBuilder(t.text);boolean qualified=false;while(check(".")||check("::")){qualified=true;callee.append(advance().text);callee.append(expect(Kind.ID,"expected name after qualifier").text);}if(match("(")){List<Expression> args=new ArrayList<>();if(!check(")")){do{args.add(expression());}while(match(","));}expect(")");return new CallExpression(V,span(t,previous()),callee.toString(),args);}if(qualified)throw fail("qualified names are supported only for function calls",t);return new NameExpression(V,t.span(),t.text);}if(t.text.equals("(")){Expression e=expression();expect(")");return e;}throw fail("expected expression",t);}
    private int precedence(String op){for(int i=0;i<OPS.length;i++)for(String candidate:OPS[i])if(candidate.equals(op))return i;return -1;}
    private boolean isType(String s){return List.of("int","long","short","char","float","double","_Bool","bool").contains(s);}
    private List<Token> lex(String s){List<Token> out=new ArrayList<>();int i=0,line=1,col=1;while(i<s.length()){char c=s.charAt(i);if(Character.isWhitespace(c)){if(c=='\n'){line++;col=1;}else col++;i++;continue;}int sl=line,sc=col,start=i;if(c=='/'&&i+1<s.length()&&s.charAt(i+1)=='/'){while(i<s.length()&&s.charAt(i)!='\n'){i++;col++;}continue;}if(c=='/'&&i+1<s.length()&&s.charAt(i+1)=='*'){i+=2;col+=2;while(i+1<s.length()&&!(s.charAt(i)=='*'&&s.charAt(i+1)=='/')){if(s.charAt(i)=='\n'){line++;col=1;}else col++;i++;}if(i+1>=s.length())throw new ParseFailure("unterminated comment",new Token(Kind.SYMBOL,"/*",SourceSpan.point(sl,sc)));i+=2;col+=2;continue;}if(Character.isLetter(c)||c=='_'){i++;col++;while(i<s.length()&&(Character.isLetterOrDigit(s.charAt(i))||s.charAt(i)=='_')){i++;col++;}out.add(new Token(Kind.ID,s.substring(start,i),new SourceSpan(sl,sc,line,col-1)));continue;}if(Character.isDigit(c)){i++;col++;while(i<s.length()&&Character.isDigit(s.charAt(i))){i++;col++;}out.add(new Token(Kind.NUMBER,s.substring(start,i),new SourceSpan(sl,sc,line,col-1)));continue;}if(c=='"'){i++;col++;while(i<s.length()&&s.charAt(i)!='"'){if(s.charAt(i)=='\\'&&i+1<s.length()){i+=2;col+=2;}else{if(s.charAt(i)=='\n')throw new ParseFailure("newline in string literal",new Token(Kind.STRING,"",SourceSpan.point(sl,sc)));i++;col++;}}if(i>=s.length())throw new ParseFailure("unterminated string literal",new Token(Kind.STRING,"",SourceSpan.point(sl,sc)));i++;col++;out.add(new Token(Kind.STRING,s.substring(start,i),new SourceSpan(sl,sc,line,col-1)));continue;}String two=i+1<s.length()?s.substring(i,i+2):"";if(List.of("==","!=","<=",">=","::").contains(two)){i+=2;col+=2;out.add(new Token(Kind.SYMBOL,two,new SourceSpan(sl,sc,line,col-1)));continue;}if("{}();,+-*/%=<>.:".indexOf(c)>=0){i++;col++;out.add(new Token(Kind.SYMBOL,String.valueOf(c),SourceSpan.point(sl,sc)));continue;}throw new ParseFailure("unsupported character '"+c+"'",new Token(Kind.SYMBOL,String.valueOf(c),SourceSpan.point(sl,sc)));}out.add(new Token(Kind.EOF,"",SourceSpan.point(line,col)));return out;}
    private boolean peek(Kind k){return peek().kind==k;}private Token peek(){return tokens.get(at);}private Token previous(){return tokens.get(Math.max(0,at-1));}private Token advance(){if(!peek(Kind.EOF))at++;return previous();}private Token look(int n){return tokens.get(Math.min(at+n,tokens.size()-1));}private boolean check(String s){return peek().text.equals(s);}private boolean checkId(String s){return peek().kind==Kind.ID&&check(s);}private boolean match(String s){if(check(s)){advance();return true;}return false;}private boolean matchId(String s){return checkId(s)&&advance()!=null;}private Token expect(String s){if(!check(s))throw fail("expected '"+s+"'",peek());return advance();}private Token expect(Kind k,String m){if(!peek(k))throw fail(m,peek());return advance();}private ParseFailure fail(String m,Token t){return new ParseFailure(m,t);}private SourceSpan span(Token a,Token b){return new SourceSpan(a.span.startLine(),a.span.startColumn(),b.span.endLine(),b.span.endColumn());}
    private enum Kind{ID,NUMBER,STRING,SYMBOL,EOF}private record Token(Kind kind,String text,SourceSpan span){}private static final class ParseFailure extends RuntimeException{final Token token;ParseFailure(String m,Token t){super(m);token=t;}}
}


