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
    private List<Token> tokens; private int at; private int loopDepth;
    @Override public Language language(){ return language; }
    @Override public synchronized ParseResult parse(String source){
        List<Diagnostic> diagnostics=new ArrayList<>();
        if(source==null||source.isBlank()) return new ParseResult(null,List.of(Diagnostic.withoutLocation(Severity.ERROR,"CB-PARSE-EMPTY","Source code is empty.")));
        try { tokens=lex(source); at=0; loopDepth=0; List<FunctionDeclaration> fs=new ArrayList<>(); while(!peek(Kind.EOF)) fs.add(function()); return new ParseResult(new Program(V,span(fs.isEmpty()?peek():tokens.get(0),previous()),fs),diagnostics); }
        catch(ParseFailure e){diagnostics.add(Diagnostic.at(Severity.ERROR,language==Language.CPP?"CB-PARSE-CPP-UNSUPPORTED":"CB-PARSE-C-UNSUPPORTED",e.getMessage(),e.token.span())); return new ParseResult(null,diagnostics);}
    }
    private FunctionDeclaration function(){Token start=expect(Kind.ID,"expected function return type"); String type=readType(start); Token name=expect(Kind.ID,"expected function name"); expect("("); List<Parameter> ps=new ArrayList<>(); if(!check(")")){ if(checkId("void")&&look(1).text.equals(")")){advance();} else do {Token t=expect(Kind.ID,"expected parameter type");String parameterType=readType(t);Token n=expect(Kind.ID,"expected parameter name");ps.add(new Parameter(V,n.span(),parameterType,n.text));}while(match(",")); } expect(")"); BlockStatement body=block(); return new FunctionDeclaration(V,span(start,previous()),type,name.text,ps,body); }
    private BlockStatement block(){Token start=expect("{");List<Statement> ss=new ArrayList<>();while(!check("}")&&!peek(Kind.EOF))ss.add(statement());expect("}");return new BlockStatement(V,span(start,previous()),ss);}
    private Statement statement(){if(matchId("break")){Token start=previous();if(loopDepth==0)throw fail("break is only valid inside a loop",start);expect(";");return new BreakStatement(V,span(start,previous()));}if(matchId("continue")){Token start=previous();if(loopDepth==0)throw fail("continue is only valid inside a loop",start);expect(";");return new ContinueStatement(V,span(start,previous()));}if(matchId("for"))return forStatement(previous());if(matchId("do"))return doWhileStatement(previous());if(matchId("if")){Token start=previous();expect("(");Expression condition=expression();expect(")");Statement thenBranch=statement();Optional<Statement> elseBranch=matchId("else")?Optional.of(statement()):Optional.empty();return new IfStatement(V,span(start,previous()),condition,thenBranch,elseBranch);}if(matchId("while")){Token start=previous();expect("(");Expression condition=expression();expect(")");Statement body=loopBody();return new WhileStatement(V,span(start,previous()),condition,body);}if(check("{"))return block(); if(matchId("return")){Token s=previous();Optional<Expression> e=check(";")?Optional.empty():Optional.of(expression());expect(";");return new ReturnStatement(V,span(s,previous()),e);} if(isType(peek().text)){Token t=advance();return variableOrArrayDeclaration(t);}Expression e=expression();expect(";");return new ExpressionStatement(V,e.sourceSpan(),e);}
    private Statement variableOrArrayDeclaration(Token type){String declaredType=readType(type);boolean javaArray=match("[");if(javaArray){expect("]");Token name=expect(Kind.ID,"expected array name");expect("=");expect("new");Token allocatedType=expect(Kind.ID,"expected array element type");if(!readType(allocatedType).equals(declaredType))throw fail("array creation type must match the declaration type",allocatedType);expect("[");Expression size=expression();expect("]");expect(";");return new ArrayDeclaration(V,span(type,previous()),declaredType,name.text,size);}Token name=expect(Kind.ID,"expected variable name");if(match("[")){Expression size=expression();expect("]");expect(";");return new ArrayDeclaration(V,span(type,previous()),declaredType,name.text,size);}Optional<Expression> initializer=match("=")?Optional.of(expression()):Optional.empty();expect(";");return new VariableDeclaration(V,span(type,previous()),name.text,declaredType,initializer);}    private Statement doWhileStatement(Token start){Statement body=loopBody();if(!matchId("while"))throw fail("expected while after do body",peek());expect("(");Expression condition=expression();expect(")");expect(";");return new DoWhileStatement(V,span(start,previous()),body,condition);}
    private Statement loopBody(){loopDepth++;try{return statement();}finally{loopDepth--;}}
    private Statement forStatement(Token start){expect("(");Optional<Statement> initializer;if(match(";"))initializer=Optional.empty();else if(isType(peek().text))initializer=Optional.of(statement());else{Expression initial=expression();expect(";");initializer=Optional.of(new ExpressionStatement(V,initial.sourceSpan(),initial));}Optional<Expression> condition=check(";")?Optional.empty():Optional.of(expression());expect(";");Optional<Expression> update=check(")")?Optional.empty():Optional.of(expression());expect(")");Statement body=loopBody();return new ForStatement(V,span(start,previous()),initializer,condition,update,body);}    private Expression expression(){Expression left=binary(0);if(match("=")){if(!(left instanceof NameExpression||left instanceof ArrayAccessExpression))throw fail("assignment target must be a variable or array element",previous());Expression value=expression();return new AssignmentExpression(V,span(tokenAt(left.sourceSpan()),tokenAt(value.sourceSpan())),left,value);}return left;}
    private static final String[][] OPS={{"||"},{"&&"},{"==","!="},{"<",">","<=",">="},{"+","-"},{"*","/","%"}};
    private Expression binary(int min){Expression left=primary();while(true){int p=precedence(peek().text);if(p<min)break;Token op=advance();Expression right=binary(p+1);left=new BinaryExpression(V,span(tokenAt(left.sourceSpan()),previous()),op.text,left,right);}return left;}
    private Token tokenAt(SourceSpan s){return new Token(Kind.ID,"",s);}
    private Expression primary(){if(check("++")||check("--")){Token operator=advance();Expression operand=primary();if(!(operand instanceof NameExpression target))throw fail("increment and decrement require a local name",operator);return new UpdateExpression(V,span(operator,tokenAt(operand.sourceSpan())),target,operator.text,true);}if(check("!")||check("-")||check("+")){Token operator=advance();Expression operand=primary();return new UnaryExpression(V,span(operator,tokenAt(operand.sourceSpan())),operator.text,operand);}Token t=advance();if(t.kind==Kind.NUMBER)return new LiteralExpression(V,t.span(),Integer.parseInt(t.text));if(t.kind==Kind.STRING)return new LiteralExpression(V,t.span(),decodeString(t));if(t.kind==Kind.CHARACTER)return new LiteralExpression(V,t.span(),decodeCharacter(t));if(t.kind==Kind.ID){if(t.text.equals("true")||t.text.equals("false"))return new LiteralExpression(V,t.span(),Boolean.parseBoolean(t.text));StringBuilder callee=new StringBuilder(t.text);boolean qualified=false;while(check(".")||check("::")){qualified=true;callee.append(advance().text);callee.append(expect(Kind.ID,"expected name after qualifier").text);}if(callee.toString().equals("std::cout")&&match("<<")){List<Expression> items=streamArguments();return new CallExpression(V,span(t,previous()),"__codebridge_cout",items);}if(match("(")){List<Expression> args=callArguments(callee.toString());return new CallExpression(V,span(t,previous()),callee.toString(),args);}if(qualified)throw fail("qualified names are supported only for function calls",t);NameExpression name=new NameExpression(V,t.span(),t.text);if(match("[")){Expression index=expression();expect("]");return new ArrayAccessExpression(V,span(t,previous()),name,index);}if(check("++")||check("--")){Token operator=advance();return new UpdateExpression(V,span(t,previous()),name,operator.text,false);}return name;}if(t.text.equals("(")){Expression e=expression();expect(")");return e;}throw fail("expected expression",t);}
    private List<Expression> streamArguments() {
        List<Expression> items = new ArrayList<>();
        do {
            if (checkId("std") && look(1).text.equals("::") && look(2).text.equals("endl")) {
                Token start = advance(); advance(); Token end = advance();
                items.add(new CallExpression(V, span(start, end), "__codebridge_endl", List.of()));
            } else items.add(expression());
        } while (match("<<"));
        return items;
    }
    private List<Expression> callArguments(String callee) {
        List<Expression> arguments = new ArrayList<>();
        if (callee.equals("scanf")) {
            if (!check(")")) {
                arguments.add(expression());
                if (match(",")) {
                    expect("&");
                    Token target = expect(Kind.ID, "scanf destination must be a local variable");
                    arguments.add(new NameExpression(V, target.span(), target.text));
                    while (match(",")) arguments.add(expression());
                }
            }
        } else if (!check(")")) {
            do { arguments.add(expression()); } while (match(","));
        }
        expect(")");
        return arguments;
    }
    private int precedence(String op){for(int i=0;i<OPS.length;i++)for(String candidate:OPS[i])if(candidate.equals(op))return i;return -1;}
    private boolean isType(String s){return List.of("int","long","short","char","float","double","_Bool","bool","string","String").contains(s)||(language==Language.CPP&&s.equals("std")&&look(2).text.equals("string"));} private String readType(Token first){if(language==Language.CPP&&first.text.equals("std")){expect("::");Token name=expect(Kind.ID,"expected string after std::");if(!name.text.equals("string"))throw fail("only std::string is supported as a qualified type",name);return "string";}return first.text;}
    private List<Token> lex(String source) {
        List<Token> out = new ArrayList<>();
        int i = 0, line = 1, column = 1;
        while (i < source.length()) {
            char c = source.charAt(i);
            if (Character.isWhitespace(c)) {
                if (c == '\n') { line++; column = 1; } else column++;
                i++;
                continue;
            }
            int startLine = line, startColumn = column, start = i;
            if (c == '/' && i + 1 < source.length() && source.charAt(i + 1) == '/') {
                while (i < source.length() && source.charAt(i) != '\n') { i++; column++; }
                continue;
            }
            if (c == '/' && i + 1 < source.length() && source.charAt(i + 1) == '*') {
                i += 2; column += 2;
                while (i + 1 < source.length() && !(source.charAt(i) == '*' && source.charAt(i + 1) == '/')) {
                    if (source.charAt(i) == '\n') { line++; column = 1; } else column++;
                    i++;
                }
                if (i + 1 >= source.length()) throw new ParseFailure("unterminated comment", new Token(Kind.SYMBOL, "/*", SourceSpan.point(startLine, startColumn)));
                i += 2; column += 2;
                continue;
            }
            if (Character.isLetter(c) || c == '_') {
                i++; column++;
                while (i < source.length() && (Character.isLetterOrDigit(source.charAt(i)) || source.charAt(i) == '_')) { i++; column++; }
                out.add(new Token(Kind.ID, source.substring(start, i), new SourceSpan(startLine, startColumn, line, column - 1)));
                continue;
            }
            if (Character.isDigit(c)) {
                i++; column++;
                while (i < source.length() && Character.isDigit(source.charAt(i))) { i++; column++; }
                out.add(new Token(Kind.NUMBER, source.substring(start, i), new SourceSpan(startLine, startColumn, line, column - 1)));
                continue;
            }
            if (c == '"' || c == '\'') {
                char quote = c;
                i++; column++;
                boolean closed = false;
                while (i < source.length()) {
                    char current = source.charAt(i);
                    if (current == '\n' || current == '\r') throw new ParseFailure("newline in literal", new Token(Kind.STRING, "", SourceSpan.point(startLine, startColumn)));
                    if (current == '\\') {
                        if (i + 1 >= source.length()) break;
                        i += 2; column += 2;
                        continue;
                    }
                    if (current == quote) { i++; column++; closed = true; break; }
                    i++; column++;
                }
                if (!closed) throw new ParseFailure("unterminated literal", new Token(Kind.STRING, "", SourceSpan.point(startLine, startColumn)));
                String raw = source.substring(start, i);
                SourceSpan literalSpan = new SourceSpan(startLine, startColumn, line, column - 1);
                out.add(quote == '"' ? new Token(Kind.STRING, raw, literalSpan)
                        : new Token(Kind.CHARACTER, raw.substring(1, raw.length() - 1), literalSpan));
                continue;
            }
            String two = i + 1 < source.length() ? source.substring(i, i + 2) : "";
            if (List.of("==", "!=", "<=", ">=", "::", "&&", "||", "++", "--", "<<").contains(two)) {
                i += 2; column += 2;
                out.add(new Token(Kind.SYMBOL, two, new SourceSpan(startLine, startColumn, line, column - 1)));
                continue;
            }
            if ("{}[]();,+-*/%=<>.:!&".indexOf(c) >= 0) {
                i++; column++;
                out.add(new Token(Kind.SYMBOL, String.valueOf(c), SourceSpan.point(startLine, startColumn)));
                continue;
            }
            throw new ParseFailure("unsupported character '" + c + "'", new Token(Kind.SYMBOL, String.valueOf(c), SourceSpan.point(startLine, startColumn)));
        }
        out.add(new Token(Kind.EOF, "", SourceSpan.point(line, column)));
        return out;
    }

    private String decodeString(Token token) {
        String raw = token.text.substring(1, token.text.length() - 1);
        StringBuilder decoded = new StringBuilder();
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c != '\\') { decoded.append(c); continue; }
            if (++i >= raw.length()) throw fail("unterminated string escape", token);
            decoded.append(switch (raw.charAt(i)) {
                case 'n' -> '\n'; case 'r' -> '\r'; case 't' -> '\t'; case 'b' -> '\b'; case 'f' -> '\f';
                case '\\' -> '\\'; case '\'' -> '\''; case '"' -> '"';
                default -> throw fail("unsupported string escape", token);
            });
        }
        return decoded.toString();
    }    private char decodeCharacter(Token token) {
        String raw = token.text;
        if (raw.length() == 1) return raw.charAt(0);
        if (raw.length() == 2 && raw.charAt(0) == '\\') {
            return switch (raw.charAt(1)) {
                case 'n' -> '\n'; case 'r' -> '\r'; case 't' -> '\t'; case 'b' -> '\b'; case 'f' -> '\f';
                case '\\' -> '\\'; case '\'' -> '\''; case '"' -> '"';
                default -> throw fail("unsupported character escape", token);
            };
        }
        throw fail("character literal must contain exactly one character", token);
    }
    private boolean peek(Kind k){return peek().kind==k;}private Token peek(){return tokens.get(at);}private Token previous(){return tokens.get(Math.max(0,at-1));}private Token advance(){if(!peek(Kind.EOF))at++;return previous();}private Token look(int n){return tokens.get(Math.min(at+n,tokens.size()-1));}private boolean check(String s){return peek().text.equals(s);}private boolean checkId(String s){return peek().kind==Kind.ID&&check(s);}private boolean match(String s){if(check(s)){advance();return true;}return false;}private boolean matchId(String s){return checkId(s)&&advance()!=null;}private Token expect(String s){if(!check(s))throw fail("expected '"+s+"'",peek());return advance();}private Token expect(Kind k,String m){if(!peek(k))throw fail(m,peek());return advance();}private ParseFailure fail(String m,Token t){return new ParseFailure(m,t);}private SourceSpan span(Token a,Token b){return new SourceSpan(a.span.startLine(),a.span.startColumn(),b.span.endLine(),b.span.endColumn());}
    private enum Kind{ID,NUMBER,STRING,CHARACTER,SYMBOL,EOF}private record Token(Kind kind,String text,SourceSpan span){}private static final class ParseFailure extends RuntimeException{final Token token;ParseFailure(String m,Token t){super(m);token=t;}}
}


