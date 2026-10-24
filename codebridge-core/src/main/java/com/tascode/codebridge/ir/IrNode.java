package com.tascode.codebridge.ir;
import com.tascode.codebridge.diagnostic.SourceSpan;
public sealed interface IrNode permits Expression, Statement, Program, FunctionDeclaration, Parameter { int irVersion(); SourceSpan sourceSpan(); }
