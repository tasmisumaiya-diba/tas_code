package com.tascode.codebridge.ir;
public sealed interface Expression extends IrNode permits LiteralExpression, NameExpression, BinaryExpression, CallExpression { }
