package com.zelbuilder.app.data

import android.util.Base64

object ProjectTreeDesigner {

    /** Binary files are stored in the file map as BINARY_PREFIX + base64. */
    const val BINARY_PREFIX = "\u0000B64:"

    private val junkDirs = setOf(
        "node_modules", ".git", "build", ".idea", ".gradle", "__MACOSX", ".cxx", ".kotlin"
    )
    private val junkFiles = setOf(".DS_Store", "Thumbs.db", "local.properties")

    fun normalize(path: String): String {
        var p = path.replace('\\', '/').trimStart('/')
        while (p.startsWith("./")) p = p.removePrefix("./")
        return p
    }

    fun isJunk(path: String): Boolean {
        val parts = normalize(path).split('/')
        if (parts.dropLast(1).any { it in junkDirs }) return true
        val name = parts.last()
        return name in junkFiles || name.endsWith(".iml")
    }

    /**
     * 1. Removes junk (node_modules, .git, build, .idea ...)
     * 2. Finds the real project root by locating settings.gradle(.kts)
     * 3. Flattens the tree so that e.g. "android/app/" becomes "app/"
     */
    fun designTree(rawFiles: Map<String, String>): Map<String, String> {
        val cleaned = LinkedHashMap<String, String>()
        for ((path, content) in rawFiles) {
            val n = normalize(path)
            if (n.isNotEmpty() && !isJunk(n)) cleaned[n] = content
        }

        val settingsPaths = cleaned.keys.filter {
            val name = it.substringAfterLast('/')
            name == "settings.gradle.kts" || name == "settings.gradle"
        }

        val root: String = if (settingsPaths.isNotEmpty()) {
            val best = settingsPaths.minByOrNull { p -> p.count { ch -> ch == '/' } } ?: settingsPaths.first()
            if (best.contains('/')) best.substringBeforeLast('/') else ""
        } else {
            commonTopDir(cleaned.keys)
        }

        val prefix = if (root.isEmpty()) "" else "$root/"
        val flattened = HashMap<String, String>()
        for ((path, content) in cleaned) {
            if (prefix.isEmpty()) {
                flattened[path] = content
            } else if (path.startsWith(prefix)) {
                flattened[path.removePrefix(prefix)] = content
            }
        }

        val sorted = LinkedHashMap<String, String>()
        for (key in flattened.keys.sorted()) sorted[key] = flattened.getValue(key)
        return sorted
    }

    private fun commonTopDir(paths: Collection<String>): String {
        var root = ""
        while (true) {
            val rel = paths.map { if (root.isEmpty()) it else it.removePrefix("$root/") }
            val firsts = rel.map { if (it.contains('/')) it.substringBefore('/') else null }
            val first = firsts.firstOrNull()
            if (first == null || firsts.any { it != first }) return root
            root = if (root.isEmpty()) first else "$root/$first"
        }
    }

    fun hasAppIcon(files: Map<String, String>): Boolean {
        val iconRes = Regex("""(^|/)res/(mipmap|drawable)[^/]*/(ic_launcher|zel_generated_icon)[^/]*$""")
        if (files.keys.any { iconRes.containsMatchIn(it) }) return true
        return files.any { (k, v) -> k.endsWith("AndroidManifest.xml") && v.contains("android:icon=") }
    }

    /** Adds a generated icon (PNG/JPEG bytes) to the project and wires it into the manifest. */
    fun injectIcon(files: MutableMap<String, String>, bytes: ByteArray) {
        val isPng = bytes.size > 4 && bytes[0] == 0x89.toByte() && bytes[1] == 'P'.code.toByte()
        val isJpeg = bytes.size > 4 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte()
        if (!isPng && !isJpeg) return
        val ext = if (isPng) "png" else "jpg"
        val manifestKey = files.keys.firstOrNull { it.endsWith("src/main/AndroidManifest.xml") }
            ?: files.keys.firstOrNull { it.endsWith("AndroidManifest.xml") }
            ?: return
        val resDir = manifestKey.removeSuffix("AndroidManifest.xml") + "res/drawable"
        files["$resDir/zel_generated_icon.$ext"] =
            BINARY_PREFIX + Base64.encodeToString(bytes, Base64.NO_WRAP)
        val manifest = files[manifestKey].orEmpty()
        if (!manifest.contains("android:icon=")) {
            files[manifestKey] = manifest.replace(
                "<application",
                "<application android:icon=\"@drawable/zel_generated_icon\""
            )
        }
    }

    fun packageFor(appName: String): String {
        val seg = appName.lowercase().filter { it.isLetterOrDigit() && it.code < 128 }.ifEmpty { "app" }
        return "com.zel.generated.a$seg"
    }

    /** Wraps a single HTML file into a minimal WebView Android project. */
    fun wrapHtmlAsAndroidProject(html: String, appName: String): Map<String, String> {
        val pkg = packageFor(appName)
        val safeName = appName
            .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            .replace("\"", "").replace("'", "\\'")
        val files = LinkedHashMap<String, String>()

        files["settings.gradle.kts"] = """
            pluginManagement {
                repositories {
                    google()
                    mavenCentral()
                    gradlePluginPortal()
                }
            }
            dependencyResolutionManagement {
                repositories {
                    google()
                    mavenCentral()
                }
            }
            rootProject.name = "WebApp"
            include(":app")
        """.trimIndent() + "\n"

        files["build.gradle.kts"] = """
            plugins {
                id("com.android.application") version "8.4.0" apply false
                id("org.jetbrains.kotlin.android") version "1.9.24" apply false
            }
        """.trimIndent() + "\n"

        files["gradle.properties"] = "android.useAndroidX=true\norg.gradle.jvmargs=-Xmx2g\n"

        files["app/build.gradle.kts"] = """
            plugins {
                id("com.android.application")
                id("org.jetbrains.kotlin.android")
            }
            android {
                namespace = "__PKG__"
                compileSdk = 34
                defaultConfig {
                    applicationId = "__PKG__"
                    minSdk = 24
                    targetSdk = 34
                    versionCode = 1
                    versionName = "1.0"
                }
                compileOptions {
                    sourceCompatibility = JavaVersion.VERSION_17
                    targetCompatibility = JavaVersion.VERSION_17
                }
                kotlinOptions {
                    jvmTarget = "17"
                }
            }
            dependencies {
                implementation("androidx.core:core-ktx:1.12.0")
            }
        """.trimIndent().replace("__PKG__", pkg) + "\n"

        files["app/src/main/AndroidManifest.xml"] = """
            <?xml version="1.0" encoding="utf-8"?>
            <manifest xmlns:android="http://schemas.android.com/apk/res/android">
                <uses-permission android:name="android.permission.INTERNET" />
                <application
                    android:label="@string/app_name"
                    android:theme="@android:style/Theme.Material.Light.NoActionBar"
                    android:usesCleartextTraffic="true">
                    <activity android:name=".MainActivity" android:exported="true">
                        <intent-filter>
                            <action android:name="android.intent.action.MAIN" />
                            <category android:name="android.intent.category.LAUNCHER" />
                        </intent-filter>
                    </activity>
                </application>
            </manifest>
        """.trimIndent() + "\n"

        files["app/src/main/res/values/strings.xml"] = """
            <?xml version="1.0" encoding="utf-8"?>
            <resources>
                <string name="app_name">__NAME__</string>
            </resources>
        """.trimIndent().replace("__NAME__", safeName) + "\n"

        files["app/src/main/java/" + pkg.replace('.', '/') + "/MainActivity.kt"] = """
            package __PKG__

            import android.app.Activity
            import android.os.Bundle
            import android.webkit.WebView
            import android.webkit.WebViewClient

            class MainActivity : Activity() {
                override fun onCreate(savedInstanceState: Bundle?) {
                    super.onCreate(savedInstanceState)
                    val web = WebView(this)
                    web.settings.javaScriptEnabled = true
                    web.settings.domStorageEnabled = true
                    web.webViewClient = WebViewClient()
                    setContentView(web)
                    web.loadUrl("file:///android_asset/index.html")
                }
            }
        """.trimIndent().replace("__PKG__", pkg) + "\n"

        files["app/src/main/assets/index.html"] = html
        return files
    }
}
