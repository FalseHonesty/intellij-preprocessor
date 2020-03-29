package dev.falsehonesty

val ALLOWED_TYPES = listOf("JAVA")

enum class PreprocessorState {
    NONE,
    IF,
    ELSE
}

enum class PreprocessorDirective {
    IF,
    IFDEF,
    ELSE,
    ENDIF
}