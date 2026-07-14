package com.mbv.viberdbviewer.data

internal fun sqliteLikePattern(query: String): String =
    buildString(query.length + LIKE_PATTERN_PADDING) {
        append('%')
        query.forEach { character ->
            if (character == LIKE_ESCAPE || character == '%' || character == '_') {
                append(LIKE_ESCAPE)
            }
            append(character)
        }
        append('%')
    }

internal fun requiresKotlinCaseFolding(query: String): Boolean = query.any { it.code > ASCII_MAX_CODE_POINT }

internal fun Boolean.sqliteFlag(): String = if (this) "1" else "0"

private const val LIKE_ESCAPE = '\\'
private const val LIKE_PATTERN_PADDING = 2
private const val ASCII_MAX_CODE_POINT = 0x7f
