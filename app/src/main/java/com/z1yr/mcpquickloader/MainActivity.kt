package com.z1yr.mcpquickloader

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
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

// 整体流程与设计说明见 README.md「工作原理」
class MainActivity : AppCompatActivity() {

    private lateinit var fileStatusText: TextView
    private lateinit var loadResultText: TextView
    private lateinit var rootStatusText: TextView
    private lateinit var loadProgress: ProgressBar
    private lateinit var loadNowButton: Button

    private var selectedFile: Uri? = null

    private val packCacheDir =
        "/data/user/0/com.netease.x19/files/games/com.netease/packcache"

    private val workDir: File
        get() = externalCacheDir ?: cacheDir

    private val recordFile: File
        get() = File(getExternalFilesDir(null) ?: filesDir, "selected_mcp.txt")

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
        loadProgress = findViewById(R.id.loadProgress)
        loadNowButton = findViewById(R.id.loadNowButton)

        loadSelection()?.let { (uri, name) ->
            selectedFile = uri
            fileStatusText.text = "✅ 已选择：$name"
        }

        findViewById<Button>(R.id.pickMcpButton).setOnClickListener {
            pickFile.launch(arrayOf("*/*"))
        }
        loadNowButton.setOnClickListener { startLoad() }

        checkRoot()
    }

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
                "加载失败：${e.message}"
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

        // 先落成真实文件：root 的 shell 不认 content://
        val localFile = File(workDir, fileName)
        localFile.parentFile?.mkdirs()
        contentResolver.openInputStream(uri)?.use { input ->
            localFile.outputStream().use { output -> input.copyTo(output) }
        } ?: return "读取所选文件失败（文件可能已被移动或删除）"

        val output = execAsRootWithProgress(
            buildLoadScript(localFile.absolutePath, fileName)
        ) { done, total ->
            runOnUiThread {
                if (total > 0) loadProgress.max = total
                loadProgress.progress = done
                loadResultText.text = "正在加载 $done / $total …"
            }
        }

        return when {
            output.startsWith("OK|") -> output.removePrefix("OK|")
            output.startsWith("ERR|") -> output.removePrefix("ERR|")
            output.isBlank() -> "执行失败：没有拿到 root（Magisk 里给本应用授权了吗？）"
            else -> "加载失败：$output"
        }
    }

    // 要 root；chown / chmod / restorecon 缺一不可，否则游戏读不到（见 README「工作原理」）
    private fun buildLoadScript(sourcePath: String, fileName: String): String {
        val src = shellQuote(sourcePath)
        val name = shellQuote(fileName)
        val dir = shellQuote(packCacheDir)
        return """
            PACKCACHE=$dir
            SRC=$src
            NAME=$name
            if [ ! -d "${'$'}PACKCACHE" ]; then echo "ERR|找不到游戏目录：${'$'}PACKCACHE"; exit 1; fi

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
            echo "OK|已复制到 ${'$'}COUNT 个子目录"
        """.trimIndent()
    }

    private fun execAsRootWithProgress(
        script: String,
        onProgress: (done: Int, total: Int) -> Unit
    ): String {
        val process = ProcessBuilder("su", "-c", script).redirectErrorStream(true).start()

        var result = ""
        process.inputStream.bufferedReader().forEachLine { line ->
            when {
                line.startsWith("PROGRESS|") -> {
                    val parts = line.split("|")
                    if (parts.size >= 3) {
                        onProgress(parts[1].toIntOrNull() ?: 0, parts[2].toIntOrNull() ?: 0)
                    }
                }
                line.startsWith("OK|") || line.startsWith("ERR|") -> result = line
            }
        }
        process.waitFor()
        return result
    }

    private fun shellQuote(value: String): String =
        "'" + value.replace("'", "'\\''") + "'"

    private fun checkRoot() {
        Thread {
            val granted = try {
                Runtime.getRuntime().exec(arrayOf("su", "-c", "id")).waitFor() == 0
            } catch (_: Exception) {
                false
            }
            runOnUiThread {
                rootStatusText.text = if (granted) {
                    "Root 权限：已授权 ✅"
                } else {
                    "Root 权限：未授权 ❌（复制到游戏目录需要它）"
                }
            }
        }.start()
    }

    private fun queryDisplayName(uri: Uri): String {
        var name = uri.lastPathSegment ?: "unknown.mcp"
        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && cursor.moveToFirst()) name = cursor.getString(index)
        }
        return name
    }
}
