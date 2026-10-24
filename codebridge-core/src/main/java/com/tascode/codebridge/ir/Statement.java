package com.tascode.codebridge.ir;
public sealed interface Statement extends IrNode permits BlockStatement, VariableDeclaration, ExpressionStatement, ReturnStatement { }
