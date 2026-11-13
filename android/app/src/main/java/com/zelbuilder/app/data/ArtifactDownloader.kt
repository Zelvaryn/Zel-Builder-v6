package com.zelbuilder.app.data

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

object ArtifactDownloader {

    private var appContext: Context? = null

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .build()

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    private fun authed(token: String, url: String): Request =
        Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $token")
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .get()
            .build()

    /**
     * Downloads the artifact ZIP of a run, extracts app-debug.apk and stores it in
     * the public Downloads folder. Returns the saved path.
     */
    suspend fun downloadAndSaveApk(
        token: String,
        owner: String,
        repo: String,
        runId: Long,
        appName: String
    ): String = withContext(Dispatchers.IO) {
        val ctx = appContext ?: throw IllegalStateException("ArtifactDownloader.init(context) was not called")

        var archiveUrl: String? = null
        var tries = 0
        while (archiveUrl == null && tries < 6) {
            val listReq = authed(token, "https://api.github.com/repos/$owner/$repo/actions/runs/$runId/artifacts")
            client.newCall(listReq).execute().use { resp ->
                if (resp.isSuccessful) {
                    val arr = JSONObject(resp.body?.string().orEmpty()).getJSONArray("artifacts")
                    if (arr.length() > 0) archiveUrl = arr.getJSONObject(0).getString("archive_download_url")
                }
            }
            tries++
            if (archiveUrl == null) delay(3000)
        }
        val url = archiveUrl ?: throw IOException("No artifact found for run $runId")

        var apkBytes: ByteArray? = null
        client.newCall(authed(token, url)).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("Artifact download failed (${resp.code})")
            ZipInputStream(resp.body!!.byteStream()).use { zip ->
                var fallback: ByteArray? = null
                var entry = zip.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory) {
                        if (entry.name.endsWith("app-debug.apk")) {
                            apkBytes = zip.readBytes()
                            break
                        } else if (entry.name.endsWith(".apk") && fallback == null) {
                            fallback = zip.readBytes()
                        }
                    }
                    entry = zip.nextEntry
                }
                if (apkBytes == null) apkBytes = fallback
            }
        }
        val bytes = apkBytes ?: throw IOException("app-debug.apk not found inside artifact")

        val safeName = appName.replace(Regex("[^A-Za-z0-9_-]"), "_").ifBlank { "app" }
        val fileName = "$safeName-${System.currentTimeMillis() / 1000}.apk"

        // Private copy (used by the installer through FileProvider).
        val privateDir = ctx.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: ctx.filesDir
        privateDir.mkdirs()
        val privateFile = File(privateDir, fileName)
        privateFile.writeBytes(bytes)

        // Public copy in Downloads.
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                    put(MediaStore.Downloads.MIME_TYPE, "application/vnd.android.package-archive")
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                }
                val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: throw IOException("MediaStore insert failed")
                ctx.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), fileName).absolutePath
            } else {
                val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                dir.mkdirs()
                val out = File(dir, fileName)
                out.writeBytes(bytes)
                out.absolutePath
            }
        } catch (e: Exception) {
            privateFile.absolutePath
        }
    }

    /** Opens the Android package installer for a previously saved APK. */
    fun openInstaller(context: Context, savedPath: String) {
        val dir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: context.filesDir
        val file = File(dir, File(savedPath).name)
        if (!file.exists()) return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !context.packageManager.canRequestPackageInstalls()) {
            val settings = Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:" + context.packageName)
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(settings)
            return
        }

        val uri = FileProvider.getUriForFile(context, context.packageName + ".fileprovider", file)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }
}
