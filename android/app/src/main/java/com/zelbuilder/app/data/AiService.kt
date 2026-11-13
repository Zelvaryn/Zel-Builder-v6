package com.zelbuilder.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

object AiService {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    private val jsonType = "application/json; charset=utf-8".toMediaType()

    /** Pollinations.ai icon URL for the given app name. */
    fun buildIconUrl(appName: String): String {
        val encoded = URLEncoder.encode(appName, "UTF-8").replace("+", "%20")
        return "https://image.pollinations.ai/prompt/A%20minimal%20modern%20flat%20vector%20app%20icon%20for%20" +
            encoded + "?width=512&height=512&nologo=true"
    }

    suspend fun downloadBytes(url: String): ByteArray? = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder().url(url).get().build()
            client.newCall(request).execute().use { resp ->
                if (resp.isSuccessful) resp.body?.bytes() else null
            }
        } catch (e: Exception) {
            null
        }
    }

    suspend fun validate(apiKey: String, baseUrl: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(baseUrl.trimEnd('/') + "/models")
                .header("Authorization", "Bearer $apiKey")
                .get()
                .build()
            client.newCall(request).execute().use { it.isSuccessful }
        } catch (e: Exception) {
            false
        }
    }

    /** Asks the model to return the complete corrected file content. */
    suspend fun fixBuildError(
        apiKey: String,
        baseUrl: String,
        model: String,
        errorLog: String,
        filePath: String,
        fileContent: String
    ): String = withContext(Dispatchers.IO) {
        val system = "You are an expert Android/Kotlin/Gradle build engineer. " +
            "You receive a build error log and one source file. " +
            "Return ONLY the complete corrected file content. " +
            "No explanations, no markdown fences."
        val user = "Build errors:\n" + errorLog.take(6000) +
            "\n\nFile: " + filePath +
            "\n\nCurrent content:\n" + fileContent.take(12000)

        val messages = JSONArray()
            .put(JSONObject().put("role", "system").put("content", system))
            .put(JSONObject().put("role", "user").put("content", user))

        val body = JSONObject()
            .put("model", model)
            .put("temperature", 0.1)
            .put("max_tokens", 1500)
            .put("messages", messages)

        val request = Request.Builder()
            .url(baseUrl.trimEnd('/') + "/chat/completions")
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .post(body.toString().toRequestBody(jsonType))
            .build()

        client.newCall(request).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw IOException("AI request failed (${resp.code}): ${text.take(300)}")
            }
            val content = JSONObject(text)
                .getJSONArray("choices")
                .getJSONObject(0)
                .getJSONObject("message")
                .getString("content")
            val fixed = stripFences(content)
            if (fixed.isBlank()) throw IOException("AI returned an empty fix")
            fixed
        }
    }

    private fun stripFences(raw: String): String {
        var t = raw.trim()
        if (t.startsWith("```")) {
            t = t.substringAfter('\n', "")
            if (t.trimEnd().endsWith("```")) t = t.trimEnd().removeSuffix("```")
        }
        return t.trim('\n') + "\n"
    }
}
