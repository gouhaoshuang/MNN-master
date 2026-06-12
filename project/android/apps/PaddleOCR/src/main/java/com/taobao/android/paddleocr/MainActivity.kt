package com.taobao.android.paddleocr

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private val detModelPath = "det4_fp32.mnn"
    // private val recModelPath = "rec4_fp32.mnn"
    // private val clsModelPath = "cls4_fp32.mnn"

    // private val detModelPath = "det4_fp16.mnn"
    private val recModelPath = "rec4_fp16.mnn"
    private val clsModelPath = "cls4_fp16.mnn"


    private val labelPath = "ocr_keys.txt"
    private val configPath = "config.txt"

    private val predictor = Native()
    private var currentInputPath: String = ""

    private lateinit var btnSelect: Button
    private lateinit var btnRun: Button
    private lateinit var btnValidate: Button // 新增：验证集测试按钮
    private lateinit var imageContainer: FrameLayout
    private lateinit var tvPlaceholder: TextView
    private lateinit var tvResult: TextView
    private var dynamicImageView: ImageView? = null
    private var modelInitialized: Boolean = false

    // 新增：验证用的工具常量
    private val valDirCandidates = arrayOf("imgVal", "imgval")
    private val IMG_EXTS = setOf(".jpg", ".jpeg", ".png", ".bmp", ".webp")

    // 原：Regex("""\[(\d+)\]\s+(.+?)\s+\(([\d.]+)\)""")
    // 改：用 [ \t]+ 代替 \s+，规避个别环境对 \s 的解析差异
    private val BOX_LINE_REGEX = Regex("""\[(\d+)\][ \t]+(.+?)[ \t]+\(([\d.]+)\)""")

    // ===== 新增：可配置的验证样本数上限（你只需修改这个值即可控制“测试多少个数据”）=====
    private var validateLimit: Int = 160
    // 例如：private var validateLimit: Int = 50

    companion object {
        const val REQUEST_PERMISSION_CODE = 100
        const val PICK_IMAGE = 1
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initView()
        initModel()
    }

    private fun initView() {
        btnSelect = findViewById(R.id.button2)
        btnRun = findViewById(R.id.button3)
        btnValidate = findViewById(R.id.button_validate) // 需要在 XML 中新增此按钮
        imageContainer = findViewById(R.id.image_container)
        tvPlaceholder = findViewById(R.id.placeholder_image)
        tvResult = findViewById(R.id.placeholder_result)

        btnSelect.setOnClickListener {
            if (!checkPermissions()) {
                requestPermissions()
            } else {
                openImagePicker()
            }
        }

        btnRun.setOnClickListener {
            if (currentInputPath.isNotEmpty()) {
                runInference()
            } else {
                Toast.makeText(this, "请先选择图片", Toast.LENGTH_SHORT).show()
            }
        }

        // 新增：验证按钮绑定
        btnValidate.setOnClickListener { runValidationOnAssets() }
    }

    private fun initModel() {
        if (modelInitialized) return
        tvResult.text = "正在初始化模型..."
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                // 拷贝模型
                Utils.copyAssets(this@MainActivity, detModelPath)
                Utils.copyAssets(this@MainActivity, clsModelPath)
                Utils.copyAssets(this@MainActivity, recModelPath)
                Utils.copyAssets(this@MainActivity, configPath)
                Utils.copyAssets(this@MainActivity, labelPath)

                val filesDir = getExternalFilesDir(null)
                val realDet = File(filesDir, detModelPath).absolutePath
                val realCls = File(filesDir, clsModelPath).absolutePath
                val realRec = File(filesDir, recModelPath).absolutePath
                val realCfg = File(filesDir, configPath).absolutePath
                val realLabel = File(filesDir, labelPath).absolutePath

                val ret = predictor.init(
                    this@MainActivity,
                    realDet, realCls, realRec, realCfg, realLabel,
                    1, "LITE_POWER_HIGH"
                )

                withContext(Dispatchers.Main) {
                    modelInitialized = ret
                    if (ret) tvResult.text = "模型加载成功" else tvResult.text = "模型初始化失败"
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun openImagePicker() {
        val intent = Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
        startActivityForResult(intent, PICK_IMAGE)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode == Activity.RESULT_OK && requestCode == PICK_IMAGE) {
            data?.data?.let { uri ->
                val cacheFile = uriToCacheFile(uri)
                if (cacheFile != null) {
                    currentInputPath = cacheFile.absolutePath
                    displayImage(currentInputPath)
                } else {
                    Toast.makeText(this, "图片读取失败", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    // 将 Uri 内容复制到 App 私有缓存文件
    private fun uriToCacheFile(uri: Uri): File? {
        return try {
            val inputStream = contentResolver.openInputStream(uri) ?: return null
            val tempFile = File(cacheDir, "input_image_temp.jpg")
            val outputStream = FileOutputStream(tempFile)

            inputStream.use { input ->
                outputStream.use { output ->
                    input.copyTo(output)
                }
            }
            tempFile
        } catch (e: Exception) {
            e.printStackTrace()
            return null
        }
    }

    private fun displayImage(path: String) {
        val bitmap = BitmapFactory.decodeFile(path)
        if (dynamicImageView == null) {
            dynamicImageView = ImageView(this).apply {
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
                scaleType = ImageView.ScaleType.FIT_CENTER
            }
            imageContainer.addView(dynamicImageView)
        }
        tvPlaceholder.visibility = View.GONE
        dynamicImageView?.setImageBitmap(bitmap)
        tvResult.text = "图片已就绪，点击运行"
    }

    private fun runInference() {
        if (currentInputPath.isEmpty()) {
            Toast.makeText(this, "请先选择图片", Toast.LENGTH_SHORT).show()
            return
        }

        btnRun.isEnabled = false
        tvResult.text = "正在进行 0次热身 + 20次测速，请稍候..."

        lifecycleScope.launch(Dispatchers.IO) {
            val savePath = File(externalCacheDir, "ocr_result.jpg").absolutePath

            // 1. 热身 (Warmup)
            repeat(0) {
                predictor.processPath(currentInputPath, savePath)
            }

            // 2. 正式测试 (Benchmark)
            val loopCount = 20
            var totalDet = 0.0
            var totalCls = 0.0
            var totalRec = 0.0
            var successCount = 0

            var finalRecognizedText = ""
            var lastResultStr = ""

            for (i in 1..loopCount) {
                if (i % 2 == 0) {
                    withContext(Dispatchers.Main) {
                        tvResult.text = "正在测速: $i / $loopCount"
                    }
                }

                val resultStr = predictor.processPath(currentInputPath, savePath)
                lastResultStr = resultStr

                if (!resultStr.startsWith("Error")) {
                    val parts = resultStr.split("#SPLIT#")
                    val timeInfo = parts[0]
                    if (parts.size > 1) {
                        finalRecognizedText = parts[1]
                    }

                    val latencies = parseLatencies(timeInfo)
                    if (latencies != null) {
                        totalDet += latencies[0]
                        totalCls += latencies[1]
                        totalRec += latencies[2]
                        successCount++
                    }
                }
            }

            withContext(Dispatchers.Main) {
                btnRun.isEnabled = true

                if (successCount > 0) {
                    val avgDet = totalDet / successCount
                    val avgCls = totalCls / successCount
                    val avgRec = totalRec / successCount
                    val avgTotal = avgDet + avgCls + avgRec

//                    val resBitmap = BitmapFactory.decodeFile(savePath)
//                    dynamicImageView?.setImageBitmap(resBitmap)

                    val sb = StringBuilder()
                    sb.append(
                        String.format(
                            "平均耗时(%d次):\nDet:%.1fms + Cls:%.1fms + Rec:%.1fms\n= 总计: %.1fms\n\n",
                            loopCount, avgDet, avgCls, avgRec, avgTotal
                        )
                    )
                    sb.append("=== 识别结果 ===\n")
                    if (finalRecognizedText.isNotEmpty()) {
                        sb.append(finalRecognizedText)
                    } else {
                        sb.append("（未识别到文本或C++未返回文本）")
                    }

                    tvResult.text = sb.toString()
                    Toast.makeText(this@MainActivity, "测速完成", Toast.LENGTH_SHORT).show()
                } else {
                    tvResult.text = "测速失败: $lastResultStr"
                }
            }
        }
    }

    // 使用正则表达式从字符串中提取3个浮点数
    private fun parseLatencies(timeInfoStr: String): DoubleArray? {
        try {
            val regex = Regex("Det:([0-9.]+)ms.*Cls:([0-9.]+)ms.*Rec:([0-9.]+)ms")
            val matchResult = regex.find(timeInfoStr)

            if (matchResult != null && matchResult.groupValues.size >= 4) {
                val det = matchResult.groupValues[1].toDouble()
                val cls = matchResult.groupValues[2].toDouble()
                val rec = matchResult.groupValues[3].toDouble()
                return doubleArrayOf(det, cls, rec)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return null
    }

    private fun checkPermissions(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun requestPermissions() {
        val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(Manifest.permission.READ_MEDIA_IMAGES)
        } else {
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
        ActivityCompat.requestPermissions(this, permissions, REQUEST_PERMISSION_CODE)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_PERMISSION_CODE) {
            val granted = grantResults.isNotEmpty() &&
                    grantResults.all { it == PackageManager.PERMISSION_GRANTED }
            if (granted) {
                openImagePicker()
            } else {
                Toast.makeText(this, "需要相册权限才能选择图片", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        predictor.release()
    }

    // ==========================
    //  以下为“验证集测试”新增逻辑
    // ==========================

    // 验证集评测主入口
    private fun runValidationOnAssets() {
        btnValidate.isEnabled = false
        tvResult.text = "正在从 assets 载入数据并评测字符覆盖率，请稍候..."

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                // 1) 定位验证集目录
                val assetsDir = resolveValDirInAssets()
                if (assetsDir == null) {
                    withContext(Dispatchers.Main) {
                        btnValidate.isEnabled = true
                        tvResult.text = "未在 assets 下找到 imgVal 或 imgval 目录"
                    }
                    return@launch
                }

                // 2) 准备 GT 文件
                val filesDir = getExternalFilesDir(null)!!
                val gtFile = File(filesDir, "GT.txt")
                if (!copyAssetToFile("GT.txt", gtFile)) {
                    withContext(Dispatchers.Main) {
                        btnValidate.isEnabled = true
                        tvResult.text = "无法读取 assets/GT.txt"
                    }
                    return@launch
                }

                // 3) 列出图片并（按 validateLimit）裁剪后逐张预测
                val imgNamesAll = listImagesInAssetsDir(assetsDir)
                if (imgNamesAll.isEmpty()) {
                    withContext(Dispatchers.Main) {
                        btnValidate.isEnabled = true
                        tvResult.text = "assets/$assetsDir 目录为空或无可识别图片"
                    }
                    return@launch
                }

                // 只取前 validateLimit 个样本做验证
                val selectedImgNames = imgNamesAll.take(validateLimit.coerceAtLeast(0))
                val total = selectedImgNames.size

                val predFile = File(filesDir, "result_pred.txt")
                val sb = StringBuilder()
                var processed = 0

                for (name in selectedImgNames) {
                    // 复制图片到 cache，得到可读路径
                    val localIn = File(cacheDir, name)
                    if (!copyAssetToFile("$assetsDir/$name", localIn)) continue

                    val resultStr = predictor.processPath(localIn.absolutePath, "")   // 或 ""

                    val predText = parsePredictedText(resultStr)

                    // 写一段 compare_accuracy 风格的结果
                    sb.appendLine("===== $name =====")
                    sb.appendLine("NUM_BOXES: 1")
                    sb.appendLine("[1] ${sanitizeOneLine(predText)} (1.0000)")
                    sb.appendLine("===== END =====")
                    sb.appendLine()

                    processed++
                    if (processed % 10 == 0) {
                        withContext(Dispatchers.Main) {
                            tvResult.text = "评测中：$processed / $total"
                        }
                    }
                    localIn.delete()
                }

                // 4) 落盘预测结果
                predFile.writeText(sb.toString(), Charsets.UTF_8)

                // 5) 将 GT 过滤为仅包含所选图片的子集，避免未预测样本被计入对比
                val filteredGtFile = File(filesDir, "GT_subset.txt")
                filterResultFileByImages(
                    srcPath = gtFile.absolutePath,
                    dstFile = filteredGtFile,
                    allowedImageNames = selectedImgNames.toSet()
                )

                // 6) 计算覆盖率（使用子集 GT 与预测结果）
                val (maxCov, minCov, avgCov) = getGlobalCoverageStatsKotlin(
                    filteredGtFile.absolutePath,
                    predFile.absolutePath
                )

                withContext(Dispatchers.Main) {
                    btnValidate.isEnabled = true
                    tvResult.text = buildString {
                        appendLine("验证完成：共 $total 张图片（限制：$validateLimit）")
                        appendLine(String.format(Locale.US, "最大字符覆盖率：%.2f%%", maxCov * 100))
                        appendLine(String.format(Locale.US, "最小字符覆盖率：%.2f%%", minCov * 100))
                        appendLine(String.format(Locale.US, "平均字符覆盖率：%.2f%%", avgCov * 100))
                    }
                    Toast.makeText(this@MainActivity, "验证完成", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    btnValidate.isEnabled = true
                    tvResult.text = "验证失败：" + (e.message ?: "未知错误")
                }
            }
        }
    }

    // assets 中定位 imgVal/imgval
    private fun resolveValDirInAssets(): String? {
        for (d in valDirCandidates) {
            try {
                val list = assets.list(d) ?: continue
                if (list.isNotEmpty()) return d
            } catch (_: Exception) { }
        }
        return null
    }

    // 列出目录下的图片
    private fun listImagesInAssetsDir(dir: String): List<String> {
        val all = assets.list(dir)?.toList() ?: emptyList()
        return all.filter { name ->
            val low = name.lowercase(Locale.US)
            IMG_EXTS.any { low.endsWith(it) }
        }.sorted()
    }

    // 复制 assets 文件到目标 File
    private fun copyAssetToFile(assetPath: String, outFile: File): Boolean {
        return try {
            assets.open(assetPath).use { input ->
                FileOutputStream(outFile).use { out ->
                    input.copyTo(out)
                }
            }
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    // 从 "timeInfo #SPLIT# 内容" 中取识别文本
    private fun parsePredictedText(resultStr: String): String {
        if (resultStr.startsWith("Error")) return ""
        val parts = resultStr.split("#SPLIT#")
        return if (parts.size > 1) parts[1] else ""
    }

    // 解析 compare_accuracy 风格结果，返回：图片 -> 归一化后的文本列表
    private fun getNormalizedTextPerImageFromFile(path: String): Map<String, List<String>> {
        val map = LinkedHashMap<String, MutableList<String>>()
        var current: String? = null

        File(path).useLines(charset = Charsets.UTF_8) { seq ->
            seq.forEach { raw ->
                val line = raw.trim()
                if (line.startsWith("=====") && line.endsWith("=====") && !line.contains("END")) {
                    val img = line.replace("=====", "").trim()
                    current = img
                    map.getOrPut(img) { mutableListOf() }
                } else if (line.startsWith("[")) {
                    val m = BOX_LINE_REGEX.find(line)
                    if (m != null) {
                        val text = m.groupValues[2]
                        val norm = normalizeTextK(text)
                        if (norm.isNotEmpty()) {
                            val key = current ?: return@forEach
                            map.getOrPut(key) { mutableListOf() }.add(norm)
                        }
                    }
                }
            }
        }
        return map
    }

    // 按图片统计字符频次
    private fun getCharStatsPerImage(path: String): Map<String, Map<Char, Int>> {
        val perImageTexts = getNormalizedTextPerImageFromFile(path)
        val out = LinkedHashMap<String, Map<Char, Int>>()
        for ((img, texts) in perImageTexts) {
            val combined = texts.joinToString(separator = "")
            val counter = HashMap<Char, Int>()
            combined.forEach { ch ->
                counter[ch] = (counter[ch] ?: 0) + 1
            }
            out[img] = counter
        }
        return out
    }

    // 计算全局 (max, min, avg)
    private fun getGlobalCoverageStatsKotlin(gtPath: String, predPath: String): Triple<Double, Double, Double> {
        val gtStats = getCharStatsPerImage(gtPath)
        val predStats = getCharStatsPerImage(predPath)

        if (gtStats.isEmpty()) return Triple(0.0, 0.0, 0.0)

        val coverages = ArrayList<Double>()

        for ((img, gtDict) in gtStats) {
            val gtTotal = gtDict.values.sum()
            if (gtTotal == 0) {
                coverages.add(0.0)
                continue
            }
            val predDict = predStats[img].orEmpty()
            var matched = 0
            for ((ch, gtCnt) in gtDict) {
                val predCnt = predDict[ch] ?: 0
                matched += minOf(gtCnt, predCnt)
            }
            val cov = matched.toDouble() / gtTotal.toDouble()
            coverages.add(cov)
        }

        val maxCov = coverages.maxOrNull() ?: 0.0
        val minCov = coverages.minOrNull() ?: 0.0
        val avgCov = if (coverages.isNotEmpty()) coverages.average() else 0.0
        return Triple(maxCov, minCov, avgCov)
    }

    // 文本标准化（等价 Python 的 normalize_text）
    private fun normalizeTextK(textIn: String): String {
        var text = normalizeLatexK(textIn)

        // 去空白
        text = text.replace(" ", "")
            .replace("\t", "")
            .replace("\n", "")
            .replace("\r", "")

        // 全角 -> 半角（常见符号）
        val puncMap = mapOf(
            '，' to ',', '。' to '.', '！' to '!', '？' to '?',
            '：' to ':', '；' to ';', '（' to '(', '）' to ')',
            '【' to '[', '】' to ']', '《' to '<', '》' to '>',
            '“' to '"', '”' to '"', '‘' to '\'', '’' to '\'',
            '、' to ',', '·' to '.', '—' to '-', '…' to '.'
        )
        val sb = StringBuilder(text.length)
        for (c in text) {
            sb.append(puncMap[c] ?: c)
        }
        text = sb.toString()

        // 英文小写
        return text.lowercase(Locale.US)
    }

    // LaTeX 预处理（等价 Python 的 normalize_latex）
    private fun normalizeLatexK(src: String): String {
        var text = src

        // 1) \frac{a}{b} -> a/b  （完全显式转义）
        text = Regex("\\\\frac\\s*\\{([^}]*)\\}\\s*\\{([^}]*)\\}")
            .replace(text, "\$1/\$2")

        // 2) 去掉数学定界符，保留中间内容
        text = Regex("""\$\$(.+?)\$\$""").replace(text, "$1")
        text = Regex("""\$(.+?)\$""").replace(text, "$1")
        text = Regex("""\\\((.+?)\\\)""").replace(text, "$1")
        text = Regex("""\\\[(.+?)\\\]""").replace(text, "$1")

        // 3) 去掉 \begin{...} 和 \end{...}
        text = Regex("\\\\begin\\{[^}]*\\}").replace(text, "")
        text = Regex("\\\\end\\{[^}]*\\}").replace(text, "")

        // 4) \command{X} -> X
        text = Regex("\\\\[a-zA-Z*]+\\s*\\{([^}]*)\\}")
            .replace(text, "$1")

        // 5) 处理剩余的 \command（希腊字母等）
        val greek = mapOf(
            "alpha" to "α", "beta" to "β", "gamma" to "γ", "delta" to "δ",
            "epsilon" to "ε", "zeta" to "ζ", "eta" to "η", "theta" to "θ",
            "iota" to "ι", "kappa" to "κ", "lambda" to "λ", "mu" to "μ",
            "nu" to "ν", "xi" to "ξ", "omicron" to "ο", "pi" to "π",
            "rho" to "ρ", "sigma" to "σ", "tau" to "τ", "upsilon" to "υ",
            "phi" to "φ", "chi" to "χ", "psi" to "ψ", "omega" to "ω",
            "Alpha" to "Α", "Beta" to "Β", "Gamma" to "Γ", "Delta" to "Δ",
            "Theta" to "Θ", "Lambda" to "Λ", "Xi" to "Ξ", "Pi" to "Π",
            "Sigma" to "Σ", "Upsilon" to "Υ", "Phi" to "Φ", "Psi" to "Ψ",
            "Omega" to "Ω"
        )
        text = Regex("\\\\([a-zA-Z]+)").replace(text) { m ->
            greek[m.groupValues[1]] ?: m.groupValues[1]
        }

        return text
    }

    // 清理单行文本，避免破坏结果文件格式
    private fun sanitizeOneLine(s: String): String {
        return s.replace("\r", " ")
            .replace("\n", " ")
            .replace("\t", " ")
            .trim()
    }

    // 把结果文件过滤成仅包含指定图片名的子集（用于 GT 子集）
    private fun filterResultFileByImages(
        srcPath: String,
        dstFile: File,
        allowedImageNames: Set<String>
    ) {
        val out = StringBuilder()
        var currentImage: String? = null
        var keep = false

        File(srcPath).useLines(charset = Charsets.UTF_8) { seq ->
            seq.forEach { raw ->
                val line = raw.trimEnd()
                if (line.startsWith("=====") && line.endsWith("=====") && !line.contains("END")) {
                    currentImage = line.replace("=====", "").trim()
                    keep = allowedImageNames.contains(currentImage)
                    if (keep) out.appendLine(line)
                } else if (line.startsWith("===== END =====")) {
                    if (keep) {
                        out.appendLine(line)
                        out.appendLine()
                    }
                    currentImage = null
                    keep = false
                } else {
                    if (keep) out.appendLine(line)
                }
            }
        }
        dstFile.writeText(out.toString(), Charsets.UTF_8)
    }
}
