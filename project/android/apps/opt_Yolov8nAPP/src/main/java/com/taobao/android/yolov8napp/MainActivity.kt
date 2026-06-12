package com.taobao.android.yolov8napp

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
import com.taobao.android.yolov8napp.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {

    private val PICK_IMAGE = 1
    private val TAKE_PHOTO = 2
    private val REQUEST_GALLERY_PERMISSION_CODE = 100
    private val REQUEST_CAMERA_PERMISSION_CODE = 101

    private var isTakingPhoto = false
    private var currentPhotoUri: Uri? = null
    private var originalBitmap: Bitmap? = null
    private lateinit var imageContainer: FrameLayout
    private lateinit var placeholderImage: TextView
    private var imageView: ImageView? = null
    private lateinit var resultContainer: FrameLayout
    private lateinit var placeholderResult: TextView
    private lateinit var btnSelect: Button
    private lateinit var btnRun: Button
    private lateinit var btnEval: Button

    private var mnnNet: MNNNetInstance? = null
    private var mnnSession: MNNNetInstance.Session? = null
    private var selectedBitmap: Bitmap? = null

    // COCO 80 类（Ultralytics/COCO 顺序）
    private lateinit var labelMap: Map<Int, String>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // 组件绑定
        btnSelect = findViewById(R.id.button2)
        btnRun = findViewById(R.id.button3)
        btnEval = findViewById(R.id.button_eval)
        imageContainer = findViewById(R.id.image_container)
        placeholderImage = findViewById(R.id.placeholder_image)
        resultContainer = findViewById(R.id.result_container)
        placeholderResult = findViewById(R.id.placeholder_result)

        btnSelect.setOnClickListener {
            openImagePicker()
        }

        btnRun.setOnClickListener {
            originalBitmap?.let { runModel(it) }
                ?: Toast.makeText(this, "请先选择一张图片", Toast.LENGTH_SHORT).show()
        }

        // 一个参数：控制测试的图片数量
        btnEval.setOnClickListener {
            evaluateValidationSet(maxImages = 1000) // ← 只改这里就能控制测试图片数量
        }

        initModel()
        loadLabelMap() // 80 类
    }

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
            val modelName = "yolov8n_standard_mobile_optimized.mnn"
            val tempFile = File(cacheDir, modelName)
            if (!tempFile.exists()) {
                assets.open(modelName).use { input ->
                    FileOutputStream(tempFile).use { output -> input.copyTo(output) }
                }
            }

            mnnNet = MNNNetInstance.createFromFile(tempFile.absolutePath)
            val config = MNNNetInstance.Config().apply {
                forwardType = MNNForwardType.FORWARD_CPU.type // 仍然 CPU
                numThread = 1                                  // 仍然单线程
            }
            mnnSession = mnnNet?.createSession(config) ?: throw RuntimeException("创建 Session 失败")
            val inputTensor = mnnSession?.getInput(null) ?: throw RuntimeException("获取输入 Tensor 失败")
            inputTensor.reshape(intArrayOf(1, 3, 640, 640))
            mnnSession?.reshape()

            placeholderResult.text = modelName+"模型加载成功"
            Toast.makeText(this, modelName+"模型加载成功", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "模型加载失败: ${e.message}", Toast.LENGTH_LONG).show()
            e.printStackTrace()
            placeholderResult.text = "模型加载失败: ${e.message}"
        }
    }

    private fun loadLabelMap() {
        val names = arrayOf(
            "person","bicycle","car","motorcycle","airplane","bus","train","truck","boat","traffic light",
            "fire hydrant","stop sign","parking meter","bench","bird","cat","dog","horse","sheep","cow",
            "elephant","bear","zebra","giraffe","backpack","umbrella","handbag","tie","suitcase","frisbee",
            "skis","snowboard","sports ball","kite","baseball bat","baseball glove","skateboard","surfboard","tennis racket","bottle",
            "wine glass","cup","fork","knife","spoon","bowl","banana","apple","sandwich","orange",
            "broccoli","carrot","hot dog","pizza","donut","cake","chair","couch","potted plant","bed",
            "dining table","toilet","tv","laptop","mouse","remote","keyboard","cell phone","microwave","oven",
            "toaster","sink","refrigerator","book","clock","vase","scissors","teddy bear","hair drier","toothbrush"
        )
        labelMap = names.mapIndexed { idx, s -> idx to s }.toMap()
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
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.TITLE, "New Picture")
            put(MediaStore.Images.Media.DESCRIPTION, "From Camera")
        }
        currentPhotoUri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
        val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
            putExtra(MediaStore.EXTRA_OUTPUT, currentPhotoUri)
        }
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
                PICK_IMAGE -> data?.data?.let { displayImage(loadOrientedBitmap(it)) }
                TAKE_PHOTO -> {
                    val bitmap = currentPhotoUri?.let { loadOrientedBitmap(it) } ?: data?.extras?.get("data") as? Bitmap
                    bitmap?.let { displayImage(it) }
                }
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.N)
    private fun loadOrientedBitmap(uri: Uri): Bitmap? {
        return try {
            val inputStream = contentResolver.openInputStream(uri) ?: return null
            val exif = android.media.ExifInterface(inputStream)
            val orientation = exif.getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, android.media.ExifInterface.ORIENTATION_NORMAL)
            inputStream.close()
            val newInputStream = contentResolver.openInputStream(uri) ?: return null
            val options = BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.ARGB_8888
                inMutable = true
            }
            val bitmap = BitmapFactory.decodeStream(newInputStream, null, options)
            newInputStream.close()
            if (bitmap == null) return null

            val matrix = Matrix().apply {
                when (orientation) {
                    android.media.ExifInterface.ORIENTATION_ROTATE_90 -> postRotate(90f)
                    android.media.ExifInterface.ORIENTATION_ROTATE_180 -> postRotate(180f)
                    android.media.ExifInterface.ORIENTATION_ROTATE_270 -> postRotate(270f)
                }
            }
            if (matrix.isIdentity) bitmap else Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true).also { bitmap.recycle() }
        } catch (e: Exception) {
            e.printStackTrace(); null
        }
    }

    private fun displayImage(bitmap: Bitmap?) {
        if (bitmap == null || bitmap.isRecycled) return
        selectedBitmap?.recycle(); selectedBitmap = null
        originalBitmap?.recycle()
        originalBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, true)
        selectedBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, true)

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
        imageView?.setImageBitmap(selectedBitmap)
    }

    private fun runModel(bitmap: Bitmap) {
        placeholderResult.text = "正在测试100次推理..."
        btnRun.isEnabled = false
        btnEval.isEnabled = false

        lifecycleScope.launch(Dispatchers.IO) {
            var totalPreprocessTime = 0L
            var totalInferenceTime = 0L
            var totalPostprocessTime = 0L
            var successCount = 0

            var lastInferenceTime = 0.0
            var lastBoxes: List<RectF>? = null
            var lastScores: List<Float>? = null
            var lastClassIds: List<Int>? = null

            repeat(100) { i ->
                try {
                    val preprocessStart = System.nanoTime()
                    val (processedBitmap, scale) = preprocessForYOLOv8n(bitmap)
                    val inputTensor = mnnSession?.getInput(null) ?: throw RuntimeException("模型未初始化")

                    val success = MNNImageProcess.convertBitmap(
                        processedBitmap, inputTensor,
                        MNNImageProcess.Config().apply {
                            mean = floatArrayOf(0f, 0f, 0f)
                            normal = floatArrayOf(1f / 255f, 1f / 255f, 1f / 255f)
                            source = MNNImageProcess.Format.RGBA
                            dest = MNNImageProcess.Format.RGB
                        },
                        Matrix()
                    )
                    val preprocessEnd = System.nanoTime()
                    processedBitmap.recycle()
                    if (!success) throw RuntimeException("图像预处理失败")
                    totalPreprocessTime += (preprocessEnd - preprocessStart)

                    val inferenceStart = System.nanoTime()
                    mnnSession?.run()
                    val inferenceEnd = System.nanoTime()
                    val currentInferenceTime = (inferenceEnd - inferenceStart) / 1_000_000.0
                    totalInferenceTime += (inferenceEnd - inferenceStart)

                    val postprocessStart = System.nanoTime()
                    val outputTensor = mnnSession?.getOutput(null) ?: throw RuntimeException("获取输出失败")

                    val (boxes, scores, classIds) = postprocessYOLOv8n(outputTensor, scale, bitmap.width, bitmap.height)
                    if (i == 99) {
                        lastBoxes = boxes; lastScores = scores; lastClassIds = classIds
                        lastInferenceTime = currentInferenceTime
                    }

                    val postprocessEnd = System.nanoTime()
                    totalPostprocessTime += (postprocessEnd - postprocessStart)
                    successCount++
                } catch (e: Exception) { e.printStackTrace() }
            }

            val avgPreprocess = if (successCount > 0) totalPreprocessTime / successCount / 1_000_000.0 else 0.0
            val avgInference = if (successCount > 0) totalInferenceTime / successCount / 1_000_000.0 else 0.0
            val avgPostprocess = if (successCount > 0) totalPostprocessTime / successCount / 1_000_000.0 else 0.0

            withContext(Dispatchers.Main) {
                btnRun.isEnabled = true
                btnEval.isEnabled = true
                if (successCount == 0) {
                    placeholderResult.text = "所有测试均失败，请检查模型与类别数设置"
                    Toast.makeText(this@MainActivity, "推理测试失败", Toast.LENGTH_LONG).show()
                    return@withContext
                }
                lastBoxes?.let { boxes ->
                    lastScores?.let { scores ->
                        lastClassIds?.let { classIds ->
                            originalBitmap?.let { bmp ->
                                val resultBitmap = drawDetections(bmp, boxes, scores, classIds)
                                imageView?.setImageBitmap(resultBitmap)
                            }
                        }
                    }
                }
                val latencySummary = "[100次平均: 前${"%.1f".format(avgPreprocess)}ms/推${"%.1f".format(avgInference)}ms/后${"%.1f".format(avgPostprocess)}ms]"
                val detectionCount = lastBoxes?.size ?: 0
                placeholderResult.text = "${latencySummary}\n最后一次推理: ${"%.2f".format(lastInferenceTime)}ms, 检测到 $detectionCount 个目标"
            }
        }
    }

    private fun preprocessForYOLOv8n(bitmap: Bitmap): Pair<Bitmap, Float> {
        val inputSize = 640
        val scale = inputSize.toFloat() / maxOf(bitmap.width, bitmap.height)
        val newWidth = (bitmap.width * scale).toInt()
        val newHeight = (bitmap.height * scale).toInt()
        val scaledBitmap = Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true)
        val canvasBitmap = Bitmap.createBitmap(inputSize, inputSize, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(canvasBitmap)
        canvas.drawColor(Color.rgb(114, 114, 114))
        val x = (inputSize - newWidth) / 2
        val y = (inputSize - newHeight) / 2
        canvas.drawBitmap(scaledBitmap, x.toFloat(), y.toFloat(), null)
        scaledBitmap.recycle()
        return Pair(canvasBitmap, scale)
    }

    /** 后处理：动态推断 numClasses，兼容 COCO 80 类或你的自定义类数 */
    private fun postprocessYOLOv8n(
        outputTensor: MNNNetInstance.Session.Tensor,
        scale: Float,
        originalWidth: Int,
        originalHeight: Int
    ): Triple<List<RectF>, List<Float>, List<Int>> {

        val CONF_THRESHOLD = 0.25f
        val IOU_THRESHOLD = 0.45f
        val numAnchors = 8400 // 640 输入下

        val outputData = outputTensor.getFloatData()
        if (outputData.isEmpty()) return Triple(emptyList(), emptyList(), emptyList())

        if (outputData.size % numAnchors != 0) {
            throw IllegalStateException("输出长度(${outputData.size})无法被 $numAnchors 整除")
        }
        val numChannels = outputData.size / numAnchors
        val numClasses = numChannels - 4
        if (numClasses <= 0) throw IllegalStateException("推断到的类别数异常: $numClasses")

        val predictions = Array(numAnchors) { FloatArray(numChannels) }
        for (i in 0 until numAnchors) {
            for (j in 0 until numChannels) {
                predictions[i][j] = outputData[j * numAnchors + i]
            }
        }

        val boxes = mutableListOf<RectF>()
        val scores = mutableListOf<Float>()
        val classIds = mutableListOf<Int>()

        val inputSize = 640
        val newWidth = (originalWidth * scale).toInt()
        val newHeight = (originalHeight * scale).toInt()
        val xPad = (inputSize - newWidth) / 2f
        val yPad = (inputSize - newHeight) / 2f

        for (i in 0 until numAnchors) {
            val cx = predictions[i][0]
            val cy = predictions[i][1]
            val w  = predictions[i][2]
            val h  = predictions[i][3]

            val classScores = predictions[i].sliceArray(4 until (4 + numClasses))
            val maxScore = classScores.maxOrNull() ?: 0f
            val classId = classScores.indexOfFirst { it == maxScore }

            if (maxScore < CONF_THRESHOLD) continue

            val x1 = cx - w / 2
            val y1 = cy - h / 2
            val x2 = cx + w / 2
            val y2 = cy + h / 2

            val rect = RectF(
                (x1 - xPad) / scale,
                (y1 - yPad) / scale,
                (x2 - xPad) / scale,
                (y2 - yPad) / scale
            )
            boxes.add(rect)
            scores.add(maxScore)
            classIds.add(classId)
        }

        if (boxes.isEmpty()) return Triple(emptyList(), emptyList(), emptyList())

        val keepIndices = nms(boxes, scores, IOU_THRESHOLD)
        return Triple(keepIndices.map { boxes[it] }, keepIndices.map { scores[it] }, keepIndices.map { classIds[it] })
    }

    private fun nms(boxes: List<RectF>, scores: List<Float>, iouThreshold: Float): List<Int> {
        val indices = scores.indices.sortedByDescending { scores[it] }.toMutableList()
        val keep = mutableListOf<Int>()
        while (indices.isNotEmpty()) {
            val current = indices.removeAt(0)
            keep.add(current)
            val remaining = indices.toList()
            indices.clear()
            for (i in remaining) {
                val iou = calculateIoU(boxes[current], boxes[i])
                if (iou <= iouThreshold) indices.add(i)
            }
        }
        return keep
    }

    private fun calculateIoU(box1: RectF, box2: RectF): Float {
        val interLeft = maxOf(box1.left, box2.left)
        val interTop = maxOf(box1.top, box2.top)
        val interRight = minOf(box1.right, box2.right)
        val interBottom = minOf(box1.bottom, box2.bottom)
        if (interRight <= interLeft || interBottom <= interTop) return 0f
        val interArea = (interRight - interLeft) * (interBottom - interTop)
        val area1 = (box1.right - box1.left) * (box1.bottom - box1.top)
        val area2 = (box2.right - box2.left) * (box2.bottom - box2.top)
        val unionArea = area1 + area2 - interArea
        return interArea / unionArea
    }

    private fun drawDetections(originalBitmap: Bitmap, boxes: List<RectF>, scores: List<Float>, classIds: List<Int>): Bitmap {
        val resultBitmap = originalBitmap.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(resultBitmap)
        val paint = Paint().apply {
            color = Color.GREEN
            style = Paint.Style.STROKE
            strokeWidth = 3f
            textSize = 28f
            typeface = Typeface.DEFAULT_BOLD
            isAntiAlias = true
        }
        for (i in boxes.indices) {
            val box = boxes[i]
            val score = scores[i]
            val classId = classIds[i]
            val label = "${labelMap[classId] ?: "unknown"} ${"%.1f".format(score * 100f)}%"
            canvas.drawRect(box, paint)
            paint.style = Paint.Style.FILL
            paint.color = Color.WHITE
            canvas.drawText(label, box.left, box.top - 5f, paint)
            paint.style = Paint.Style.STROKE
            paint.color = Color.GREEN
            canvas.drawText(label, box.left, box.top - 5f, paint)
        }
        return resultBitmap
    }

    override fun onDestroy() {
        super.onDestroy()
        mnnSession?.release(); mnnSession = null
        mnnNet?.release(); mnnNet = null
        imageView = null
    }

    // ====== 以下为验证集 mAP（COCO val2017） ======

    data class ImageInfo(val id: Int, val fileName: String, val width: Int, val height: Int)
    data class AnnInfo(val box: RectF, val cls: Int)

    /** COCO category_id → 0..79 索引映射 */
    private val cocoCatIdToIdx: Map<Int, Int> by lazy {
        val ids = intArrayOf(
            1,2,3,4,5,6,7,8,9,10,11,13,14,15,16,17,18,19,20,21,22,23,24,25,27,28,31,32,33,34,35,36,37,38,39,40,
            41,42,43,44,46,47,48,49,50,51,52,53,54,55,56,57,58,59,60,61,62,63,64,65,67,70,72,73,74,75,76,77,78,79,
            80,81,82,84,85,86,87,88,89,90
        )
        ids.mapIndexed { idx, id -> id to idx }.toMap()
    }

    /** 读入 COCO instances_val2017.json（只保留 80 类） */
    private fun loadCocoAnnotations(jsonPathInAssets: String = "annotations/instances_val2017.json")
            : Pair<Map<Int, ImageInfo>, Map<Int, List<AnnInfo>>> {

        val jsonStr = assets.open(jsonPathInAssets).bufferedReader().use { it.readText() }
        val root = JSONObject(jsonStr)

        val imagesArr = root.getJSONArray("images")
        val images = mutableMapOf<Int, ImageInfo>()
        for (i in 0 until imagesArr.length()) {
            val obj = imagesArr.getJSONObject(i)
            val id = obj.getInt("id")
            val fileName = obj.getString("file_name")
            val width = obj.getInt("width")
            val height = obj.getInt("height")
            images[id] = ImageInfo(id, fileName, width, height)
        }

        val annsMap = mutableMapOf<Int, MutableList<AnnInfo>>()
        val annsArr = root.getJSONArray("annotations")
        for (i in 0 until annsArr.length()) {
            val obj = annsArr.getJSONObject(i)
            val imgId = obj.getInt("image_id")
            val catId = obj.getInt("category_id")
            val cls = cocoCatIdToIdx[catId] ?: continue
            val bbox = obj.getJSONArray("bbox") // [x,y,w,h]
            val x = bbox.getDouble(0).toFloat()
            val y = bbox.getDouble(1).toFloat()
            val w = bbox.getDouble(2).toFloat()
            val h = bbox.getDouble(3).toFloat()
            val ann = AnnInfo(RectF(x, y, x + w, y + h), cls)
            annsMap.getOrPut(imgId) { mutableListOf() }.add(ann)
        }

        return images to annsMap
    }

    /** 评测 mAP：实时显示 x/总量，mAP@0.5 和 mAP@0.5:0.95 */
    private fun evaluateValidationSet(maxImages: Int = 200) {
        btnRun.isEnabled = false
        btnEval.isEnabled = false
        placeholderResult.text = "准备验证集..."

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val (imageInfoMap, imageIdToAnns) = loadCocoAnnotations()
                val imageDir = "yolo_val"
                val allAssetFiles = assets.list(imageDir)?.toList()?.sorted() ?: emptyList()
                if (allAssetFiles.isEmpty()) {
                    withContext(Dispatchers.Main) {
                        placeholderResult.text = "assets/yolo_val 为空"
                        btnRun.isEnabled = true; btnEval.isEnabled = true
                    }
                    return@launch
                }

                val fileNameToImageId = imageInfoMap.values.associateBy({ it.fileName }, { it.id })
                val validFiles = allAssetFiles.filter { fn ->
                    val imgId = fileNameToImageId[fn]
                    val anns = if (imgId != null) imageIdToAnns[imgId] else null
                    imgId != null && anns != null && anns.isNotEmpty()
                }

                val total = validFiles.size.coerceAtMost(maxImages)
                if (total == 0) {
                    withContext(Dispatchers.Main) {
                        placeholderResult.text = "没有可评测的图片（标注缺失或未匹配 COCO 80 类）。"
                        btnRun.isEnabled = true; btnEval.isEnabled = true
                    }
                    return@launch
                }

                // mAP 评测缓存
                val numClasses = 80
                val iouThresholds = (0..9).map { 0.5f + it * 0.05f }.toFloatArray() // 0.50~0.95
                val T = iouThresholds.size

                // per-threshold per-class accumulators
                val predScores = Array(T) { Array(numClasses) { mutableListOf<Float>() } }
                val predTP = Array(T) { Array(numClasses) { mutableListOf<Int>() } }
                val gtCountPerClass = IntArray(numClasses) { 0 }

                var processed = 0

                for (idx in 0 until total) {
                    val fileName = validFiles[idx]
                    val imgId = fileNameToImageId[fileName] ?: continue
                    val anns = imageIdToAnns[imgId] ?: emptyList()

                    // 统计 GT
                    for (a in anns) gtCountPerClass[a.cls]++

                    // decode
                    val bmp = assets.open("$imageDir/$fileName").use { input -> BitmapFactory.decodeStream(input) } ?: continue

                    // 推理
                    val (inputBitmap, scale) = preprocessForYOLOv8n(bmp)
                    val inputTensor = mnnSession?.getInput(null) ?: run {
                        inputBitmap.recycle()
                        throw RuntimeException("模型未初始化")
                    }
                    val ok = MNNImageProcess.convertBitmap(
                        inputBitmap, inputTensor,
                        MNNImageProcess.Config().apply {
                            mean = floatArrayOf(0f, 0f, 0f)
                            normal = floatArrayOf(1f/255f, 1f/255f, 1f/255f)
                            source = MNNImageProcess.Format.RGBA
                            dest = MNNImageProcess.Format.RGB
                        },
                        Matrix()
                    )
                    inputBitmap.recycle()
                    if (!ok) { continue }

                    mnnSession?.run()
                    val outputTensor = mnnSession?.getOutput(null) ?: continue

                    val (predBoxes, predScoresAll, predClsAll) = postprocessYOLOv8n(outputTensor, scale, bmp.width, bmp.height)
                    // 不回收 bmp，避免 getWidth on recycled 警告；让 GC 处理

                    // —— 逐阈值、逐类进行贪心匹配（按分数降序）
                    // 先按图片内部对预测排序，确保匹配顺序稳定
//                    val order = predScoresAll.indices.sortedByDescending { predScoresAll[it] }
                    // 先按分数排序，再取前100个，和 COCO maxDets=100 对齐
                    val order = predScoresAll.indices
                        .sortedByDescending { predScoresAll[it] }
                        .take(100)
                    val sortedBoxes = order.map { predBoxes[it] }
                    val sortedScores = order.map { predScoresAll[it] }
                    val sortedCls = order.map { predClsAll[it] }

                    for (tIdx in 0 until T) {
                        val thr = iouThresholds[tIdx]

                        // 按类分别匹配，GT 不能被重复匹配
                        val gtByClass = Array(numClasses) { mutableListOf<RectF>() }
                        val gtUsedByClass = Array(numClasses) { mutableListOf<Boolean>() }
                        for (a in anns) {
                            gtByClass[a.cls].add(a.box)
                            gtUsedByClass[a.cls].add(false)
                        }

                        for (p in sortedBoxes.indices) {
                            val c = sortedCls[p]
                            val score = sortedScores[p]
                            // 在该类的 GT 中找 IoU 最大且未被使用的
                            val gts = gtByClass[c]
                            val used = gtUsedByClass[c]
                            var bestIou = 0f
                            var bestIdx = -1
                            for (g in gts.indices) {
                                if (used[g]) continue
                                val iou = calculateIoU(sortedBoxes[p], gts[g])
                                if (iou > bestIou) { bestIou = iou; bestIdx = g }
                            }
                            if (bestIdx >= 0 && bestIou >= thr) {
                                used[bestIdx] = true
                                predScores[tIdx][c].add(score)
                                predTP[tIdx][c].add(1)
                            } else {
                                predScores[tIdx][c].add(score)
                                predTP[tIdx][c].add(0)
                            }
                        }
                    }

                    processed += 1

                    // 实时计算与显示当前 mAP（可选：稍有开销，但数量不大可接受）
                    val map50 = computeMAP(predScores, predTP, gtCountPerClass, iouThresholds, only050 = true)
                    val map5095 = computeMAP(predScores, predTP, gtCountPerClass, iouThresholds, only050 = false)

                    withContext(Dispatchers.Main) {
                        placeholderResult.text = "${processed}/${total}，mAP@0.5=${"%.2f".format(map50*100)}%，mAP@0.5:0.95=${"%.2f".format(map5095*100)}%"
                    }
                }

                val finalMap50 = computeMAP(predScores, predTP, gtCountPerClass, iouThresholds, only050 = true)
                val finalMap5095 = computeMAP(predScores, predTP, gtCountPerClass, iouThresholds, only050 = false)

                withContext(Dispatchers.Main) {
                    placeholderResult.text = "完成：${processed}/${total}，mAP@0.5=${"%.2f".format(finalMap50*100)}%，mAP@0.5:0.95=${"%.2f".format(finalMap5095*100)}%"
                    btnRun.isEnabled = true
                    btnEval.isEnabled = true
                }
            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    placeholderResult.text = "评测异常：${e.message}"
                    btnRun.isEnabled = true
                    btnEval.isEnabled = true
                }
            }
        }
    }

    /** 计算 mAP：支持只算 0.5，或 0.50~0.95 平均 */
    private fun computeMAP(
        predScores: Array<Array<MutableList<Float>>>,
        predTP: Array<Array<MutableList<Int>>>,
        gtCountPerClass: IntArray,
        iouThresholds: FloatArray,
        only050: Boolean
    ): Float {
        val thresholds = if (only050) floatArrayOf(0.5f) else iouThresholds
        var apSum = 0.0
        var apCount = 0
        for (thr in thresholds) {
            val tIdx = iouThresholds.indexOfFirst { it == thr }
            if (tIdx < 0) continue
            for (c in gtCountPerClass.indices) {
                val nGT = gtCountPerClass[c]
                if (nGT == 0) continue // 该类在子集中没有 GT，按 COCO 不计入
                val scores = predScores[tIdx][c]
                val tps = predTP[tIdx][c]
                if (scores.isEmpty()) {
                    // 没有任何预测，AP=0
                    apSum += 0.0; apCount += 1
                    continue
                }
                // 全局按分数降序
                val order = scores.indices.sortedByDescending { scores[it] }
                var tpCum = 0
                var fpCum = 0
                val prec = ArrayList<Double>(order.size)
                val rec = ArrayList<Double>(order.size)
                for (i in order.indices) {
                    val isTP = tps[order[i]] == 1
                    if (isTP) tpCum++ else fpCum++
                    val precision = tpCum.toDouble() / max(1, tpCum + fpCum).toDouble()
                    val recall = tpCum.toDouble() / nGT.toDouble()
                    prec.add(precision)
                    rec.add(recall)
                }
                val ap = computeAPFromPR(rec, prec)
                apSum += ap
                apCount += 1
            }
        }
        return if (apCount == 0) 0f else (apSum / apCount).toFloat()
    }

    /** 计算 AP：对 precision 做包络，再按 101 个 recall 点取最大值平均（COCO 风格近似） */
    private fun computeAPFromPR(recall: List<Double>, precision: List<Double>): Double {
        if (recall.isEmpty()) return 0.0
        // 1) 对 (r, p) 做单调包络（从后往前取最大）
        val mrec = mutableListOf<Double>()
        val mpre = mutableListOf<Double>()
        mrec.add(0.0); mrec.addAll(recall); mrec.add(1.0)
        mpre.add(0.0); mpre.addAll(precision); mpre.add(0.0)
        for (i in mpre.size - 2 downTo 0) {
            mpre[i] = max(mpre[i], mpre[i + 1])
        }
        // 2) 101 点插值
        var ap = 0.0
        var i = 0
        for (t in 0..100) {
            val r = t / 100.0
            while (i < mrec.size - 1 && r > mrec[i + 1]) i++
            ap += (mpre[i + 1])
        }
        return ap / 101.0
    }
}
