package com.zelbuilder.app.viewmodel

import android.app.Application
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.zelbuilder.app.data.AiService
import com.zelbuilder.app.data.ArtifactDownloader
import com.zelbuilder.app.data.GitHubService
import com.zelbuilder.app.data.ProjectTreeDesigner
import com.zelbuilder.app.data.local.ZelDatabase
import com.zelbuilder.app.service.BuildBridge
import com.zelbuilder.app.service.BuildPipeline
import com.zelbuilder.app.service.BuildService
import com.zelbuilder.app.service.ZelSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.zip.ZipInputStream

enum class Screen { SPLASH, MAIN, ANALYSIS, DESIGN, CONSOLE }
enum class SourceType { ZIP, HTML, FOLDER }
enum class RepoMode { NEW, EXISTING, DELETE }

class ZelViewModel(application: Application) : AndroidViewModel(application) {

    private val prefs = application.getSharedPreferences("zel_prefs", Context.MODE_PRIVATE)
    val builds = ZelDatabase.get(application).buildDao().observeAll()

    // ── navigation / global ──
    var screen by mutableStateOf(Screen.SPLASH)
    var showSettings by mutableStateOf(false)
    var message by mutableStateOf<String?>(null)
    var busy by mutableStateOf(false)

    // ── settings ──
    var githubToken by mutableStateOf(prefs.getString("github_token", "").orEmpty())
    var aiApiKey by mutableStateOf(prefs.getString("ai_key", "").orEmpty())
    var aiBaseUrl by mutableStateOf(prefs.getString("ai_base_url", DEFAULT_BASE_URL).orEmpty())
    var aiModel by mutableStateOf(prefs.getString("ai_model", DEFAULT_MODEL).orEmpty())
    var backgroundService by mutableStateOf(prefs.getBoolean("bg_service", true))
    var validationResult by mutableStateOf("")
    var validating by mutableStateOf(false)

    // ── source selection ──
    var sourceType by mutableStateOf<SourceType?>(null)
    var sourceName by mutableStateOf("")
    private var sourceUri: Uri? = null

    // ── repository flow ──
    var showRepoSheet by mutableStateOf(false)
    var repoMode by mutableStateOf(RepoMode.NEW)
    var repos by mutableStateOf<List<String>>(emptyList())
    var reposLoading by mutableStateOf(false)
    var newRepoName by mutableStateOf("")
    var selectedRepo by mutableStateOf("")
    var showCleanDialog by mutableStateOf(false)
    var showDeleteDialog by mutableStateOf(false)
    private var owner: String = ""
    private var activeRepo: String = ""

    // ── analysis ──
    var analyzing by mutableStateOf(false)
    var analysisStatus by mutableStateOf("")
    var analysisError by mutableStateOf<String?>(null)
    var appName by mutableStateOf("")
    var packageName by mutableStateOf("")
    var iconUrl by mutableStateOf("")
    var iconPreview by mutableStateOf<ByteArray?>(null)
    var projectHasIcon by mutableStateOf(false)
    private var rawFiles: Map<String, String> = emptyMap()

    // ── tree designer ──
    var treeAnimating by mutableStateOf(false)
    var treeDone by mutableStateOf(false)
    var cleanedFiles by mutableStateOf<List<String>>(emptyList())
    var treeStats by mutableStateOf("")
    private var designed: Map<String, String> = emptyMap()

    // ───────────────────────── settings ─────────────────────────

    fun saveSettings() {
        prefs.edit()
            .putString("github_token", githubToken.trim())
            .putString("ai_key", aiApiKey.trim())
            .putString("ai_base_url", aiBaseUrl.trim().ifBlank { DEFAULT_BASE_URL })
            .putString("ai_model", aiModel.trim().ifBlank { DEFAULT_MODEL })
            .putBoolean("bg_service", backgroundService)
            .apply()
    }

    fun closeSettings() {
        saveSettings()
        showSettings = false
    }

    fun validateConnections() {
        saveSettings()
        viewModelScope.launch {
            validating = true
            validationResult = "Checking connections..."
            val gh = runCatching { GitHubService.validateToken(githubToken.trim()) }.getOrDefault(false)
            val ai = runCatching { AiService.validate(aiApiKey.trim(), aiBaseUrl.trim()) }.getOrDefault(false)
            validationResult = "GitHub: " + (if (gh) "connected" else "failed") +
                "\nAI: " + (if (ai) "connected" else "failed")
            validating = false
        }
    }

    // ───────────────────────── navigation ─────────────────────────

    fun goBack() {
        screen = when (screen) {
            Screen.CONSOLE -> Screen.DESIGN
            Screen.DESIGN -> Screen.ANALYSIS
            Screen.ANALYSIS -> Screen.MAIN
            else -> screen
        }
    }

    fun newProject() {
        BuildBridge.reset()
        sourceType = null
        sourceName = ""
        sourceUri = null
        treeDone = false
        treeAnimating = false
        cleanedFiles = emptyList()
        iconUrl = ""
        iconPreview = null
        screen = Screen.MAIN
    }

    fun dismissMessage() {
        message = null
    }

    // ───────────────────────── file selection ─────────────────────────

    fun onSourcePicked(type: SourceType, uri: Uri?) {
        if (uri == null) return
        sourceType = type
        sourceUri = uri
        sourceName = queryName(uri, type)
    }

    private fun queryName(uri: Uri, type: SourceType): String {
        val ctx = getApplication<Application>()
        if (type == SourceType.FOLDER) {
            return DocumentFile.fromTreeUri(ctx, uri)?.name ?: "project"
        }
        ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val n = c.getString(0)
                if (!n.isNullOrBlank()) return n
            }
        }
        return uri.lastPathSegment ?: "project"
    }

    // ───────────────────────── repository flow ─────────────────────────

    fun onProcessFile() {
        if (sourceType == null) return
        showRepoSheet = true
        loadRepos()
    }

    fun loadRepos() {
        if (githubToken.isBlank()) {
            message = "Add your GitHub token in Settings first."
            return
        }
        viewModelScope.launch {
            reposLoading = true
            try {
                owner = GitHubService.getUsername(githubToken.trim())
                repos = GitHubService.getUserRepos(githubToken.trim())
            } catch (e: Exception) {
                message = "GitHub error: ${e.message}"
            } finally {
                reposLoading = false
            }
        }
    }

    private suspend fun ensureOwner() {
        if (owner.isBlank()) owner = GitHubService.getUsername(githubToken.trim())
    }

    fun confirmRepository() {
        if (githubToken.isBlank()) {
            message = "Add your GitHub token in Settings first."
            return
        }
        when (repoMode) {
            RepoMode.NEW -> {
                val name = newRepoName.trim().replace(Regex("\\s+"), "-")
                if (name.isEmpty()) {
                    message = "Enter a repository name."
                    return
                }
                viewModelScope.launch {
                    busy = true
                    try {
                        ensureOwner()
                        val ok = GitHubService.createRepo(githubToken.trim(), name)
                        if (ok) {
                            activeRepo = name
                            showRepoSheet = false
                            startAnalysis()
                        } else {
                            message = "Could not create repository. The name may already exist."
                        }
                    } catch (e: Exception) {
                        message = "GitHub error: ${e.message}"
                    } finally {
                        busy = false
                    }
                }
            }
            RepoMode.EXISTING -> {
                if (selectedRepo.isBlank()) {
                    message = "Select a repository first."
                } else {
                    showCleanDialog = true
                }
            }
            RepoMode.DELETE -> {
                message = "Select a repository and press Delete Repository."
            }
        }
    }

    fun answerClean(clean: Boolean) {
        showCleanDialog = false
        viewModelScope.launch {
            busy = true
            try {
                ensureOwner()
                if (clean) {
                    val ok = GitHubService.cleanRepo(githubToken.trim(), owner, selectedRepo)
                    if (!ok) {
                        message = "Could not clean the repository."
                        return@launch
                    }
                }
                activeRepo = selectedRepo
                showRepoSheet = false
                startAnalysis()
            } catch (e: Exception) {
                message = "GitHub error: ${e.message}"
            } finally {
                busy = false
            }
        }
    }

    fun deleteSelectedRepo() {
        showDeleteDialog = false
        val name = selectedRepo
        if (name.isBlank()) return
        viewModelScope.launch {
            busy = true
            try {
                ensureOwner()
                val ok = GitHubService.deleteRepo(githubToken.trim(), owner, name)
                if (ok) {
                    message = "Deleted repository $name"
                    selectedRepo = ""
                    repos = GitHubService.getUserRepos(githubToken.trim())
                } else {
                    message = "Delete failed. The token needs the delete_repo scope."
                }
            } catch (e: Exception) {
                message = "GitHub error: ${e.message}"
            } finally {
                busy = false
            }
        }
    }

    // ───────────────────────── AI analysis & icon ─────────────────────────

    private fun startAnalysis() {
        val type = sourceType ?: return
        screen = Screen.ANALYSIS
        analyzing = true
        analysisError = null
        analysisStatus = "AI is analyzing files..."
        iconUrl = ""
        iconPreview = null
        treeDone = false
        cleanedFiles = emptyList()

        viewModelScope.launch {
            try {
                var files = withContext(Dispatchers.IO) { readSource(type) }
                if (files.isEmpty()) error("No readable files were found in the selection.")

                val name = deriveAppName(files, type)
                if (type == SourceType.HTML) {
                    files = ProjectTreeDesigner.wrapHtmlAsAndroidProject(files["index.html"].orEmpty(), name)
                }
                delay(1200)

                appName = name
                packageName = derivePackage(files, name)
                projectHasIcon = ProjectTreeDesigner.hasAppIcon(files)

                if (!projectHasIcon) {
                    analysisStatus = "No app icon found. Generating one for $name..."
                    val url = AiService.buildIconUrl(name)
                    val bytes = AiService.downloadBytes(url)
                    BuildBridge.iconBytes = bytes
                    iconPreview = bytes
                    iconUrl = url
                } else {
                    BuildBridge.iconBytes = null
                }
                rawFiles = files
                analyzing = false
            } catch (e: Exception) {
                analysisError = e.message ?: "Analysis failed"
                analyzing = false
            }
        }
    }

    private fun deriveAppName(files: Map<String, String>, type: SourceType): String {
        if (type == SourceType.HTML) {
            val title = Regex("<title>(.*?)</title>", RegexOption.IGNORE_CASE)
                .find(files["index.html"].orEmpty())?.groupValues?.get(1)?.trim()
            if (!title.isNullOrEmpty()) return title.take(40)
        }
        val settingsEntry = files.entries.firstOrNull {
            val n = it.key.substringAfterLast('/')
            n == "settings.gradle.kts" || n == "settings.gradle"
        }
        if (settingsEntry != null) {
            val root = Regex("""rootProject\.name\s*=\s*["']([^"']+)["']""")
                .find(settingsEntry.value)?.groupValues?.get(1)
            if (!root.isNullOrBlank()) return root
        }
        return sourceName.substringBeforeLast('.').ifBlank { "ZelApp" }.take(40)
    }

    private fun derivePackage(files: Map<String, String>, name: String): String {
        val gradle = files.entries.filter { it.key.endsWith("build.gradle.kts") || it.key.endsWith("build.gradle") }
        val appId = Regex("""applicationId\s*=?\s*["']([\w.]+)["']""")
        val ns = Regex("""namespace\s*=?\s*["']([\w.]+)["']""")
        for (g in gradle) {
            val m = appId.find(g.value)
            if (m != null) return m.groupValues[1]
        }
        for (g in gradle) {
            val m = ns.find(g.value)
            if (m != null) return m.groupValues[1]
        }
        return ProjectTreeDesigner.packageFor(name)
    }

    // ───────────────────────── reading the selection ─────────────────────────

    private val binaryExt = setOf(
        "png", "jpg", "jpeg", "webp", "gif", "ico", "jar", "ttf", "otf", "woff", "woff2",
        "so", "keystore", "jks", "mp3", "mp4", "ogg", "wav", "aar", "zip", "pdf", "bin"
    )

    private fun encode(path: String, bytes: ByteArray): String {
        val ext = path.substringAfterLast('.', "").lowercase()
        return if (ext in binaryExt) {
            ProjectTreeDesigner.BINARY_PREFIX + Base64.encodeToString(bytes, Base64.NO_WRAP)
        } else {
            bytes.toString(Charsets.UTF_8)
        }
    }

    private fun readSource(type: SourceType): Map<String, String> {
        val ctx = getApplication<Application>()
        val uri = sourceUri ?: error("No file selected")
        val out = LinkedHashMap<String, String>()
        when (type) {
            SourceType.HTML -> {
                val text = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
                    ?: error("Cannot read the HTML file")
                out["index.html"] = text
            }
            SourceType.ZIP -> {
                val input = ctx.contentResolver.openInputStream(uri) ?: error("Cannot read the ZIP file")
                ZipInputStream(input).use { zip ->
                    var entry = zip.nextEntry
                    while (entry != null) {
                        if (!entry.isDirectory) {
                            val path = ProjectTreeDesigner.normalize(entry.name)
                            if (!ProjectTreeDesigner.isJunk(path)) {
                                out[path] = encode(path, zip.readBytes())
                            }
                        }
                        entry = zip.nextEntry
                    }
                }
            }
            SourceType.FOLDER -> {
                val root = DocumentFile.fromTreeUri(ctx, uri) ?: error("Cannot open the folder")
                walk(root, "", out)
            }
        }
        return out
    }

    private fun walk(dir: DocumentFile, prefix: String, out: MutableMap<String, String>) {
        val ctx = getApplication<Application>()
        for (child in dir.listFiles()) {
            val name = child.name ?: continue
            val path = if (prefix.isEmpty()) name else "$prefix/$name"
            if (child.isDirectory) {
                if (ProjectTreeDesigner.isJunk("$path/_")) continue
                walk(child, path, out)
            } else if (!ProjectTreeDesigner.isJunk(path) && out.size < 8000) {
                val bytes = ctx.contentResolver.openInputStream(child.uri)?.use { it.readBytes() } ?: continue
                out[path] = encode(path, bytes)
            }
        }
    }

    // ───────────────────────── tree designer ─────────────────────────

    fun continueToDesigner() {
        screen = Screen.DESIGN
    }

    fun designTree() {
        if (treeAnimating) return
        viewModelScope.launch {
            treeAnimating = true
            treeDone = false
            val result = withContext(Dispatchers.Default) { ProjectTreeDesigner.designTree(rawFiles) }
            delay(3400)
            designed = result
            cleanedFiles = result.keys.toList()
            val removed = rawFiles.size - result.size
            treeStats = "${result.size} files kept, $removed junk/outside files removed, folders flattened"
            packageName = derivePackage(result, appName)
            treeAnimating = false
            treeDone = true
        }
    }

    // ───────────────────────── push & build ─────────────────────────

    fun pushAndBuild() {
        if (!treeDone || designed.isEmpty()) {
            message = "Design the file tree first."
            return
        }
        if (BuildBridge.isRunning()) {
            screen = Screen.CONSOLE
            return
        }
        saveSettings()
        BuildBridge.reset()
        BuildBridge.files = designed
        BuildBridge.owner = owner
        BuildBridge.repo = activeRepo
        BuildBridge.settings = ZelSettings(
            githubToken = githubToken.trim(),
            aiKey = aiApiKey.trim(),
            aiBaseUrl = aiBaseUrl.trim().ifBlank { DEFAULT_BASE_URL },
            aiModel = aiModel.trim().ifBlank { DEFAULT_MODEL }
        )
        screen = Screen.CONSOLE
        val app = getApplication<Application>()
        BuildBridge.log("Starting pipeline for $appName -> $owner/$activeRepo")

        if (backgroundService) {
            BuildBridge.log("Running in the foreground service")
            BuildService.start(app, appName, packageName, iconUrl)
        } else {
            BuildBridge.log("Running inside the app (service disabled)")
            viewModelScope.launch {
                BuildPipeline.run(app, appName, packageName, iconUrl)
            }
        }
    }

    fun installApk() {
        val path = BuildBridge.apkPath.value ?: return
        val app = getApplication<Application>()
        ArtifactDownloader.init(app)
        ArtifactDownloader.openInstaller(app, path)
    }

    companion object {
        const val DEFAULT_BASE_URL = "https://api.groq.com/openai/v1"
        const val DEFAULT_MODEL = "llama-3.3-70b-versatile"
    }
}
