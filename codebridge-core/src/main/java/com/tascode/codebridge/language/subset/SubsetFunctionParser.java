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
    private final java.util.Set<String> knownTypes;
    public SubsetFunctionParser(Language language) { this(language, java.util.Set.of()); }
    public SubsetFunctionParser(Language language, java.util.Set<String> knownTypes) {
        this.language = java.util.Objects.requireNonNull(language);
        this.knownTypes = java.util.Set.copyOf(knownTypes);
    }
    private List<Token> tokens; private int at; private int loopDepth; private boolean parsingStreamChain;
    @Override public Language language(){ return language; }
    @Override public synchronized ParseResult parse(String source){
        List<Diagnostic> diagnostics=new ArrayList<>();
        if(source==null||source.isBlank()) return new ParseResult(null,List.of(Diagnostic.withoutLocation(Severity.ERROR,"CB-PARSE-EMPTY","Source code is empty.")));
        try { tokens=lex(source); at=0; loopDepth=0; parsingStreamChain=false; List<FunctionDeclaration> fs=new ArrayList<>(); while(!peek(Kind.EOF)) fs.add(function()); return new ParseResult(new Program(V,span(fs.isEmpty()?peek():tokens.get(0),previous()),fs),diagnostics); }
        catch(ParseFailure e){diagnostics.add(Diagnostic.at(Severity.ERROR,language==Language.CPP?"CB-PARSE-CPP-UNSUPPORTED":"CB-PARSE-C-UNSUPPORTED",e.getMessage(),e.token.span())); return new ParseResult(null,diagnostics);}
    }
    private FunctionDeclaration function(){Token start=expect(Kind.ID,"expected function return type"); String type=readType(start); Token name=expect(Kind.ID,"expected function name"); expect("("); List<Parameter> ps=new ArrayList<>(); if(!check(")")){ if(checkId("void")&&look(1).text.equals(")")){advance();} else do {Token t=expect(Kind.ID,"expected parameter type");String parameterType=readType(t);if(language==Language.JAVA&&match("[")){expect("]");parameterType+="[]";}if(language==Language.CPP&&parameterType.endsWith("[]"))match("&");Token n=expect(Kind.ID,"expected parameter name");ps.add(new Parameter(V,n.span(),parameterType,n.text));}while(match(",")); } expect(")"); BlockStatement body=block(); return new FunctionDeclaration(V,span(start,previous()),type,name.text,ps,body); }
    private BlockStatement block(){
        Token start=expect("{");
        List<Statement> ss=new ArrayList<>();
        while(!check("}")&&!peek(Kind.EOF)) {
            if(isType(peek().text)) ss.addAll(variableDeclarations(advance()));
            else ss.add(statement());
        }
        expect("}");
        return new BlockStatement(V,span(start,previous()),ss);
    }
    private Statement statement(){if(matchId("break")){Token start=previous();if(loopDepth==0)throw fail("break is only valid inside a loop",start);expect(";");return new BreakStatement(V,span(start,previous()));}if(matchId("continue")){Token start=previous();if(loopDepth==0)throw fail("continue is only valid inside a loop",start);expect(";");return new ContinueStatement(V,span(start,previous()));}if(matchId("for"))return forStatement(previous());if(matchId("do"))return doWhileStatement(previous());if(matchId("if")){Token start=previous();expect("(");Expression condition=expression();expect(")");Statement thenBranch=statement();Optional<Statement> elseBranch=matchId("else")?Optional.of(statement()):Optional.empty();return new IfStatement(V,span(start,previous()),condition,thenBranch,elseBranch);}if(matchId("while")){Token start=previous();expect("(");Expression condition=expression();expect(")");Statement body=loopBody();return new WhileStatement(V,span(start,previous()),condition,body);}if(check("{"))return block(); if(matchId("return")){Token s=previous();Optional<Expression> e=check(";")?Optional.empty():Optional.of(expression());expect(";");return new ReturnStatement(V,span(s,previous()),e);} if(isType(peek().text)){Token t=advance();return variableOrArrayDeclaration(t);}Expression e=expression();expect(";");return new ExpressionStatement(V,e.sourceSpan(),e);}
    private Statement variableOrArrayDeclaration(Token type){
        List<Statement> declarations=variableDeclarations(type);
        if(declarations.size()==1)return declarations.getFirst();
        return new BlockStatement(V,span(type,previous()),declarations);
    }
    private List<Statement> variableDeclarations(Token type){
        String declaredType=readType(type);
        List<Statement> declarations=new ArrayList<>();
        boolean javaArray=match("[");
        if(javaArray){
            expect("]");
            Token name=expect(Kind.ID,"expected array name");
            expect("=");
            boolean created=false;
            if(match("new")){
                created=true;
                Token allocatedType=expect(Kind.ID,"expected array element type");
                if(!readType(allocatedType).equals(declaredType))throw fail("array creation type must match the declaration type",allocatedType);
                expect("[");
                if(!check("]")) { Expression size=expression(); expect("]"); expect(";"); return List.of(new ArrayDeclaration(V,span(type,previous()),declaredType,name.text,size)); }
                expect("]");
            }
            if(!match("{"))throw fail(created?"array creation must have a size or initializer list":"array declaration requires an initializer list",peek());
            List<Expression> values=new ArrayList<>();
            if(!check("}")) do { values.add(expression()); } while(match(","));
            expect("}");expect(";");
            List<Statement> initialized=new ArrayList<>();
            SourceSpan declarationSpan=span(type,previous());
            initialized.add(new ArrayDeclaration(V,declarationSpan,declaredType,name.text,new LiteralExpression(V,name.span(),values.size())));
            NameExpression arrayName=new NameExpression(V,name.span(),name.text);
            for(int i=0;i<values.size();i++){
                LiteralExpression index=new LiteralExpression(V,name.span(),i);
                ArrayAccessExpression target=new ArrayAccessExpression(V,name.span(),arrayName,index);
                AssignmentExpression assignment=new AssignmentExpression(V,declarationSpan,target,values.get(i));
                initialized.add(new ExpressionStatement(V,declarationSpan,assignment));
            }
            return initialized;
        }
        do {
            Token name=expect(Kind.ID,"expected variable name");
            if(match("[")){
                Expression size=expression();expect("]");
                declarations.add(new ArrayDeclaration(V,span(type,previous()),declaredType,name.text,size));
            } else if(language==Language.CPP&&declaredType.endsWith("[]")&&match("(")){
                Expression size=expression();expect(")");
                declarations.add(new ArrayDeclaration(V,span(type,previous()),declaredType.substring(0,declaredType.length()-2),name.text,size));
            } else {
                Optional<Expression> initializer=match("=")?Optional.of(expression()):Optional.empty();
                declarations.add(new VariableDeclaration(V,span(type,previous()),name.text,declaredType,initializer));
            }
        } while(match(","));
        expect(";");
        return declarations;
    }    private Statement doWhileStatement(Token start){Statement body=loopBody();if(!matchId("while"))throw fail("expected while after do body",peek());expect("(");Expression condition=expression();expect(")");expect(";");return new DoWhileStatement(V,span(start,previous()),body,condition);}
    private Statement loopBody(){loopDepth++;try{return statement();}finally{loopDepth--;}}
    private Statement forStatement(Token start){expect("(");Optional<Statement> initializer;if(match(";"))initializer=Optional.empty();else if(isType(peek().text))initializer=Optional.of(statement());else{Expression initial=expression();expect(";");initializer=Optional.of(new ExpressionStatement(V,initial.sourceSpan(),initial));}Optional<Expression> condition=check(";")?Optional.empty():Optional.of(expression());expect(";");Optional<Expression> update=check(")")?Optional.empty():Optional.of(expression());expect(")");Statement body=loopBody();return new ForStatement(V,span(start,previous()),initializer,condition,update,body);}    private Expression expression(){Expression left=binary(0);if(match("=")){if(!(left instanceof NameExpression||left instanceof ArrayAccessExpression))throw fail("assignment target must be a variable or array element",previous());Expression value=expression();return new AssignmentExpression(V,span(tokenAt(left.sourceSpan()),tokenAt(value.sourceSpan())),left,value);}if(isCompoundAssignment(peek().text)){Token operator=advance();if(!(left instanceof NameExpression||left instanceof ArrayAccessExpression))throw fail("compound assignment requires a variable or array element",operator);Expression right=expression();String binaryOperator=operator.text.substring(0,operator.text.length()-1);Expression value=new BinaryExpression(V,span(tokenAt(left.sourceSpan()),tokenAt(right.sourceSpan())),binaryOperator,left,right);return new AssignmentExpression(V,span(tokenAt(left.sourceSpan()),tokenAt(right.sourceSpan())),left,value);}return left;} private boolean isCompoundAssignment(String operator){return List.of("+=","-=","*=","/=","%=","&=","|=","^=","<<=",">>=").contains(operator);}
    private static final String[][] OPS={{"||"},{"&&"},{"|"},{"^"},{"&"},{"==","!="},{"<",">","<=",">="},{"<<",">>"},{"+","-"},{"*","/","%"}};
    private Expression binary(int min){Expression left=primary();while(true){int p=precedence(peek().text);if(p<min)break;Token op=advance();Expression right=binary(p+1);left=new BinaryExpression(V,span(tokenAt(left.sourceSpan()),previous()),op.text,left,right);}return left;}
    private Token tokenAt(SourceSpan s){return new Token(Kind.ID,"",s);}
    private Expression primary() {
        if (check("++") || check("--")) {
            Token operator = advance();
            Expression operand = primary();
            if (!(operand instanceof NameExpression target)) throw fail("increment and decrement require a local name", operator);
            return new UpdateExpression(V, span(operator, tokenAt(operand.sourceSpan())), target, operator.text, true);
        }
        if (check("!") || check("-") || check("+") || check("~")) {
            Token operator = advance();
            Expression operand = primary();
            return new UnaryExpression(V, span(operator, tokenAt(operand.sourceSpan())), operator.text, operand);
        }
        Token token = advance();
        if (token.kind == Kind.NUMBER) {
            String number = token.text;
            char last = number.charAt(number.length() - 1);
            return number.indexOf('.') >= 0 || number.indexOf('e') >= 0 || number.indexOf('E') >= 0
                    || last == 'F' || last == 'f' || last == 'D' || last == 'd'
                    ? floatingLiteral(token) : integerLiteral(token);
        }
        if (token.kind == Kind.STRING) return new LiteralExpression(V, token.span(), decodeString(token));
        if (token.kind == Kind.CHARACTER) return new LiteralExpression(V, token.span(), decodeCharacter(token));
        if (token.kind == Kind.ID) {
            if (token.text.equals("true") || token.text.equals("false"))
                return new LiteralExpression(V, token.span(), Boolean.parseBoolean(token.text));
            StringBuilder qualifiedName = new StringBuilder(token.text);
            boolean qualified = false;
            while (check(".") || check("::")) {
                qualified = true;
                qualifiedName.append(advance().text);
                qualifiedName.append(expect(Kind.ID, "expected name after qualifier").text);
            }
            String name = qualifiedName.toString();
            if (language == Language.CPP && (name.equals("std::cin") || name.equals("cin")) && match(">>"))
                return new CallExpression(V, span(token, previous()), "__codebridge_cin", cinTargets());
            if ((name.equals("std::cout") || name.equals("cout")) && match("<<"))
                return new CallExpression(V, span(token, previous()), "__codebridge_cout", streamArguments());
            if (match("(")) return new CallExpression(V, span(token, previous()), name, callArguments(name));
            if (qualified && (language != Language.JAVA || name.contains("::") || name.indexOf('.') != name.lastIndexOf('.')))
                throw fail("qualified values are supported only for a single Java object field access", token);
            NameExpression expression = new NameExpression(V, qualified ? span(token, previous()) : token.span(), name);
            if (match("[")) {
                Expression index = expression();
                expect("]");
                return new ArrayAccessExpression(V, span(token, previous()), expression, index);
            }
            if (check("++") || check("--")) {
                Token operator = advance();
                return new UpdateExpression(V, span(token, previous()), expression, operator.text, false);
            }
            return expression;
        }
        if (token.text.equals("(")) {
            Expression expression = expression();
            expect(")");
            return expression;
        }
        throw fail("expected expression", token);
    }
    private LiteralExpression integerLiteral(Token token) {
        String digits = token.text;
        if (digits.matches("(?i).*[u].*")) throw fail("unsigned integer literals are outside the supported subset", token);
        boolean longSuffix = digits.endsWith("L") || digits.endsWith("l");
        if (longSuffix) digits = digits.substring(0, digits.length() - 1);
        int radix = digits.startsWith("0x") || digits.startsWith("0X") ? 16 : digits.length() > 1 && digits.startsWith("0") ? 8 : 10;
        String valueText = radix == 16 ? digits.substring(2) : digits;
        try {
            long value = Long.parseLong(valueText, radix);
            if (!longSuffix && radix == 10 && value <= Integer.MAX_VALUE) return new LiteralExpression(V, token.span(), (int) value);
            if (!longSuffix && radix != 10 && value > Integer.MAX_VALUE) throw fail("hexadecimal or octal literal requiring an unsigned type is outside the supported subset", token);
            if (!longSuffix && radix != 10) return new LiteralExpression(V, token.span(), (int) value);
            if (!longSuffix && value <= Integer.MAX_VALUE) return new LiteralExpression(V, token.span(), (int) value);
            return new LiteralExpression(V, token.span(), value);
        } catch (NumberFormatException exception) {
            throw fail("integer literal is invalid or exceeds the supported 64-bit range", token);
        }
    }
    private LiteralExpression floatingLiteral(Token token) {
        String text = token.text;
        char suffix = text.charAt(text.length() - 1);
        if (suffix == 'f' || suffix == 'F') {
            try {
                float value = Float.parseFloat(text.substring(0, text.length() - 1));
                if (!Float.isFinite(value)) throw fail("floating-point literal exceeds the supported float range", token);
                return new LiteralExpression(V, token.span(), value);
            } catch (NumberFormatException exception) { throw fail("invalid floating-point literal", token); }
        }
        if (suffix == 'd' || suffix == 'D') text = text.substring(0, text.length() - 1);
        try {
            double value = Double.parseDouble(text);
            if (!Double.isFinite(value)) throw fail("floating-point literal exceeds the supported double range", token);
            return new LiteralExpression(V, token.span(), value);
        } catch (NumberFormatException exception) { throw fail("invalid floating-point literal", token); }
    }    private List<Expression> cinTargets() {
        List<Expression> targets = new ArrayList<>();
        do {
            Token name = expect(Kind.ID, "C++ input extraction requires a local variable name");
            NameExpression target = new NameExpression(V, name.span(), name.text);
            if (match("[")) {
                Expression index = expression();
                expect("]");
                targets.add(new ArrayAccessExpression(V, span(name, previous()), target, index));
            } else targets.add(target);
        } while (match(">>"));
        return targets;
    }    private List<Expression> streamArguments() {
        List<Expression> items = new ArrayList<>();
        do {
            if (checkId("std") && look(1).text.equals("::") && look(2).text.equals("endl")
                    || language == Language.CPP && checkId("endl")) {
                Token start = advance();
                Token end = start;
                if (start.text.equals("std")) { advance(); end = advance(); }
                items.add(new CallExpression(V, span(start, end), "__codebridge_endl", List.of()));
            } else { parsingStreamChain = true; try { items.add(expression()); } finally { parsingStreamChain = false; } }
        } while (match("<<"));
        return items;
    }
    private List<Expression> callArguments(String callee) {
        List<Expression> arguments = new ArrayList<>();
        if (callee.equals("scanf")) {
            if (!check(")")) {
                arguments.add(expression());
                while (match(",")) {
                    expect("&");
                    Token target = expect(Kind.ID, "scanf destination must be a local variable");
                    arguments.add(new NameExpression(V, target.span(), target.text));
                }
            }
        } else if (!check(")")) {
            do { arguments.add(expression()); } while (match(","));
        }
        expect(")");
        return arguments;
    }    private int precedence(String op){if(parsingStreamChain&&op.equals("<<"))return -1;for(int i=0;i<OPS.length;i++)for(String candidate:OPS[i])if(candidate.equals(op))return i;return -1;}
    private boolean isType(String s){return knownTypes.contains(s)||List.of("int","long","short","char","float","double","_Bool","bool","string","String").contains(s)||(language==Language.JAVA&&s.equals("byte"))||(language==Language.CPP&&s.equals("std")&&List.of("string","vector","int8_t").contains(look(2).text));} private String readType(Token first){if(language==Language.CPP&&first.text.equals("std")){expect("::");Token name=expect(Kind.ID,"expected string, int8_t, or vector after std::");if(name.text.equals("vector")){expect("<");Token element=expect(Kind.ID,"expected vector element type");String elementType=readType(element);expect(">");return elementType+"[]";}if(name.text.equals("int8_t"))return "byte";if(!name.text.equals("string"))throw fail("only std::string, std::int8_t, and supported std::vector types are accepted",name);return "string";}if(language==Language.CPP&&first.text.equals("long")){if(match("long"))return "long";return "c-long";}if(language==Language.C&&first.text.equals("long"))return "c-long";return first.text;}
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
                if (c == '0' && i + 1 < source.length() && (source.charAt(i + 1) == 'x' || source.charAt(i + 1) == 'X')) {
                    i += 2; column += 2;
                    int digitsStart = i;
                    while (i < source.length() && Character.digit(source.charAt(i), 16) >= 0) { i++; column++; }
                    if (i == digitsStart) throw new ParseFailure("hexadecimal integer literal requires at least one digit", new Token(Kind.NUMBER, "0x", SourceSpan.point(startLine, startColumn)));
                    while (i < source.length() && "uUlL".indexOf(source.charAt(i)) >= 0) { i++; column++; }
                    out.add(new Token(Kind.NUMBER, source.substring(start, i), new SourceSpan(startLine, startColumn, line, column - 1)));
                    continue;
                }
                i++; column++;
                while (i < source.length() && Character.isDigit(source.charAt(i))) { i++; column++; }
                boolean floating = false;
                if (i < source.length() && source.charAt(i) == '.') {
                    floating = true; i++; column++;
                    while (i < source.length() && Character.isDigit(source.charAt(i))) { i++; column++; }
                }
                if (i < source.length() && (source.charAt(i) == 'e' || source.charAt(i) == 'E')) {
                    floating = true; i++; column++;
                    if (i < source.length() && (source.charAt(i) == '+' || source.charAt(i) == '-')) { i++; column++; }
                    int exponentStart = i;
                    while (i < source.length() && Character.isDigit(source.charAt(i))) { i++; column++; }
                    if (i == exponentStart) throw new ParseFailure("malformed floating-point exponent", new Token(Kind.NUMBER, source.substring(start, i), SourceSpan.point(startLine, startColumn)));
                }
                if (i < source.length() && "fFdD".indexOf(source.charAt(i)) >= 0) { floating = true; i++; column++; }
                else if (floating && i < source.length() && (source.charAt(i) == 'L' || source.charAt(i) == 'l')) {
                    throw new ParseFailure("C long double literals have no direct Java primitive equivalent", new Token(Kind.NUMBER, source.substring(start, i + 1), SourceSpan.point(startLine, startColumn)));
                } else if (!floating && i < source.length() && "uUlL".indexOf(source.charAt(i)) >= 0) {
                    while (i < source.length() && "uUlL".indexOf(source.charAt(i)) >= 0) { i++; column++; }
                }
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
            String three = i + 2 < source.length() ? source.substring(i, i + 3) : "";
            if (List.of("<<=", ">>=").contains(three)) {
                i += 3; column += 3;
                out.add(new Token(Kind.SYMBOL, three, new SourceSpan(startLine, startColumn, line, column - 1)));
                continue;
            }
            String two = i + 1 < source.length() ? source.substring(i, i + 2) : "";
            if (List.of("==", "!=", "<=", ">=", "::", "&&", "||", "++", "--", "<<", ">>", "+=", "-=", "*=", "/=", "%=", "&=", "|=", "^=").contains(two)) {
                i += 2; column += 2;
                out.add(new Token(Kind.SYMBOL, two, new SourceSpan(startLine, startColumn, line, column - 1)));
                continue;
            }
            if ("{}[]();,+-*/%=<>.:!&|^~".indexOf(c) >= 0) {
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


