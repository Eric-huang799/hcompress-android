package com.eric.hcompress.ui

import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumn
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.eric.hcompress.engine.HuffmanEngine
import com.eric.hcompress.plugin.PluginManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : AppCompatActivity() {

    private var selectedFileUri: Uri? = null
    private var selectedFileName: String = ""
    private var isDecompress = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Init: load built-in plugins
        lifecycleScope.launch { checkPlugins() }

        // Pick file button
        findViewById<com.google.android.material.button.MaterialButton>(R.id.btn_pick_file).setOnClickListener {
            pickFile.launch(arrayOf("*/*"))
        }

        // Compress
        findViewById<com.google.android.material.button.MaterialButton>(R.id.btn_compress).setOnClickListener {
            isDecompress = false; selectedFileUri?.let { process(it) }
        }

        // Decompress
        findViewById<com.google.android.material.button.MaterialButton>(R.id.btn_decompress).setOnClickListener {
            isDecompress = true; selectedFileUri?.let { process(it) }
        }

        // Plugin store
        findViewById<com.google.android.material.button.MaterialButton>(R.id.btn_plugins).setOnClickListener {
            showPluginStore()
        }
    }

    private val pickFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let {
            selectedFileUri = it
            contentResolver.query(it, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(OpenableColumn.DISPLAY_NAME)
                    if (idx >= 0) selectedFileName = cursor.getString(idx)
                }
            }
            findViewById<com.google.android.material.textview.MaterialTextView>(R.id.tv_file_name)
                .text = selectedFileName
        }
    }

    private fun process(uri: Uri) {
        lifecycleScope.launch {
            try {
                val inputBytes = withContext(Dispatchers.IO) {
                    contentResolver.openInputStream(uri)?.readBytes() ?: ByteArray(0)
                }
                if (inputBytes.isEmpty()) { toast("无法读取文件"); return@launch }

                if (isDecompress) {
                    // Try HCF first, then format plugins
                    val hcfMagic = byteArrayOf('H'.code.toByte(), 'C'.code.toByte(), 'F'.code.toByte(), 0x1A)
                    if (inputBytes.take(4).toByteArray().contentEquals(hcfMagic)) {
                        decompressHCF(inputBytes, selectedFileName)
                    } else {
                        toast("非 HCF 格式，请通过插件支持。\n即将支持 ZIP/GZIP/7z/RAR 等格式")
                        // TODO: integrate format plugin detection
                    }
                } else {
                    compressHCF(inputBytes, selectedFileName)
                }
            } catch (e: Exception) {
                toast("处理失败: ${e.message}")
            }
        }
    }

    private suspend fun compressHCF(data: ByteArray, name: String) = withContext(Dispatchers.IO) {
        val freq = HuffmanEngine.freqTable(data)
        val (codes, blens) = HuffmanEngine.buildCanonical(freq)
        val encoded = HuffmanEngine.encode(data, codes, blens) ?: return@withContext toast("编码失败")
        val header = buildHeader(blens, data.size)
        val output = header + encoded
        val outFile = File(getExternalFilesDir(null), "$name.hcf")
        outFile.writeBytes(output)
        withContext(Dispatchers.Main) {
            toast("压缩完成！${data.size} → ${output.size} 字节 (${output.size * 100 / maxOf(data.size, 1)}%)")
        }
    }

    private suspend fun decompressHCF(data: ByteArray, name: String) = withContext(Dispatchers.IO) {
        // Parse header
        val blens = IntArray(256) { data[12 + it].toInt() and 0xFF }
        val origSize = (0..7).fold(0L) { acc, i -> acc or ((data[268 + i].toLong() and 0xFF) shl (i * 8)) }.toInt()
        val payload = data.copyOfRange(276, data.size)

        // Build decode tables
        val blCount = IntArray(blens.maxOrNull()?.plus(1) ?: 1)
        for (bl in blens) if (bl > 0) blCount[bl]++
        val baseCode = IntArray(blCount.size)
        var code = 0
        for (len in 1 until blCount.size) {
            baseCode[len] = code
            code = (code + blCount[len]) shl 1
        }
        val symOff = IntArray(blCount.size + 1)
        val symsFlat = mutableListOf<Int>()
        for (len in 1 until blCount.size) {
            symOff[len] = symsFlat.size
            for (sym in 0..255) if (blens[sym] == len) symsFlat.add(sym)
        }
        symOff[blCount.size] = symsFlat.size

        val decoded = HuffmanEngine.decode(payload, baseCode, symOff, symsFlat.toIntArray(),
            blCount.size - 1, origSize) ?: return@withContext toast("解码失败")
        val outName = name.removeSuffix(".hcf")
        val outFile = File(getExternalFilesDir(null), outName)
        outFile.writeBytes(decoded)
        withContext(Dispatchers.Main) {
            toast("解压完成！${data.size} → ${origSize} 字节")
        }
    }

    private fun buildHeader(blens: IntArray, origSize: Int): ByteArray {
        val h = ByteArray(276)
        "HCF".toByteArray().copyInto(h, 0)
        h[4] = 1; h[5] = 0  // version 1, LE
        h[6] = ((6 and 0xF) shl 1).toByte()  // flags: level 6
        for (i in 0..255) h[12 + i] = blens[i].toByte()
        for (i in 0..7) h[268 + i] = ((origSize shr (i * 8)) and 0xFF).toByte()
        // CRC-16 placeholder
        return h
    }

    private suspend fun checkPlugins() {
        val installed = PluginManager.listInstalled(this)
        if (installed.isEmpty()) {
            toast("内置插件已就绪。前往插件商店获取更多格式支持")
        }
    }

    private fun showPluginStore() {
        lifecycleScope.launch {
            val plugins = PluginManager.fetchAvailable()
            if (plugins.isEmpty()) {
                toast("暂无可用的社区插件。请检查网络连接。")
                return@launch
            }
            val names = plugins.map { "${it.name} (${it.type}) — ${it.description}" }.toTypedArray()
            MaterialAlertDialogBuilder(this@MainActivity)
                .setTitle("插件商店 — 社区插件")
                .setItems(names) { _, idx ->
                    lifecycleScope.launch {
                        toast("正在安装 ${plugins[idx].name}…")
                        val ok = PluginManager.install(this@MainActivity, plugins[idx])
                        toast(if (ok) "${plugins[idx].name} 安装成功" else "安装失败")
                    }
                }
                .setNegativeButton("关闭", null)
                .show()
        }
    }

    private fun toast(msg: String) {
        runOnUiThread { Toast.makeText(this, msg, Toast.LENGTH_LONG).show() }
    }
}
