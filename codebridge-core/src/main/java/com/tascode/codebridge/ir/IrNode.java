package com.tascode.codebridge.ir;
import com.tascode.codebridge.diagnostic.SourceSpan;
public sealed interface IrNode permits Expression, Statement, Program, FunctionDeclaration, Parameter, TypeReference, TypeParameter, GlobalVariableDeclaration, EnumDeclaration, EnumDeclaration.Enumerator, ClassDeclaration, FieldDeclaration, MethodDeclaration, ConstructorDeclaration { int irVersion(); SourceSpan sourceSpan(); }
