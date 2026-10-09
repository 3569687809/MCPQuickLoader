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

/**
 * 界面逻辑。
 *
 * 流程：选一个 .mcp 文件（会被记住，重开 App 还在）→ 点「立即加载」
 * → 用 root 把它复制进游戏目录 packcache 下的每一个子目录（同名覆盖，权限 766）。
 *
 * 存放位置（都在外部存储，用户自己就能看见、能清理）：
 *   中转文件  /sdcard/Android/data/包名/cache/       ← 属于"缓存"，清缓存就没了，下次加载会重新复制
 *   选择记录  /sdcard/Android/data/包名/files/selected_mcp.txt
 */
class MainActivity : AppCompatActivity() {

    private lateinit var fileStatusText: TextView
    private lateinit var loadResultText: TextView
    private lateinit var rootStatusText: TextView
    private lateinit var loadProgress: ProgressBar
    private lateinit var loadNowButton: Button

    /** 用户选中的 .mcp 文件；null 表示还没选 */
    private var selectedFile: Uri? = null

    /** 游戏存放模组的目录 */
    private val packCacheDir =
        "/data/user/0/com.netease.x19/files/games/com.netease/packcache"

    /**
     * 中转目录（放从文件选择器复制出来的 .mcp）。
     * externalCacheDir = /sdcard/Android/data/<包名>/cache
     * 这是标准的"应用缓存目录"：用户在系统设置里点"清除缓存"就能清掉，卸载时也会自动删。
     * 万一外部存储不可用（极少数情况），退回内部缓存目录，保证功能不断。
     */
    private val workDir: File
        get() = externalCacheDir ?: cacheDir

    /**
     * 选择记录文件。
     * 放在 files 目录而不是 cache：清缓存不会把"上次选了哪个文件"也一起清掉。
     */
    private val recordFile: File
        get() = File(getExternalFilesDir(null) ?: filesDir, "selected_mcp.txt")

    /** 打开系统文件选择器，选完文件后回调 */
    private val pickFile = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri == null) {
            return@registerForActivityResult   // 用户取消了
        }
        val name = queryDisplayName(uri)
        if (!name.endsWith(".mcp", ignoreCase = true)) {
            selectedFile = null
            recordFile.delete()
            fileStatusText.text = "⚠ 不是 .mcp 文件：$name"
            return@registerForActivityResult
        }

        // 关键一步：文件选择器给的只是"临时通行证"，App 一重启就作废。
        // 这里向系统申请长期读取权限，然后才敢把它的地址记下来。
        try {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } catch (_: Exception) {
            // 个别文件管理器不支持长期授权，忽略；本次会话内依然可用
        }

        selectedFile = uri
        saveSelection(uri, name)
        fileStatusText.text = "✅ 已选择：$name"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // ---- 处理"内容被状态栏/标题栏吞掉"的问题 ----------------------------
        // Android 15 起，targetSdk 35+ 的应用被强制"边到边"（edge-to-edge）：
        // 窗口会铺满整块屏幕（包括状态栏和导航栏底下），内容必须自己让开。
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

        // 恢复上次选择的文件（App 被杀掉重开也能记起来）
        loadSelection()?.let { (uri, name) ->
            selectedFile = uri
            fileStatusText.text = "✅ 已选择：$name"
        }

        findViewById<Button>(R.id.pickMcpButton).setOnClickListener {
            // .mcp 不是安卓认识的标准类型，所以先放开所有类型，选完再检查后缀
            pickFile.launch(arrayOf("*/*"))
        }

        loadNowButton.setOnClickListener { startLoad() }

        checkRoot()
    }

    // ---------------------------------------------------------------- 选择记录

    /** 把"选了哪个文件"记到外部存储的文本文件里（第一行是地址，第二行是文件名） */
    private fun saveSelection(uri: Uri, name: String) {
        try {
            recordFile.parentFile?.mkdirs()
            recordFile.writeText(uri.toString() + "\n" + name)
        } catch (_: Exception) {
            // 写不进去就算了，不影响本次使用
        }
    }

    /** 读回上次选的记录，没有就返回 null */
    private fun loadSelection(): Pair<Uri, String>? {
        return try {
            if (!recordFile.exists()) return null
            val lines = recordFile.readLines()
            if (lines.size >= 2 && lines[0].isNotBlank()) {
                Uri.parse(lines[0]) to lines[1]
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    // ---------------------------------------------------------------- 加载流程

    /** 点「立即加载」之后的完整流程 */
    private fun startLoad() {
        val uri = selectedFile
        if (uri == null) {
            loadResultText.text = "请先选择要加载的 MCP 文件"
            return
        }

        // 进入"加载中"状态：进度条出现，按钮先禁用，避免重复点
        loadProgress.visibility = View.VISIBLE
        loadProgress.progress = 0
        loadNowButton.isEnabled = false
        loadResultText.text = "正在准备…"

        // 读文件 + 执行 root 命令都会阻塞，必须放到后台线程，否则界面会卡死
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

    /**
     * 真正干活的地方：
     * 1. 把用户选中的文件复制到外部缓存目录（拿到真实路径，root 才能拷）
     * 2. 用 root 执行一段 shell：遍历 packcache 下每个子目录，各放一份
     */
    private fun load(uri: Uri): String {
        val fileName = queryDisplayName(uri)

        // 第 1 步：内容 Uri → 外部缓存目录里的真实文件
        val localFile = File(workDir, fileName)
        localFile.parentFile?.mkdirs()
        contentResolver.openInputStream(uri)?.use { input ->
            localFile.outputStream().use { output -> input.copyTo(output) }
        } ?: return "读取所选文件失败（文件可能已被移动或删除）"

        // 第 2 步：交给 root 去分发，过程中每复制完一个目录就回报一次进度
        val output = execAsRootWithProgress(
            buildLoadScript(localFile.absolutePath, fileName)
        ) { done, total ->
            runOnUiThread {
                if (total > 0) {
                    loadProgress.max = total
                }
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

    /**
     * 生成要交给 root 执行的 shell 脚本。
     *
     * 逻辑：packcache 下面有几个子目录，就把文件往每个子目录里放一份；
     * 每个子目录里新文件的归属、权限（766）和 SELinux 标签都跟该目录本身保持一致，
     * 否则游戏读不到或写不了。
     *
     * 每处理完一个目录就输出一行 PROGRESS|已完成|总数，让界面上的进度条动起来。
     */
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

    /**
     * 把脚本交给 su 执行，边读它的输出边回调进度。
     * 约定：PROGRESS|已完成|总数 是进度行，OK|… / ERR|… 是最终结果行。
     */
    private fun execAsRootWithProgress(
        script: String,
        onProgress: (done: Int, total: Int) -> Unit
    ): String {
        val process = ProcessBuilder("su", "-c", script)
            .redirectErrorStream(true)
            .start()

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

    /** 用单引号包住参数，避免路径里有空格或特殊字符时命令被拆断 */
    private fun shellQuote(value: String): String =
        "'" + value.replace("'", "'\\''") + "'"

    /** 检测 root：`su -c id` 能成功返回就说明 Magisk 给了本应用授权 */
    private fun checkRoot() {
        Thread {
            val granted = try {
                val process = Runtime.getRuntime().exec(arrayOf("su", "-c", "id"))
                process.waitFor() == 0
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

    /** 从文件选择器返回的 Uri 里取出文件名 */
    private fun queryDisplayName(uri: Uri): String {
        var name = uri.lastPathSegment ?: "unknown.mcp"
        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && cursor.moveToFirst()) {
                name = cursor.getString(index)
            }
        }
        return name
    }
}
