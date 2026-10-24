package com.tascode.codebridge.ir;
public sealed interface Statement extends IrNode permits BlockStatement, VariableDeclaration, ExpressionStatement, ReturnStatement, IfStatement, WhileStatement, ForStatement, BreakStatement, ContinueStatement, DoWhileStatement, ArrayDeclaration { }
