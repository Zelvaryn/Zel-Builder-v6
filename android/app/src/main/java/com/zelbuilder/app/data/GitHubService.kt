package com.zelbuilder.app.data

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.time.Instant
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

/** A file to be pushed. Binary files carry base64 content with isBase64 = true. */
data class RepoFile(
    val path: String,
    val content: String,
    val isBase64: Boolean = false
)

object GitHubService {

    const val WORKFLOW_PATH = ".github/workflows/build-apk.yml"
    private const val WORKFLOW_FILE = "build-apk.yml"
    private const val API = "https://api.github.com"

    /** Same workflow the app requires in every pushed repository. ('§' is replaced by '$'.) */
    val WORKFLOW_YAML: String = """
        name: Build APK
        on:
          push:
            branches: [main, master]
          workflow_dispatch:
        jobs:
          build:
            runs-on: ubuntu-latest
            steps:
              - uses: actions/checkout@v4
              - uses: actions/setup-java@v4
                with:
                  distribution: temurin
                  java-version: "17"
              - run: yes | §ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager --licenses || true
              - uses: android-actions/setup-android@v4
              - run: yes | sdkmanager --licenses > /dev/null || true; sdkmanager "platforms;android-34" "build-tools;34.0.0"
              - run: wget -q https://services.gradle.org/distributions/gradle-8.7-bin.zip; unzip -q gradle-8.7-bin.zip; echo "§PWD/gradle-8.7/bin" >> §GITHUB_PATH
              - run: gradle :app:assembleDebug --stacktrace --no-daemon
              - uses: actions/upload-artifact@v4
                with:
                  name: zel-builder-apk
                  path: app/build/outputs/apk/debug/*.apk
    """.trimIndent().replace('§', '$') + "\n"

    private val jsonType = "application/json; charset=utf-8".toMediaType()

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .build()

    private class Resp(val code: Int, val body: String) {
        val ok: Boolean get() = code in 200..299
    }

    private fun builder(token: String, url: String): Request.Builder =
        Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $token")
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")

    private fun call(request: Request): Resp =
        client.newCall(request).execute().use { Resp(it.code, it.body?.string().orEmpty()) }

    private fun get(token: String, path: String): Resp =
        call(builder(token, API + path).get().build())

    private fun send(token: String, method: String, path: String, json: JSONObject?): Resp {
        val body = (json?.toString() ?: "{}").toRequestBody(jsonType)
        return call(builder(token, API + path).method(method, body).build())
    }

    // ───────────────────────── account ─────────────────────────

    suspend fun validateToken(token: String): Boolean = withContext(Dispatchers.IO) {
        try {
            get(token, "/user").ok
        } catch (e: Exception) {
            false
        }
    }

    suspend fun getUsername(token: String): String = withContext(Dispatchers.IO) {
        val r = get(token, "/user")
        if (!r.ok) throw IOException("GitHub token rejected (${r.code})")
        JSONObject(r.body).getString("login")
    }

    suspend fun getUserRepos(token: String): List<String> = withContext(Dispatchers.IO) {
        val names = mutableListOf<String>()
        for (page in 1..5) {
            val r = get(token, "/user/repos?per_page=100&page=$page&sort=updated&affiliation=owner")
            if (!r.ok) throw IOException("Could not list repositories (${r.code})")
            val arr = JSONArray(r.body)
            for (i in 0 until arr.length()) names.add(arr.getJSONObject(i).getString("name"))
            if (arr.length() < 100) break
        }
        names
    }

    suspend fun getDefaultBranch(token: String, owner: String, repo: String): String =
        withContext(Dispatchers.IO) {
            try {
                val r = get(token, "/repos/$owner/$repo")
                if (r.ok) JSONObject(r.body).optString("default_branch", "main").ifBlank { "main" } else "main"
            } catch (e: Exception) {
                "main"
            }
        }

    // ───────────────────────── repo management ─────────────────────────

    suspend fun createRepo(token: String, repoName: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val body = JSONObject()
                .put("name", repoName)
                .put("private", true)
                .put("auto_init", true)
                .put("description", "Created by Zel Builder Royal")
            send(token, "POST", "/user/repos", body).code == 201
        } catch (e: Exception) {
            false
        }
    }

    suspend fun deleteRepo(token: String, owner: String, repoName: String): Boolean =
        withContext(Dispatchers.IO) {
            try {
                send(token, "DELETE", "/repos/$owner/$repoName", null).code == 204
            } catch (e: Exception) {
                false
            }
        }

    /** Deletes every file on the default branch in a single commit. */
    suspend fun cleanRepo(token: String, owner: String, repoName: String): Boolean =
        withContext(Dispatchers.IO) {
            try {
                val branch = getDefaultBranch(token, owner, repoName)
                val headSha = getRefSha(token, owner, repoName, branch) ?: return@withContext true
                val commit = get(token, "/repos/$owner/$repoName/git/commits/$headSha")
                if (!commit.ok) return@withContext false
                val treeSha = JSONObject(commit.body).getJSONObject("tree").getString("sha")
                val tree = get(token, "/repos/$owner/$repoName/git/trees/$treeSha?recursive=1")
                if (!tree.ok) return@withContext false

                val entries = JSONArray()
                val items = JSONObject(tree.body).getJSONArray("tree")
                for (i in 0 until items.length()) {
                    val item = items.getJSONObject(i)
                    if (item.getString("type") == "blob") {
                        entries.put(
                            JSONObject()
                                .put("path", item.getString("path"))
                                .put("mode", item.getString("mode"))
                                .put("type", "blob")
                                .put("sha", JSONObject.NULL)
                        )
                    }
                }
                if (entries.length() == 0) return@withContext true

                val newTree = send(
                    token, "POST", "/repos/$owner/$repoName/git/trees",
                    JSONObject().put("base_tree", treeSha).put("tree", entries)
                )
                if (!newTree.ok) return@withContext false
                val newTreeSha = JSONObject(newTree.body).getString("sha")

                val newCommit = send(
                    token, "POST", "/repos/$owner/$repoName/git/commits",
                    JSONObject()
                        .put("message", "Zel Builder: clean repository")
                        .put("tree", newTreeSha)
                        .put("parents", JSONArray().put(headSha))
                )
                if (!newCommit.ok) return@withContext false
                val newCommitSha = JSONObject(newCommit.body).getString("sha")

                send(
                    token, "PATCH", "/repos/$owner/$repoName/git/refs/heads/$branch",
                    JSONObject().put("sha", newCommitSha).put("force", true)
                ).ok
            } catch (e: Exception) {
                false
            }
        }

    // ───────────────────────── push ─────────────────────────

    private fun getRefSha(token: String, owner: String, repo: String, branch: String): String? {
        val r = get(token, "/repos/$owner/$repo/git/ref/heads/$branch")
        return if (r.ok) JSONObject(r.body).getJSONObject("object").getString("sha") else null
    }

    private fun createBlob(token: String, owner: String, repo: String, file: RepoFile): String {
        val body = JSONObject()
            .put("content", file.content)
            .put("encoding", if (file.isBase64) "base64" else "utf-8")
        var lastError = ""
        repeat(3) { attempt ->
            val r = send(token, "POST", "/repos/$owner/$repo/git/blobs", body)
            if (r.ok) return JSONObject(r.body).getString("sha")
            lastError = "${r.code} ${r.body.take(200)}"
            Thread.sleep(1500L * (attempt + 1))
        }
        throw IOException("Blob upload failed for ${file.path}: $lastError")
    }

    /** Pushes the files as ONE commit (replacing the branch content). Returns the commit SHA. */
    suspend fun pushProject(
        token: String,
        owner: String,
        repo: String,
        branch: String,
        files: List<RepoFile>,
        message: String
    ): String = withContext(Dispatchers.IO) {
        var parent = getRefSha(token, owner, repo, branch)

        if (parent == null) {
            val defaultBranch = getDefaultBranchBlocking(token, owner, repo)
            var defaultSha = getRefSha(token, owner, repo, defaultBranch)
            if (defaultSha == null) {
                // Brand-new empty repository: initialise it through the contents API.
                val init = send(
                    token, "PUT", "/repos/$owner/$repo/contents/.zel-init",
                    JSONObject()
                        .put("message", "Initialize repository")
                        .put("content", Base64.encodeToString("zel".toByteArray(), Base64.NO_WRAP))
                )
                if (!init.ok) throw IOException("Could not initialise empty repository (${init.code})")
                defaultSha = getRefSha(token, owner, repo, defaultBranch)
                    ?: throw IOException("Repository has no commits")
            }
            if (branch != defaultBranch) {
                val ref = send(
                    token, "POST", "/repos/$owner/$repo/git/refs",
                    JSONObject().put("ref", "refs/heads/$branch").put("sha", defaultSha)
                )
                if (!ref.ok) throw IOException("Could not create branch $branch (${ref.code})")
            }
            parent = defaultSha
        }

        val blobShas = files.chunked(6).flatMap { chunk ->
            coroutineScope {
                chunk.map { f -> async { createBlob(token, owner, repo, f) } }.awaitAll()
            }
        }

        val entries = JSONArray()
        files.forEachIndexed { i, f ->
            entries.put(
                JSONObject()
                    .put("path", f.path)
                    .put("mode", if (f.path.endsWith("gradlew")) "100755" else "100644")
                    .put("type", "blob")
                    .put("sha", blobShas[i])
            )
        }

        val tree = send(token, "POST", "/repos/$owner/$repo/git/trees", JSONObject().put("tree", entries))
        if (!tree.ok) throw IOException("Tree creation failed (${tree.code}): ${tree.body.take(200)}")
        val treeSha = JSONObject(tree.body).getString("sha")

        val commit = send(
            token, "POST", "/repos/$owner/$repo/git/commits",
            JSONObject()
                .put("message", message)
                .put("tree", treeSha)
                .put("parents", JSONArray().put(parent))
        )
        if (!commit.ok) throw IOException("Commit failed (${commit.code}): ${commit.body.take(200)}")
        val commitSha = JSONObject(commit.body).getString("sha")

        val update = send(
            token, "PATCH", "/repos/$owner/$repo/git/refs/heads/$branch",
            JSONObject().put("sha", commitSha).put("force", true)
        )
        if (!update.ok) throw IOException("Branch update failed (${update.code}): ${update.body.take(200)}")
        commitSha
    }

    private fun getDefaultBranchBlocking(token: String, owner: String, repo: String): String {
        val r = get(token, "/repos/$owner/$repo")
        return if (r.ok) JSONObject(r.body).optString("default_branch", "main").ifBlank { "main" } else "main"
    }

    // ───────────────────────── actions ─────────────────────────

    suspend fun dispatchWorkflow(token: String, owner: String, repo: String, branch: String): Boolean =
        withContext(Dispatchers.IO) {
            for (attempt in 1..5) {
                try {
                    val r = send(
                        token, "POST",
                        "/repos/$owner/$repo/actions/workflows/$WORKFLOW_FILE/dispatches",
                        JSONObject().put("ref", branch)
                    )
                    if (r.code == 204) return@withContext true
                } catch (e: IOException) {
                    // retry
                }
                delay(4000)
            }
            false
        }

    /** Finds the id of the workflow_dispatch run created after [sinceEpochMs]. */
    suspend fun getLatestRunId(
        token: String,
        owner: String,
        repo: String,
        branch: String,
        sinceEpochMs: Long
    ): Long? = withContext(Dispatchers.IO) {
        for (attempt in 1..20) {
            try {
                val r = get(token, "/repos/$owner/$repo/actions/runs?branch=$branch&event=workflow_dispatch&per_page=5")
                if (r.ok) {
                    val runs = JSONObject(r.body).getJSONArray("workflow_runs")
                    for (i in 0 until runs.length()) {
                        val run = runs.getJSONObject(i)
                        val created = Instant.parse(run.getString("created_at")).toEpochMilli()
                        if (created >= sinceEpochMs) return@withContext run.getLong("id")
                    }
                }
            } catch (e: Exception) {
                // retry
            }
            delay(3000)
        }
        null
    }

    /** Polls until the run completes. Returns true when the conclusion is "success". */
    suspend fun waitForRun(token: String, owner: String, repo: String, runId: Long): Boolean =
        withContext(Dispatchers.IO) {
            val deadline = System.currentTimeMillis() + 45 * 60_000L
            while (System.currentTimeMillis() < deadline) {
                try {
                    val r = get(token, "/repos/$owner/$repo/actions/runs/$runId")
                    if (r.ok) {
                        val o = JSONObject(r.body)
                        if (o.optString("status") == "completed") {
                            return@withContext o.optString("conclusion") == "success"
                        }
                    }
                } catch (e: IOException) {
                    // transient network error, keep polling
                }
                delay(10_000)
            }
            false
        }

    /** Downloads the zipped run logs and returns them concatenated as text. */
    suspend fun downloadRunLogs(token: String, owner: String, repo: String, runId: Long): String =
        withContext(Dispatchers.IO) {
            val request = builder(token, "$API/repos/$owner/$repo/actions/runs/$runId/logs").get().build()
            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) throw IOException("Could not download logs (${resp.code})")
                val parts = mutableListOf<Pair<String, String>>()
                ZipInputStream(resp.body!!.byteStream()).use { zip ->
                    var entry = zip.nextEntry
                    while (entry != null) {
                        if (!entry.isDirectory) {
                            parts.add(entry.name to zip.readBytes().toString(Charsets.UTF_8))
                        }
                        entry = zip.nextEntry
                    }
                }
                val sb = StringBuilder()
                for ((name, text) in parts.sortedBy { it.first }) {
                    sb.append("### ").append(name).append('\n').append(text).append('\n')
                }
                sb.toString()
            }
        }
}
