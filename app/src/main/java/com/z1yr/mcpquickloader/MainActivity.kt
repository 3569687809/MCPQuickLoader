package com.z1yr.mcpquickloader

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.OpenableColumns
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// 整体流程见 README.md「工作原理」；本分支额外带诊断日志
class MainActivity : AppCompatActivity() {

    private lateinit var fileStatusText: TextView
    private lateinit var loadResultText: TextView
    private lateinit var rootStatusText: TextView
    private lateinit var storageStatusText: TextView
    private lateinit var loadProgress: ProgressBar
    private lateinit var loadNowButton: Button

    private var selectedFile: Uri? = null

    /** 有些 root（实测 KernelSU）需要 --mount-master 才能看到别的 App 的数据目录 */
    private var mountMasterSupported = false

    /** 游戏缓存目录可能落在内部存储，也可能落在外部存储，两边都试 */
    private val packCacheCandidates = listOf(
        "/data/user/0/com.netease.x19/files/games/com.netease/packcache",
        "/storage/emulated/0/Android/data/com.netease.x19/files/games/com.netease/packcache"
    )

    private val workDir: File
        get() = externalCacheDir ?: cacheDir

    private val recordFile: File
        get() = File(getExternalFilesDir(null) ?: filesDir, "selected_mcp.txt")

    /** 诊断日志，放在外部缓存目录，用户「清除缓存」就能清掉 */
    private val logFile: File
        get() = File(externalCacheDir ?: cacheDir, "z1yr-debug.log")

    private val timeFormat = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US)

    private val pickFile = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri == null) return@registerForActivityResult

        // .mcp 不是标准 MIME 类型，只能放开 */* 再校验后缀
        val name = queryDisplayName(uri)
        if (!name.endsWith(".mcp", ignoreCase = true)) {
            selectedFile = null
            recordFile.delete()
            fileStatusText.text = "⚠ 不是 .mcp 文件：$name"
            return@registerForActivityResult
        }

        // 少了这步，App 重启后 content:// 就失效了
        try {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } catch (_: Exception) {
        }

        selectedFile = uri
        saveSelection(uri, name)
        fileStatusText.text = "✅ 已选择：$name"
        appendLog("选中文件: $name  ($uri)")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Android 15 起强制 edge-to-edge，系统栏得自己避让
        val contentScroll = findViewById<View>(R.id.contentScroll)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.rootLayout)) { root, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            root.updatePadding(top = bars.top)
            contentScroll.updatePadding(bottom = bars.bottom)
            insets
        }

        fileStatusText = findViewById(R.id.fileStatusText)
        loadResultText = findViewById(R.id.loadResultText)
        rootStatusText = findViewById(R.id.rootStatusText)
        storageStatusText = findViewById(R.id.storageStatusText)
        loadProgress = findViewById(R.id.loadProgress)
        loadNowButton = findViewById(R.id.loadNowButton)

        storageStatusText.setOnClickListener { requestAllFilesAccess() }

        loadSelection()?.let { (uri, name) ->
            selectedFile = uri
            fileStatusText.text = "✅ 已选择：$name"
        }

        findViewById<Button>(R.id.pickMcpButton).setOnClickListener {
            pickFile.launch(arrayOf("*/*"))
        }
        loadNowButton.setOnClickListener { startLoad() }

        appendLog("")
        appendLog("===== 启动 ${timeFormat.format(Date())} =====")
        appendLog("版本 ${appVersion()} | ${Build.MANUFACTURER} ${Build.MODEL} | Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")

        probeRoot()
    }

    override fun onResume() {
        super.onResume()
        if (::storageStatusText.isInitialized) updateStorageStatus()
    }

    // ---------------------------------------------------------------- 权限

    /** 「所有文件访问权限」：访问 /storage/emulated/0 下别的应用目录时需要 */
    private fun updateStorageStatus() {
        val granted = Build.VERSION.SDK_INT < Build.VERSION_CODES.R ||
                Environment.isExternalStorageManager()
        storageStatusText.text = if (granted) {
            "所有文件访问权限：已授予 ✅"
        } else {
            "所有文件访问权限：未授予 ❌（点这里去授权）"
        }
        appendLog("所有文件访问权限: ${if (granted) "已授予" else "未授予"}")
    }

    private fun requestAllFilesAccess() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        try {
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:$packageName")
                )
            )
        } catch (_: Exception) {
            startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
        }
    }

    // ---------------------------------------------------------------- 诊断日志

    private fun appendLog(text: String) {
        try {
            logFile.parentFile?.mkdirs()
            logFile.appendText(text + "\n")
        } catch (_: Exception) {
        }
    }

    private fun appVersion(): String = try {
        packageManager.getPackageInfo(packageName, 0).versionName ?: "?"
    } catch (_: Exception) {
        "?"
    }

    // ---------------------------------------------------------------- root 探测

    private fun runSu(args: List<String>): String = try {
        val process = ProcessBuilder(args).redirectErrorStream(true).start()
        val text = process.inputStream.bufferedReader().use { it.readText() }.trim()
        process.waitFor()
        text
    } catch (e: Exception) {
        "异常: ${e.message}"
    }

    private fun suCommand(script: String): List<String> =
        if (mountMasterSupported) listOf("su", "--mount-master", "-c", script)
        else listOf("su", "-c", script)

    /**
     * 启动时同时探测两种 su 调用方式，并把「能不能看到游戏目录」写进日志。
     * App 各自跑在不同的挂载命名空间里，某些 root 方案只有 --mount-master 才看得见全局目录。
     */
    private fun probeRoot() {
        Thread {
            val plainId = runSu(listOf("su", "-c", "id"))
            val masterId = runSu(listOf("su", "--mount-master", "-c", "id"))
            mountMasterSupported = masterId.contains("uid=0")

            appendLog("su -c id                → $plainId")
            appendLog("su --mount-master -c id → $masterId")
            packCacheCandidates.forEach { path ->
                appendLog("su -c 看 $path → ${runSu(listOf("su", "-c", "ls -ld '$path' 2>&1"))}")
            }
            appendLog("本次采用: ${if (mountMasterSupported) "su --mount-master" else "su"}")

            val granted = plainId.contains("uid=0") || masterId.contains("uid=0")
            runOnUiThread {
                rootStatusText.text = if (granted) {
                    "Root 权限：已授权 ✅"
                } else {
                    "Root 权限：未授权 ❌（复制到游戏目录需要它）"
                }
            }
        }.start()
    }

    // ---------------------------------------------------------------- 选择记录

    private fun saveSelection(uri: Uri, name: String) {
        try {
            recordFile.parentFile?.mkdirs()
            recordFile.writeText(uri.toString() + "\n" + name)
        } catch (_: Exception) {
        }
    }

    private fun loadSelection(): Pair<Uri, String>? {
        return try {
            if (!recordFile.exists()) return null
            val lines = recordFile.readLines()
            if (lines.size >= 2 && lines[0].isNotBlank()) Uri.parse(lines[0]) to lines[1] else null
        } catch (_: Exception) {
            null
        }
    }

    // ---------------------------------------------------------------- 加载流程

    private fun startLoad() {
        val uri = selectedFile
        if (uri == null) {
            loadResultText.text = "请先选择要加载的 MCP 文件"
            return
        }

        loadProgress.visibility = View.VISIBLE
        loadProgress.progress = 0
        loadNowButton.isEnabled = false
        loadResultText.text = "正在准备…"

        // 读文件和执行 su 都会阻塞，必须离开主线程
        Thread {
            val message = try {
                load(uri)
            } catch (e: Exception) {
                fail("加载异常：${e.message}")
            }
            runOnUiThread {
                loadResultText.text = message
                loadProgress.visibility = View.GONE
                loadNowButton.isEnabled = true
            }
        }.start()
    }

    private fun load(uri: Uri): String {
        val fileName = queryDisplayName(uri)

        appendLog("")
        appendLog("===== 加载 ${timeFormat.format(Date())} =====")
        appendLog("文件: $fileName")

        // 先落成真实文件：root 的 shell 不认 content://
        val localFile = File(workDir, fileName)
        localFile.parentFile?.mkdirs()
        contentResolver.openInputStream(uri)?.use { input ->
            localFile.outputStream().use { output -> input.copyTo(output) }
        } ?: return fail("读取所选文件失败（文件可能已被移动或删除）")

        appendLog("落地: ${localFile.absolutePath}  (${localFile.length()} 字节)")

        val script = buildLoadScript(localFile.absolutePath, fileName)
        appendLog("--- 脚本 ---\n$script")

        val lines = execAsRootWithProgress(script) { done, total ->
            runOnUiThread {
                if (total > 0) loadProgress.max = total
                loadProgress.progress = done
                loadResultText.text = "正在加载 $done / $total …"
            }
        }
        appendLog("--- shell 输出 ---\n" + lines.joinToString("\n"))

        val result = lines.lastOrNull { it.startsWith("OK|") || it.startsWith("ERR|") }
        return when {
            result == null -> fail("没有得到 root 的返回结果（Magisk 里授权了吗？）")
            result.startsWith("OK|") -> result.removePrefix("OK|")
            else -> fail(result.removePrefix("ERR|"))
        }
    }

    private fun fail(message: String): String {
        appendLog("--- 结果 ---\n$message")
        // 把 shell 的诊断行直接摆到界面上，用户截个图就能反馈
        val diag = try {
            logFile.readLines().filter { it.startsWith("DIAG|") }.takeLast(8)
        } catch (_: Exception) {
            emptyList()
        }
        val extra = if (diag.isEmpty()) "" else "\n\n" + diag.joinToString("\n") { it.removePrefix("DIAG|") }
        return "$message$extra\n\n完整日志：\n${logFile.absolutePath}"
    }

    // 要 root；chown / chmod / restorecon 缺一不可，否则游戏读不到
    private fun buildLoadScript(sourcePath: String, fileName: String): String {
        val src = shellQuote(sourcePath)
        val name = shellQuote(fileName)
        val candidates = packCacheCandidates.joinToString(" ") { shellQuote(it) }
        return """
            SRC=$src
            NAME=$name
            PACKCACHE=""

            for c in $candidates; do
              if [ -d "${'$'}c" ]; then PACKCACHE="${'$'}c"; echo "DIAG|命中候选路径: ${'$'}c"; break; fi
            done

            if [ -z "${'$'}PACKCACHE" ]; then
              FOUND=${'$'}(find /data/user /storage/emulated/0/Android/data -maxdepth 6 -type d -name packcache 2>/dev/null | head -1)
              if [ -n "${'$'}FOUND" ]; then
                PACKCACHE="${'$'}FOUND"
                echo "DIAG|自动定位到: ${'$'}FOUND"
              fi
            fi

            if [ -z "${'$'}PACKCACHE" ]; then
              echo "ERR|找不到游戏目录"
              echo "DIAG|id: ${'$'}(id 2>&1)"
              echo "DIAG|内部路径: ${'$'}(ls -ld '/data/user/0/com.netease.x19' 2>&1)"
              echo "DIAG|外部路径: ${'$'}(ls -ld '/storage/emulated/0/Android/data/com.netease.x19' 2>&1)"
              echo "DIAG|各用户空间: ${'$'}(ls -d /data/user/*/com.netease.x19 2>&1)"
              echo "DIAG|data 下含 netease: ${'$'}(find /data -maxdepth 4 -iname '*netease*' 2>/dev/null | head -5)"
              echo "DIAG|外部存储下含 netease: ${'$'}(find /storage/emulated/0/Android/data -maxdepth 3 -iname '*netease*' 2>/dev/null | head -5)"
              exit 1
            fi

            TOTAL=0
            for d in "${'$'}PACKCACHE"/*/; do [ -d "${'$'}d" ] && TOTAL=${'$'}((TOTAL+1)); done

            COUNT=0
            for d in "${'$'}PACKCACHE"/*/; do
              [ -d "${'$'}d" ] || continue
              OWNER=${'$'}(stat -c '%u:%g' "${'$'}d")
              cp -f "${'$'}SRC" "${'$'}d${'$'}NAME" || { echo "ERR|复制到 ${'$'}d 失败"; exit 1; }
              chown "${'$'}OWNER" "${'$'}d${'$'}NAME" 2>/dev/null
              chmod 766 "${'$'}d${'$'}NAME" 2>/dev/null
              restorecon "${'$'}d${'$'}NAME" 2>/dev/null
              COUNT=${'$'}((COUNT+1))
              echo "PROGRESS|${'$'}COUNT|${'$'}TOTAL"
            done
            echo "OK|已复制到 ${'$'}COUNT 个子目录（${'$'}PACKCACHE）"
        """.trimIndent()
    }

    private fun execAsRootWithProgress(
        script: String,
        onProgress: (done: Int, total: Int) -> Unit
    ): List<String> {
        val process = ProcessBuilder(suCommand(script)).redirectErrorStream(true).start()

        val allLines = mutableListOf<String>()
        process.inputStream.bufferedReader().forEachLine { line ->
            allLines += line
            if (line.startsWith("PROGRESS|")) {
                val parts = line.split("|")
                if (parts.size >= 3) {
                    onProgress(parts[1].toIntOrNull() ?: 0, parts[2].toIntOrNull() ?: 0)
                }
            }
        }
        process.waitFor()
        return allLines
    }

    private fun shellQuote(value: String): String =
        "'" + value.replace("'", "'\\''") + "'"

    private fun queryDisplayName(uri: Uri): String {
        var name = uri.lastPathSegment ?: "unknown.mcp"
        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && cursor.moveToFirst()) name = cursor.getString(index)
        }
        return name
    }
}
