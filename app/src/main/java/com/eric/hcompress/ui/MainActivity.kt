package com.eric.hcompress.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.OpenableColumns
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.eric.hcompress.engine.HuffmanEngine
import kotlinx.coroutines.*
import java.io.File

class MainActivity : AppCompatActivity() {

    private var fileUri: Uri? = null
    private var fileName: String = ""
    private var inputSize: Int = 0
    private var isDecompress = false
    private lateinit var statusText: TextView
    private lateinit var resultText: TextView
    private lateinit var progressBar: ProgressBar

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(40, 80, 40, 40)
        }
        layout.addView(TextView(this).apply { text = "hcompress"; textSize = 26f; gravity = android.view.Gravity.CENTER })
        layout.addView(TextView(this).apply { text = "Canonical Huffman 压缩工具"; textSize = 13f; gravity = android.view.Gravity.CENTER; setTextColor(0xFF888888.toInt()) })

        statusText = TextView(this).apply { text = "选择文件开始"; textSize = 15f; gravity = android.view.Gravity.CENTER; setPadding(0, 20, 0, 10) }
        layout.addView(statusText)

        layout.addView(Button(this).apply { text = "选择文件"; setOnClickListener { pickFile() } })

        val modeBar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, 8, 0, 8) }
        modeBar.addView(Button(this).apply { text = "压缩"; setOnClickListener { isDecompress = false; statusText.text = "模式: 压缩" } },
            LinearLayout.LayoutParams(0, -2, 1f))
        modeBar.addView(Button(this).apply { text = "解压"; setOnClickListener { isDecompress = true; statusText.text = "模式: 解压" } },
            LinearLayout.LayoutParams(0, -2, 1f))
        layout.addView(modeBar)

        progressBar = ProgressBar(this).apply { visibility = android.view.View.GONE }
        layout.addView(progressBar)

        layout.addView(Button(this).apply { text = "开始"; setOnClickListener { process() } })

        resultText = TextView(this).apply { text = ""; textSize = 13f; setPadding(0, 16, 0, 0); isSingleLine = false }
        layout.addView(resultText)

        layout.addView(Button(this).apply {
            text = "输出至: Download/hcompress/"; setOnClickListener {
                toast("文件保存在 下载/hcompress/ 文件夹")
            }
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = 20 })

        setContentView(layout)
    }

    private fun pickFile() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE); type = "*/*"
        }
        startActivityForResult(intent, 1)
    }

    @Deprecated("Use Activity Result API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 1 && resultCode == RESULT_OK && data?.data != null) {
            val uri = data.data!!
            // Take persistent permission
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            fileUri = uri
            contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) fileName = cursor.getString(idx)
                }
            }
            statusText.text = "已选择: $fileName"
        }
    }

    private fun process() {
        val uri = fileUri ?: run { toast("请先选择文件"); return }
        progressBar.visibility = android.view.View.VISIBLE
        resultText.text = "处理中..."

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val inputBytes = contentResolver.openInputStream(uri)?.readBytes() ?: throw Exception("无法读取文件")
                if (isDecompress) {
                    val mag = byteArrayOf('H'.code.toByte(), 'C'.code.toByte(), 'F'.code.toByte(), 0x1A.toByte())
                    if (!inputBytes.take(4).toByteArray().contentEquals(mag)) {
                        withContext(Dispatchers.Main) { toast("仅支持 HCF 格式。其他格式请用 PC 版") }
                        return@launch
                    }
                    val blens = IntArray(256) { inputBytes[12 + it].toInt() and 0xFF }
                    val maxLen = blens.maxOrNull() ?: 1
                    val origSize = (0..7).fold(0L) { a, i -> a or ((inputBytes[268 + i].toLong() and 0xFF) shl (i * 8)) }.toInt()
                    val blCount = IntArray(maxLen + 1); blens.forEach { if (it > 0) blCount[it]++ }
                    val baseCode = IntArray(maxLen + 1); var code = 0
                    for (l in 1..maxLen) { baseCode[l] = code; code = (code + blCount[l]) shl 1 }
                    val symOff = IntArray(maxLen + 2); val syms = mutableListOf<Int>()
                    for (l in 1..maxLen) { symOff[l] = syms.size; for (s in 0..255) if (blens[s] == l) syms.add(s) }
                    symOff[maxLen + 1] = syms.size
                    val decoded = HuffmanEngine.decode(inputBytes.copyOfRange(276, inputBytes.size), baseCode, symOff, syms.toIntArray(), maxLen, origSize)
                        ?: throw Exception("解码失败")
                    val outName = fileName.removeSuffix(".hcf").ifEmpty { "decompressed" }
                    val savedPath = saveToDownloads(outName, decoded)
                    withContext(Dispatchers.Main) { resultText.text = "解压完成!\n${inputBytes.size} → ${origSize} 字节\n保存至: $savedPath" }
                } else {
                    val freq = HuffmanEngine.freqTable(inputBytes)
                    val (codes, blens) = HuffmanEngine.buildCanonical(freq)
                    val encoded = HuffmanEngine.encode(inputBytes, codes, blens) ?: throw Exception("编码失败")
                    val hdr = ByteArray(276)
                    "HCF".toByteArray().copyInto(hdr, 0)
                    hdr[4] = 1; hdr[5] = 0; hdr[6] = ((6 and 0xF) shl 1).toByte()
                    for (i in 0..255) hdr[12 + i] = blens[i].toByte()
                    for (i in 0..7) hdr[268 + i] = ((inputBytes.size shr (i * 8)) and 0xFF).toByte()
                    val outName = "$fileName.hcf"
                    val savedPath = saveToDownloads(outName, hdr + encoded)
                    val ratio = (hdr.size + encoded.size) * 100.0 / inputBytes.size
                    withContext(Dispatchers.Main) { resultText.text = "压缩完成!\n${inputBytes.size} → ${hdr.size + encoded.size} 字节 (${ratio.toInt()}%)\n保存至: $savedPath" }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { resultText.text = "错误: ${e.message}" }
            } finally {
                withContext(Dispatchers.Main) { progressBar.visibility = android.view.View.GONE }
            }
        }
    }

    private fun saveToDownloads(name: String, data: ByteArray): String {
        val values = android.content.ContentValues().apply {
            put(android.provider.MediaStore.Downloads.DISPLAY_NAME, name)
            put(android.provider.MediaStore.Downloads.MIME_TYPE, "application/octet-stream")
            put(android.provider.MediaStore.Downloads.RELATIVE_PATH, "Download/hcompress")
        }
        val uri = contentResolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw Exception("无法创建文件")
        contentResolver.openOutputStream(uri)?.use { it.write(data) }
            ?: throw Exception("无法写入文件")
        return "Download/hcompress/$name"
    }

    private fun toast(msg: String) { runOnUiThread { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() } }
}
