package com.mytvphone.app.util

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns

/**
 * 从 content:// URI 读取显示文件名。集中放置，避免 MainActivity 与导入面板各写一份。
 */
fun resolveDisplayName(context: Context, uri: Uri): String? = try {
    context.contentResolver.query(uri, null, null, null, null)?.use { c ->
        val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (i >= 0 && c.moveToFirst()) c.getString(i) else null
    }
} catch (e: Exception) {
    null
}
