package com.eric.hcompress.plugin

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File

/** Plugin metadata fetched from GitHub Releases. */
data class PluginInfo(
    val name: String, val version: String, val type: String,
    val description: String, val downloadUrl: String, val size: Long
)

/** Manages plugin discovery, download, and lifecycle. */
object PluginManager {
    private const val PLUGIN_DIR = "plugins"
    private const val REPO_API = "https://api.github.com/repos/Eric-huang799/hcompress-plugins/releases/latest"
    private val client = OkHttpClient()
    private val gson = Gson()

    /** Fetch available plugins from GitHub Releases. */
    suspend fun fetchAvailable(): List<PluginInfo> = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder().url(REPO_API).build()
            val resp = client.newCall(req).execute()
            val body = resp.body?.string() ?: return@withContext emptyList()
            // Parse release assets as plugin list
            val json = gson.fromJson(body, Map::class.java)
            val assets = json["assets"] as? List<Map<String, Any>> ?: return@withContext emptyList()
            assets.mapNotNull { asset ->
                val name = asset["name"] as? String ?: return@mapNotNull null
                if (!name.endsWith(".py") && !name.endsWith(".jar")) return@mapNotNull null
                PluginInfo(
                    name = name.removeSuffix(".py").removeSuffix(".jar"),
                    version = json["tag_name"] as? String ?: "0.0.0",
                    type = if (name.endsWith(".py")) "python" else "java",
                    description = asset["label"] as? String ?: "",
                    downloadUrl = asset["browser_download_url"] as? String ?: "",
                    size = (asset["size"] as? Double)?.toLong() ?: 0L
                )
            }
        } catch (e: Exception) { emptyList() }
    }

    /** Download and install a plugin. */
    suspend fun install(context: Context, plugin: PluginInfo): Boolean = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder().url(plugin.downloadUrl).build()
            val resp = client.newCall(req).execute()
            val data = resp.body?.bytes() ?: return@withContext false
            val dir = File(context.filesDir, PLUGIN_DIR)
            dir.mkdirs()
            val dest = File(dir, "${plugin.name}.py")
            dest.writeBytes(data)
            true
        } catch (e: Exception) { false }
    }

    /** List installed plugins. */
    fun listInstalled(context: Context): List<String> {
        val dir = File(context.filesDir, PLUGIN_DIR)
        return dir.listFiles()?.map { it.name }?.filter { it.endsWith(".py") } ?: emptyList()
    }

    /** Remove a plugin. */
    fun uninstall(context: Context, name: String): Boolean {
        return File(context.filesDir, "$PLUGIN_DIR/$name.py").delete()
    }
}
