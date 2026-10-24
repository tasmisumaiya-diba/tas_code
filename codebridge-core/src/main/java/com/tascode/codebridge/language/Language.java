package com.tascode.codebridge.language;

/** Supported source and target languages. */
public enum Language {
    C("c"), CPP("cpp"), JAVA("java");
    private final String id;
    Language(String id) { this.id = id; }
    public String id() { return id; }
    public static Language fromId(String id) {
        if (id == null) throw new IllegalArgumentException("Language id must not be null");
        for (Language language : values()) if (language.id.equalsIgnoreCase(id.trim())) return language;
        throw new IllegalArgumentException("Unsupported language: " + id);
    }
}
