package com.taobao.android.mnndemo

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.*
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.taobao.android.mnn.MNNForwardType
import com.taobao.android.mnn.MNNImageProcess
import com.taobao.android.mnn.MNNNetInstance
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {

    private val PICK_IMAGE = 1
    private val TAKE_PHOTO = 2
    private val REQUEST_GALLERY_PERMISSION_CODE = 100
    private val REQUEST_CAMERA_PERMISSION_CODE = 101
    private var currentPhotoUri: Uri? = null

    private lateinit var imageContainer: FrameLayout
    private lateinit var placeholderImage: TextView
    private var imageView: ImageView? = null
    private lateinit var resultContainer: FrameLayout
    private lateinit var placeholderResult: TextView
    private lateinit var btnSelect: Button
    private lateinit var btnRun: Button
    // 验证按钮
    private lateinit var btnValidate: Button

    private var mnnNet: MNNNetInstance? = null
    private var mnnSession: MNNNetInstance.Session? = null
    private var selectedBitmap: Bitmap? = null

    // 标签映射
    private lateinit var labelMap: Map<Int, String>
    // 【关键】WNID (n01440764) -> 索引 (Int) 映射表
    private lateinit var wnidMap: MutableMap<String, Int>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // 组件绑定
        btnSelect = findViewById(R.id.button2)
        btnRun = findViewById(R.id.button3)
        btnValidate = findViewById(R.id.btn_validate)

        imageContainer = findViewById(R.id.image_container)
        placeholderImage = findViewById(R.id.placeholder_image)
        resultContainer = findViewById(R.id.result_container)
        placeholderResult = findViewById(R.id.placeholder_result)

        btnSelect.setOnClickListener {
            openImagePicker()
        }

        btnRun.setOnClickListener {
            selectedBitmap?.let { runModel(it) } ?: Toast.makeText(this, "请先选择一张图片", Toast.LENGTH_SHORT).show()
        }

        btnValidate.setOnClickListener {
            if (mnnSession == null) {
                Toast.makeText(this, "模型尚未初始化", Toast.LENGTH_SHORT).show()
            } else {
                runValidationFromAssets()
            }
        }

        initModel()
        loadLabelMap() // 加载分类标签
    }

    // --- 核心修复：加载标签的同时，必须填充 wnidMap ---
    private fun loadLabelMap() {
        try {
            val inputStream = assets.open("ImageNet-1k.txt")
            val buffer = ByteArray(inputStream.available())
            inputStream.read(buffer)
            val json = String(buffer, charset("UTF-8"))

            val jsonObject = JSONObject(json)
            val map = mutableMapOf<Int, String>()
            val wMap = mutableMapOf<String, Int>() // 必须初始化这个

            val keys = jsonObject.keys()
            while (keys.hasNext()) {
                val key = keys.next() // "0"
                val idx = key.toInt() // 0
                val array = jsonObject.getJSONArray(key)

                // 数据格式: ["n01440764", "tench"]
                val wnid = array.getString(0)
                val name = array.getString(1)

                map[idx] = name
                // 【重要】建立 n01440764 -> 0 的映射
                wMap[wnid] = idx
            }
            labelMap = map
            wnidMap = wMap // 赋值给全局变量
            Log.d("MNN_Demo", "标签加载完成，wnidMap大小: ${wnidMap.size}")
        } catch (e: Exception) {
            Toast.makeText(this, "标签映射加载失败: ${e.message}", Toast.LENGTH_LONG).show()
            e.printStackTrace()
        }
    }

    // --- 核心修复：验证逻辑使用与 runModel 一致的归一化参数 ---
    private fun runValidationFromAssets() {
        placeholderResult.text = "正在验证 val_data 数据集..."
        btnValidate.isEnabled = false
        btnRun.isEnabled = false

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                var totalImages = 0
                var top1Count = 0
                var top5Count = 0

                val rootDir = "val_small_data"
                val classDirs = assets.list(rootDir) ?: emptyArray()

                if (classDirs.isEmpty()) {
                    withContext(Dispatchers.Main) {
                        placeholderResult.text = "Assets中未找到 val_small_data 文件夹"
                        btnValidate.isEnabled = true
                        btnRun.isEnabled = true
                    }
                    return@launch
                }

                for (classDirName in classDirs) {
                    // 查找映射 ID
                    val groundTruthIndex = wnidMap[classDirName]

                    if (groundTruthIndex == null) {
                        Log.w("MNN_Demo", "跳过未知类别: $classDirName")
                        continue
                    }

                    val imagePath = "$rootDir/$classDirName"
                    val images = assets.list(imagePath) ?: emptyArray()

                    for (imgName in images) {
                        var bitmap: Bitmap? = null
                        try {
                            assets.open("$imagePath/$imgName").use { stream ->
                                bitmap = BitmapFactory.decodeStream(stream)
                            }
                        } catch (e: Exception) { continue }

                        if (bitmap != null) {
                            totalImages++

                            // 1. 预处理 (与 runModel 保持一致)
                            val processedBitmap = preprocessForMobileViT(bitmap!!)

                            val inputTensor = mnnSession?.getInput(null)

                            // 2. 转换 (【修复】使用你 runModel 中的参数)
                            MNNImageProcess.convertBitmap(
                                processedBitmap,
                                inputTensor,
                                MNNImageProcess.Config().apply {
                                    // 你的 runModel 使用的是 0 和 1/255，这里保持一致！
                                    mean = floatArrayOf(0f, 0f, 0f)
                                    normal = floatArrayOf(1 / 255f, 1 / 255f, 1 / 255f)
                                    source = MNNImageProcess.Format.RGBA
                                    dest = MNNImageProcess.Format.RGB
                                },
                                Matrix()
                            )

                            // 3. 推理
                            mnnSession?.run()

                            // 4. 获取结果
                            val outputTensor = mnnSession?.getOutput(null)
                            val scores = outputTensor?.getFloatData() ?: floatArrayOf()

                            val sortedIndices = scores.indices.sortedByDescending { scores[it] }
                            val top1Index = sortedIndices[0]
                            val top5Indices = sortedIndices.take(5)

                            val isRight = top1Index == groundTruthIndex
                            if (isRight) top1Count++
                            if (top5Indices.contains(groundTruthIndex)) top5Count++

                            // 【调试日志】如果准确率还是0，请查看 Logcat
                            if (totalImages <= 5 || !isRight) {
                                Log.i("MNN_Test", "Img: $classDirName/$imgName | GT: $groundTruthIndex | Pred: $top1Index | Res: $isRight")
                            }

                            // 回收资源
                            bitmap!!.recycle()
                            processedBitmap.recycle()

                            if (totalImages % 5 == 0) {
                                withContext(Dispatchers.Main) {
                                    val acc = if(totalImages > 0) (top1Count.toFloat()/totalImages)*100 else 0f
                                    placeholderResult.text = "正在验证 ($totalImages 张)...\n当前Top1: ${"%.1f".format(acc)}%"
                                }
                            }
                        }
                    }
                }

                val top1Acc = if (totalImages > 0) (top1Count.toFloat() / totalImages) * 100 else 0f
                val top5Acc = if (totalImages > 0) (top5Count.toFloat() / totalImages) * 100 else 0f

                withContext(Dispatchers.Main) {
                    val resultText = "验证完成 (共 $totalImages 张)\n" +
                            "Top-1 准确率: ${"%.2f".format(top1Acc)}%\n" +
                            "Top-5 准确率: ${"%.2f".format(top5Acc)}%"
                    placeholderResult.text = resultText
                    btnValidate.isEnabled = true
                    btnRun.isEnabled = true
                }
            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    placeholderResult.text = "验证出错: ${e.message}"
                    btnValidate.isEnabled = true
                    btnRun.isEnabled = true
                }
            }
        }
    }

    // --- 以下是你原本的代码，保持不变 ---

    private fun checkPermissions(): Boolean {
        val galleryPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_IMAGES
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
        return ContextCompat.checkSelfPermission(this, galleryPermission) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestPermissions() {
        val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(Manifest.permission.READ_MEDIA_IMAGES)
        } else {
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        ActivityCompat.requestPermissions(this, permissions, REQUEST_GALLERY_PERMISSION_CODE)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_GALLERY_PERMISSION_CODE) {
            if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                openImagePicker()
            } else {
                Toast.makeText(this, "需要图库权限才能选择图片", Toast.LENGTH_SHORT).show()
            }
        } else if (requestCode == REQUEST_CAMERA_PERMISSION_CODE) {
            if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                takePhoto()
            } else {
                Toast.makeText(this, "需要相机权限才能拍照", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun initModel() {
        try {
            val modelName = "mobilevit_qat_eps3_int8.mnn"
            val tempFile = File(cacheDir, modelName)
            if (!tempFile.exists()) {
                assets.open(modelName).use { input ->
                    FileOutputStream(tempFile).use { output ->
                        input.copyTo(output)
                    }
                }
            }

            mnnNet = MNNNetInstance.createFromFile(tempFile.absolutePath)
            val config = MNNNetInstance.Config().apply {
                forwardType = MNNForwardType.FORWARD_CPU.type
                numThread = 1
            }
            mnnSession = mnnNet?.createSession(config)

            placeholderResult.text = "MNN模型加载成功"
            Toast.makeText(this, "MNN模型加载成功", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "MNN模型加载失败: ${e.message}", Toast.LENGTH_LONG).show()
            e.printStackTrace()
            placeholderResult.text = "模型加载失败: ${e.message}"
        }
    }

    private fun openImagePicker() {
        val options = arrayOf("拍照", "从相册选择")
        AlertDialog.Builder(this)
            .setTitle("选择图片")
            .setItems(options) { _, which -> if (which == 0) takePhoto() else pickFromGallery() }
            .show()
    }

    private fun takePhoto() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.CAMERA),
                REQUEST_CAMERA_PERMISSION_CODE
            )
            return
        }
        val values = ContentValues()
        values.put(MediaStore.Images.Media.TITLE, "New Picture")
        values.put(MediaStore.Images.Media.DESCRIPTION, "From Camera")
        currentPhotoUri =
            contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)

        val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
        intent.putExtra(MediaStore.EXTRA_OUTPUT, currentPhotoUri)
        startActivityForResult(intent, TAKE_PHOTO)
    }

    private fun pickFromGallery() {
        val intent = Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
        startActivityForResult(intent, PICK_IMAGE)
    }

    @RequiresApi(Build.VERSION_CODES.N)
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode == Activity.RESULT_OK) {
            when (requestCode) {
                PICK_IMAGE -> {
                    data?.data?.let { uri ->
                        val bitmap = loadOrientedBitmap(uri)
                        displayImage(bitmap)
                    }
                }

                TAKE_PHOTO -> {
                    val bitmap = currentPhotoUri?.let { loadOrientedBitmap(it) }
                        ?: data?.extras?.get("data") as? Bitmap
                    bitmap?.let { displayImage(it) }
                }
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.N)
    private fun loadOrientedBitmap(uri: Uri): Bitmap {
        val input = contentResolver.openInputStream(uri)
        val exif = android.media.ExifInterface(input!!)
        val orientation = exif.getAttributeInt(
            android.media.ExifInterface.TAG_ORIENTATION,
            android.media.ExifInterface.ORIENTATION_NORMAL
        )
        val matrix = Matrix()
        when (orientation) {
            android.media.ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            android.media.ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            android.media.ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
        }
        val bitmap = BitmapFactory.decodeStream(contentResolver.openInputStream(uri))
        return if (matrix.isIdentity) bitmap else Bitmap.createBitmap(
            bitmap,
            0,
            0,
            bitmap.width,
            bitmap.height,
            matrix,
            true
        )
    }

    private fun displayImage(bitmap: Bitmap) {
        selectedBitmap = bitmap
        placeholderImage.text = ""
        if (imageView == null) {
            imageView = ImageView(this).apply {
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
                scaleType = ImageView.ScaleType.FIT_CENTER
                imageContainer.addView(this)
            }
        }
        imageView?.setImageBitmap(bitmap)
    }

    // --- 完整保留你原本的 runModel 逻辑 ---
    private fun runModel(bitmap: Bitmap) {
        placeholderResult.text = "正在测试100次推理..."
        btnRun.isEnabled = false

        lifecycleScope.launch(Dispatchers.IO) {
            var totalPreprocessTime = 0L
            var totalInferenceTime = 0L
            var totalPostprocessTime = 0L
            var successCount = 0

            var lastInferenceTime = 0.0
            var lastResultText = ""
            var lastOutputTensor: FloatArray? = null

            repeat(100) { i ->
                try {
                    val preprocessStart = System.nanoTime()
                    val processedBitmap = preprocessForMobileViT(bitmap)
                    val inputTensor = mnnSession?.getInput(null) ?: throw RuntimeException("模型未初始化")

                    // 这里的参数是你要求保留的
                    val convertSuccess = MNNImageProcess.convertBitmap(
                        processedBitmap,
                        inputTensor,
                        MNNImageProcess.Config().apply {
                            mean = floatArrayOf(0f, 0f, 0f)
                            normal = floatArrayOf(1 / 255f, 1 / 255f, 1 / 255f)
                            source = MNNImageProcess.Format.RGBA
                            dest = MNNImageProcess.Format.RGB
                        },
                        Matrix()
                    )
                    if (!convertSuccess) throw RuntimeException("图像预处理失败")
                    val preprocessEnd = System.nanoTime()
                    totalPreprocessTime += (preprocessEnd - preprocessStart)

                    val inferenceStart = System.nanoTime()
                    mnnSession?.run()
                    val inferenceEnd = System.nanoTime()
                    val currentInferenceTime = (inferenceEnd - inferenceStart) / 1_000_000.0
                    totalInferenceTime += (inferenceEnd - inferenceStart)

                    val postprocessStart = System.nanoTime()
                    val outputTensor = mnnSession?.getOutput(null) ?: throw RuntimeException("获取输出失败")
                    val scores = outputTensor.getFloatData()
                    if (i == 99) lastOutputTensor = scores
                    val postprocessEnd = System.nanoTime()
                    totalPostprocessTime += (postprocessEnd - postprocessStart)

                    if (i == 99) lastInferenceTime = currentInferenceTime

                    successCount++
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }

            val avgPreprocess = if (successCount > 0) totalPreprocessTime / successCount / 1_000_000.0 else 0.0
            val avgInference = if (successCount > 0) totalInferenceTime / successCount / 1_000_000.0 else 0.0
            val avgPostprocess = if (successCount > 0) totalPostprocessTime / successCount / 1_000_000.0 else 0.0

            lastOutputTensor?.let { scores ->
                val probs = softmax(scores)
                val topIndices = probs.indices.sortedByDescending { probs[it] }.take(5)
                lastResultText = topIndices.map { idx ->
                    "class $idx (${labelMap[idx] ?: "未知类别"}): ${"%.2f".format(probs[idx] * 100)}%"
                }.joinToString("\n")
            }

            withContext(Dispatchers.Main) {
                btnRun.isEnabled = true
                if (successCount == 0) {
                    placeholderResult.text = "所有测试均失败，请检查模型"
                    Toast.makeText(this@MainActivity, "推理测试失败", Toast.LENGTH_LONG).show()
                    return@withContext
                }

                val latencySummary = "[100次平均: 前${"%.1f".format(avgPreprocess)}ms/推${"%.1f".format(avgInference)}ms/后${"%.1f".format(avgPostprocess)}ms] "
                placeholderResult.text =
                    "${latencySummary}推理结果 (${lastInferenceTime} ms):\n$lastResultText"
            }
        }
    }

    private fun softmax(values: FloatArray): FloatArray {
        val max = values.maxOrNull() ?: 0f
        val exp = values.map { Math.exp((it - max).toDouble()).toFloat() }
        val sum = exp.sum()
        return exp.map { it / sum }.toFloatArray()
    }

    private fun preprocessForMobileViT(bitmap: Bitmap): Bitmap {
        val targetShort = (256 / 0.888f).roundToInt()
        val resized = if (bitmap.width < bitmap.height) {
            resizeWithBicubicInterpolation(
                bitmap,
                targetShort,
                (bitmap.height * targetShort.toFloat() / bitmap.width).roundToInt()
            )
        } else {
            resizeWithBicubicInterpolation(
                bitmap,
                (bitmap.width * targetShort.toFloat() / bitmap.height).roundToInt(),
                targetShort
            )
        }
        return centerCrop(resized, 256)
    }

    private fun resizeWithBicubicInterpolation(
        bitmap: Bitmap,
        targetWidth: Int,
        targetHeight: Int
    ): Bitmap {
        val config = bitmap.config ?: Bitmap.Config.ARGB_8888
        val resizedBitmap = Bitmap.createBitmap(targetWidth, targetHeight, config)
        val canvas = Canvas(resizedBitmap)
        val paint = Paint().apply { isFilterBitmap = true; isAntiAlias = true }
        canvas.drawBitmap(
            bitmap,
            null,
            RectF(0f, 0f, targetWidth.toFloat(), targetHeight.toFloat()),
            paint
        )
        return resizedBitmap
    }

    private fun centerCrop(src: Bitmap, size: Int): Bitmap {
        val x = (src.width - size) / 2
        val y = (src.height - size) / 2
        return Bitmap.createBitmap(src, x, y, size, size)
    }

    override fun onDestroy() {
        super.onDestroy()
        mnnSession?.release(); mnnSession = null
        mnnNet?.release(); mnnNet = null
        imageView = null
    }
}

