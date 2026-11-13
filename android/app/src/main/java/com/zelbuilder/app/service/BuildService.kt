package com.zelbuilder.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.zelbuilder.app.MainActivity
import com.zelbuilder.app.R
import com.zelbuilder.app.data.AiService
import com.zelbuilder.app.data.ArtifactDownloader
import com.zelbuilder.app.data.GitHubService
import com.zelbuilder.app.data.LogCleaner
import com.zelbuilder.app.data.ProjectTreeDesigner
import com.zelbuilder.app.data.RepoFile
import com.zelbuilder.app.data.local.BuildEntity
import com.zelbuilder.app.data.local.ZelDatabase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class BuildStage {
    IDLE, PUSHING, DISPATCHING, BUILDING, ABSTRACTING, FINDING, FIXING, REPUSHING, DOWNLOADING, SUCCESS, FAILED
}

data class ZelSettings(
    val githubToken: String = "",
    val aiKey: String = "",
    val aiBaseUrl: String = "https://api.groq.com/openai/v1",
    val aiModel: String = "llama-3.3-70b-versatile"
)

/** In-memory hand-over between the UI/ViewModel and the (service or ViewModel-driven) pipeline. */
object BuildBridge {
    val logs = MutableStateFlow<List<String>>(emptyList())
    val stage = MutableStateFlow(BuildStage.IDLE)
    val status = MutableStateFlow("")
    val apkPath = MutableStateFlow<String?>(null)

    @Volatile var files: Map<String, String> = emptyMap()
    @Volatile var owner: String = ""
    @Volatile var repo: String = ""
    @Volatile var settings: ZelSettings = ZelSettings()
    @Volatile var iconBytes: ByteArray? = null

    private val clock = SimpleDateFormat("HH:mm:ss", Locale.US)

    fun log(line: String) {
        val stamped = "[" + clock.format(Date()) + "] " + line
        logs.update { (it + stamped).takeLast(2000) }
    }

    fun reset() {
        logs.value = emptyList()
        stage.value = BuildStage.IDLE
        status.value = ""
        apkPath.value = null
    }

    fun isRunning(): Boolean {
        val s = stage.value
        return s != BuildStage.IDLE && s != BuildStage.SUCCESS && s != BuildStage.FAILED
    }
}

/** Push -> Dispatch -> Poll -> Fail -> Clean logs -> AI fix -> Re-push (max 3 attempts). */
object BuildPipeline {

    private const val MAX_ATTEMPTS = 3

    private fun step(stage: BuildStage, text: String, onNotify: (String) -> Unit) {
        BuildBridge.stage.value = stage
        BuildBridge.status.value = text
        BuildBridge.log(text)
        onNotify(text)
    }

    private suspend fun prepareFiles(iconUrl: String): MutableMap<String, String> {
        val files = BuildBridge.files.toMutableMap()
        files[GitHubService.WORKFLOW_PATH] = GitHubService.WORKFLOW_YAML
        if (!ProjectTreeDesigner.hasAppIcon(files)) {
            val bytes = BuildBridge.iconBytes
                ?: if (iconUrl.isNotBlank()) AiService.downloadBytes(iconUrl) else null
            if (bytes != null) {
                ProjectTreeDesigner.injectIcon(files, bytes)
                BuildBridge.log("Generated app icon added to the project")
            }
        }
        return files
    }

    private fun toRepoFiles(files: Map<String, String>): List<RepoFile> =
        files.map { (path, content) ->
            if (content.startsWith(ProjectTreeDesigner.BINARY_PREFIX)) {
                RepoFile(path, content.removePrefix(ProjectTreeDesigner.BINARY_PREFIX), true)
            } else {
                RepoFile(path, content, false)
            }
        }

    private suspend fun waitWithHeartbeat(token: String, owner: String, repo: String, runId: Long): Boolean =
        coroutineScope {
            val started = System.currentTimeMillis()
            val heartbeat = launch {
                while (true) {
                    delay(30_000)
                    val secs = (System.currentTimeMillis() - started) / 1000
                    BuildBridge.log("Build in progress... ${secs}s")
                }
            }
            try {
                GitHubService.waitForRun(token, owner, repo, runId)
            } finally {
                heartbeat.cancel()
            }
        }

    suspend fun run(
        context: Context,
        appName: String,
        packageName: String,
        iconUrl: String,
        onNotify: (String) -> Unit = {}
    ) {
        val s = BuildBridge.settings
        val owner = BuildBridge.owner
        val repo = BuildBridge.repo
        val dao = ZelDatabase.get(context).buildDao()
        ArtifactDownloader.init(context)

        try {
            BuildBridge.log("Package: $packageName")
            val current = prepareFiles(iconUrl)
            val branch = GitHubService.getDefaultBranch(s.githubToken, owner, repo)
            var apk: String? = null
            var attempt = 0

            while (attempt < MAX_ATTEMPTS && apk == null) {
                attempt++
                if (attempt == 1) {
                    step(BuildStage.PUSHING, "Pushing ${current.size} files to $owner/$repo ($branch)...", onNotify)
                } else {
                    step(BuildStage.REPUSHING, "Re-pushing...", onNotify)
                }
                val sha = GitHubService.pushProject(
                    s.githubToken, owner, repo, branch, toRepoFiles(current),
                    "Zel Builder Royal: build attempt $attempt"
                )
                BuildBridge.log("Committed " + sha.take(7))

                step(BuildStage.DISPATCHING, "Dispatching workflow...", onNotify)
                val since = System.currentTimeMillis() - 30_000
                if (!GitHubService.dispatchWorkflow(s.githubToken, owner, repo, branch)) {
                    throw IOException("Could not dispatch the workflow. Token needs 'repo' and 'workflow' scopes.")
                }
                val runId = GitHubService.getLatestRunId(s.githubToken, owner, repo, branch, since)
                    ?: throw IOException("Workflow run did not start")

                step(BuildStage.BUILDING, "Building on GitHub Actions (run #$runId)...", onNotify)
                val success = waitWithHeartbeat(s.githubToken, owner, repo, runId)

                if (success) {
                    step(BuildStage.DOWNLOADING, "Downloading APK...", onNotify)
                    apk = ArtifactDownloader.downloadAndSaveApk(s.githubToken, owner, repo, runId, appName)
                } else {
                    BuildBridge.log("Build run #$runId failed (attempt $attempt/$MAX_ATTEMPTS)")
                    if (attempt >= MAX_ATTEMPTS) throw IOException("Build still failing after $MAX_ATTEMPTS attempts")

                    step(BuildStage.ABSTRACTING, "Abstracting logs...", onNotify)
                    val raw = GitHubService.downloadRunLogs(s.githubToken, owner, repo, runId)
                    val errors = LogCleaner.extractOnlyErrors(raw)
                    BuildBridge.log(errors.lines().take(12).joinToString("\n").ifBlank { "(no error lines found)" })

                    step(BuildStage.FINDING, "AI is finding the bug...", onNotify)
                    val target = LogCleaner.guessFaultyFile(errors, current.keys)
                        ?: throw IOException("Could not locate the faulty file")

                    step(BuildStage.FIXING, "Fixing $target...", onNotify)
                    val fixed = AiService.fixBuildError(
                        s.aiKey, s.aiBaseUrl, s.aiModel, errors, target, current[target].orEmpty()
                    )
                    current[target] = fixed
                }
            }

            val path = apk ?: throw IOException("No APK produced")
            BuildBridge.apkPath.value = path
            dao.insert(BuildEntity(appName = appName, repo = "$owner/$repo", status = "SUCCESS", apkPath = path, createdAt = System.currentTimeMillis()))
            step(BuildStage.SUCCESS, "APK saved: $path", onNotify)
        } catch (e: CancellationException) {
            BuildBridge.stage.value = BuildStage.FAILED
            throw e
        } catch (e: Exception) {
            step(BuildStage.FAILED, "Build failed: ${e.message}", onNotify)
            try {
                dao.insert(BuildEntity(appName = appName, repo = "$owner/$repo", status = "FAILED", apkPath = "", createdAt = System.currentTimeMillis()))
            } catch (ignored: Exception) {
            }
        }
    }
}

class BuildService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        val appName = intent?.getStringExtra(EXTRA_APP_NAME).orEmpty()
        val packageName = intent?.getStringExtra(EXTRA_PACKAGE).orEmpty()
        val iconUrl = intent?.getStringExtra(EXTRA_ICON_URL).orEmpty()

        createChannel()
        val notification = buildNotification("Starting build for $appName...", true)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIF_ID, notification)
        }
        acquireWakeLock()

        if (job?.isActive == true) return START_NOT_STICKY

        job = scope.launch {
            try {
                BuildPipeline.run(applicationContext, appName, packageName, iconUrl) { text ->
                    notify(NOTIF_ID, buildNotification(text, true))
                }
            } finally {
                releaseWakeLock()
                val finalText = BuildBridge.status.value.ifBlank { "Build finished" }
                stopForeground(STOP_FOREGROUND_REMOVE)
                notify(NOTIF_ID + 1, buildNotification(finalText, false))
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        releaseWakeLock()
        scope.cancel()
        super.onDestroy()
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "zelbuilder:build").apply {
            acquire(60 * 60 * 1000L)
        }
    }

    private fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        } catch (ignored: Exception) {
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Zel Builder builds", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun notify(id: Int, notification: Notification) {
        try {
            getSystemService(NotificationManager::class.java).notify(id, notification)
        } catch (ignored: SecurityException) {
        }
    }

    private fun buildNotification(text: String, ongoing: Boolean): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_zel)
            .setContentTitle("Zel Builder Royal")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOngoing(ongoing)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "zel_build"
        private const val NOTIF_ID = 4201
        private const val ACTION_STOP = "com.zelbuilder.app.STOP_BUILD"
        private const val EXTRA_APP_NAME = "appName"
        private const val EXTRA_PACKAGE = "packageName"
        private const val EXTRA_ICON_URL = "iconUrl"

        fun start(context: Context, appName: String, packageName: String, iconUrl: String) {
            val intent = Intent(context, BuildService::class.java)
                .putExtra(EXTRA_APP_NAME, appName)
                .putExtra(EXTRA_PACKAGE, packageName)
                .putExtra(EXTRA_ICON_URL, iconUrl)
            ContextCompat.startForegroundService(context, intent)
        }
    }
}
