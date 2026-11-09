package com.mytvphone.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.lifecycle.lifecycleScope
import com.mytvphone.app.data.PlaylistParser
import com.mytvphone.app.ui.AppNav
import com.mytvphone.app.ui.MyTvPhoneTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {

    private val vm: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MyTvPhoneTheme {
                AppNav(vm = vm)
            }
        }
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onPause() {
        super.onPause()
        vm.controller.pause()
    }

    override fun onResume() {
        super.onResume()
        vm.controller.resume()
    }

    /** 支持「分享纯文本」与「用其他应用打开 .m3u/.txt」两种导入入口。 */
    @Suppress("DEPRECATION")
    private fun handleIntent(intent: Intent?) {
        intent ?: return
        when (intent.action) {
            Intent.ACTION_SEND -> {
                val text = intent.getStringExtra(Intent.EXTRA_TEXT)
                val stream = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
                if (!text.isNullOrBlank()) {
                    vm.setExternalImport("分享导入", text)
                } else if (stream != null) {
                    readAndImport(stream, "分享的文件")
                }
            }

            Intent.ACTION_VIEW -> intent.data?.let { readAndImport(it, "外部文件") }
        }
    }

    private fun readAndImport(uri: Uri, label: String) {
        lifecycleScope.launch {
            val text = withContext(Dispatchers.IO) {
                try {
                    contentResolver.openInputStream(uri)?.use { PlaylistParser.decodeAuto(it.readBytes()) }
                } catch (e: Exception) {
                    null
                }
            }
            if (text.isNullOrBlank()) {
                vm.showToast("读取文件失败：$label")
            } else {
                vm.setExternalImport(displayName(uri) ?: label, text)
            }
        }
    }

    private fun displayName(uri: Uri): String? = try {
        contentResolver.query(uri, null, null, null, null)?.use { c ->
            val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (i >= 0 && c.moveToFirst()) c.getString(i) else null
        }
    } catch (e: Exception) {
        null
    }
}
