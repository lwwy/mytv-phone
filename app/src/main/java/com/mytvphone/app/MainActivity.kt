package com.mytvphone.app

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.util.UnstableApi
import com.mytvphone.app.data.PlaylistParser
import com.mytvphone.app.ui.AppNav
import com.mytvphone.app.ui.MyTvPhoneTheme
import com.mytvphone.app.util.AppLog
import com.mytvphone.app.util.resolveDisplayName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// PlayerController 整体标了 Media3 的 @UnstableApi，这里与 ViewModel、播放页一样显式 opt-in
@androidx.annotation.OptIn(UnstableApi::class)
class MainActivity : ComponentActivity() {

    private val vm: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 尽早初始化：崩溃捕获与设备信息头要在任何业务逻辑之前就位
        AppLog.init(this)
        enableEdgeToEdge()
        setContent {
            val settings by vm.settings.collectAsStateWithLifecycle()
            MyTvPhoneTheme(dynamicColor = settings.dynamicColor) {
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
        vm.controller.pauseForLifecycle()
    }

    override fun onResume() {
        super.onResume()
        vm.controller.resumeForLifecycle()
    }

    /** 支持「分享纯文本」与「用其他应用打开 .m3u/.txt」两种导入入口。 */
    private fun handleIntent(intent: Intent?) {
        intent ?: return
        AppLog.i("Main", "handleIntent · action=${intent.action} · data=${AppLog.redact(intent.data?.toString())}")
        when (intent.action) {
            Intent.ACTION_SEND -> {
                val text = intent.getStringExtra(Intent.EXTRA_TEXT)
                val stream = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri
                }
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
                runCatching {
                    // 有界读取：被误分享进来的是视频等大文件时直接失败，不撑爆内存
                    contentResolver.openInputStream(uri)?.use {
                        PlaylistParser.decodeAuto(PlaylistParser.readBounded(it))
                    }
                }.getOrNull()
            }
            if (text.isNullOrBlank()) {
                AppLog.w("Main", "外部分享读取失败 · label=$label · uri=${AppLog.redact(uri.toString())}")
                vm.showToast("读取文件失败：$label")
            } else {
                AppLog.i("Main", "外部分享读取成功 · label=$label · ${text.length} 字符")
                vm.setExternalImport(resolveDisplayName(this@MainActivity, uri) ?: label, text)
            }
        }
    }
}
