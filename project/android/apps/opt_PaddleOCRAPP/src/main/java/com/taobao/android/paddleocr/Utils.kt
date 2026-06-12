package com.taobao.android.paddleocr

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

object Utils {
    fun copyAssets(context: Context, path: String) {
        val assetManager = context.assets
        var files: Array<String>? = null
        try {
            files = assetManager.list(path)
        } catch (e: IOException) {
            e.printStackTrace()
        }
        if (files != null && files.isNotEmpty()) {
            val file = File(context.getExternalFilesDir(null), path)
            if (!file.exists()) {
                if (!file.mkdirs()) {
                    return
                }
            }
            for (filename in files) {
                copyAssets(context, "$path/$filename")
            }
        } else {
            val file = File(context.getExternalFilesDir(null), path)
            if (!file.exists()) {
                try {
                    val `is` = assetManager.open(path)
                    val fos = FileOutputStream(file)
                    val buffer = ByteArray(1024)
                    var read: Int
                    while (`is`.read(buffer).also { read = it } != -1) {
                        fos.write(buffer, 0, read)
                    }
                    `is`.close()
                    fos.close()
                } catch (e: IOException) {
                    e.printStackTrace()
                }
            }
        }
    }
}