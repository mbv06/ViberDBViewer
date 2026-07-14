package com.mbv.viberdbviewer.data

import android.database.Cursor

internal fun Cursor.stringOrNull(index: Int): String? = if (isNull(index)) null else getString(index)

internal fun Cursor.longOrNull(index: Int): Long? = if (isNull(index)) null else getLong(index)
