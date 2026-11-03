package com.tascode.codebridge.ir;

/** Semantic type algebra shared by frontends and backends. Legacy declarations may still carry their spelling. */
public sealed interface IrType extends IrNode permits PrimitiveType, NamedType, TypeVariableType,
        ArrayType, PointerType, ReferenceType, FunctionType, WildcardType, CollectionType,
        VoidType, NullType, UnknownType { }
