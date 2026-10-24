package com.tascode.codebridge.diagnostic;

/** A one-based source location, with an optional end position. */
public record SourceSpan(int startLine, int startColumn, int endLine, int endColumn) {

    public SourceSpan {
        if (startLine < 1 || startColumn < 1) {
            throw new IllegalArgumentException("Start line and column must be at least 1");
        }
        if (endLine < startLine || endColumn < 1) {
            throw new IllegalArgumentException("End position must not precede the start position");
        }
        if (endLine == startLine && endColumn < startColumn) {
            throw new IllegalArgumentException("End position must not precede the start position");
        }
    }

    public static SourceSpan point(int line, int column) {
        return new SourceSpan(line, column, line, column);
    }
}
