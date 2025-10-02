package com.ileping.open_image_map

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import android.util.Base64
import android.Manifest
import android.content.pm.PackageManager
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.exifinterface.media.ExifInterface
import androidx.activity.result.contract.ActivityResultContracts
import org.json.JSONObject
import java.io.InputStream
import java.io.File
import java.io.FileOutputStream
import android.database.Cursor
import android.util.Log

class MainActivity : AppCompatActivity() {
    companion object {
        private const val PERMISSION_REQUEST_CODE = 100
        private const val TAG = "PhotoLocationViewer"
    }

    private lateinit var webView: WebView
    private lateinit var webAppInterface: WebAppInterface

    private var lastBackPressTime: Long = 0
    private var isWebViewReady = false  // WebView是否加载完成
    private var hasProcessedIntent = false  // 是否已处理过Intent，避免重复

    override fun onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack()
            return
        }

        val currentTime = System.currentTimeMillis()
        if (currentTime - lastBackPressTime > 2000) {
            Toast.makeText(this, "再按一次退出应用", Toast.LENGTH_SHORT).show()
            lastBackPressTime = currentTime
        } else {
            super.onBackPressed()
            finish()
        }
    }

    inner class WebAppInterface(private val context: Context) {

        private fun showToast(message: String) {
            (context as Activity).runOnUiThread { 
                Toast.makeText(context, message, Toast.LENGTH_SHORT).show() 
            }
        }

        private fun checkAndRequestPermission(): Boolean {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                // Android 13 及以上版本使用 READ_MEDIA_IMAGES
                val permission = Manifest.permission.READ_MEDIA_IMAGES
                if (ContextCompat.checkSelfPermission(context, permission) != PackageManager.PERMISSION_GRANTED) {
                    ActivityCompat.requestPermissions(this@MainActivity, arrayOf(permission), PERMISSION_REQUEST_CODE)
                    return false
                }
            } else if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                // Android 10-12 使用 READ_EXTERNAL_STORAGE
                val permission = Manifest.permission.READ_EXTERNAL_STORAGE
                if (ContextCompat.checkSelfPermission(context, permission) != PackageManager.PERMISSION_GRANTED) {
                    ActivityCompat.requestPermissions(this@MainActivity, arrayOf(permission), PERMISSION_REQUEST_CODE)
                    return false
                }
            } else {
                // Android 9 及以下版本使用 WRITE_EXTERNAL_STORAGE
                val permission = Manifest.permission.WRITE_EXTERNAL_STORAGE
                if (ContextCompat.checkSelfPermission(context, permission) != PackageManager.PERMISSION_GRANTED) {
                    ActivityCompat.requestPermissions(this@MainActivity, arrayOf(permission), PERMISSION_REQUEST_CODE)
                    return false
                }
            }
            return true
        }

        @JavascriptInterface
        fun saveImage(base64Image: String, fileName: String) {
            if (checkAndRequestPermission()) {
                saveImageToGallery(base64Image, fileName)
            }
        }

        @Suppress("DEPRECATION")
        private fun saveImageToGallery(base64Image: String, fileName: String) {
            try {
                val base64Data = base64Image.split(",")[1]
                val decodedBytes = Base64.decode(base64Data, Base64.DEFAULT)
                val bitmap = BitmapFactory.decodeByteArray(decodedBytes, 0, decodedBytes.size)
                
                MediaStore.Images.Media.insertImage(
                    context.contentResolver,
                    bitmap,
                    fileName,
                    "照片位置查看"
                )
                
                (context as Activity).runOnUiThread {
                    Toast.makeText(context, "图片已保存到相册", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                e.printStackTrace()
                (context as Activity).runOnUiThread {
                    Toast.makeText(context, "保存失败: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }

        /**
         * 检测高德地图是否安装（多种方式兼容）
         * 支持：中文版(minimap) 和 国际版Amap
         */
        private fun isAmapInstalled(): Boolean {
            val packageManager = context.packageManager
            
            // 支持的包名列表
            val amapPackages = listOf(
                "com.autonavi.minimap",      // 中文版高德地图
                "com.amap.android.ams",      // Amap国际版
                "com.autonavi.amapauto"      // 高德地图车机版
            )
            
            // 方式1: 直接检测包名
            for (packageName in amapPackages) {
                try {
                    packageManager.getApplicationInfo(packageName, 0)
                    Log.d(TAG, "✓ 检测成功：找到高德地图 - $packageName")
                    return true
                } catch (e: Exception) {
                    // 继续检测下一个
                }
            }
            
            // 方式2: 列出所有已安装应用，查找包含amap的
            try {
                val packages = packageManager.getInstalledApplications(0)
                for (packageInfo in packages) {
                    val pkgName = packageInfo.packageName
                    if (pkgName.contains("amap", ignoreCase = true) || 
                        pkgName.contains("autonavi", ignoreCase = true)) {
                        Log.d(TAG, "✓ 检测成功：找到高德相关应用 - $pkgName")
                        return true
                    }
                }
            } catch (e: Exception) {
                Log.d(TAG, "方式2检测失败")
            }
            
            Log.w(TAG, "❌ 未检测到高德地图")
            return false
        }
        
        /**
         * 获取已安装的高德地图包名
         */
        private fun getInstalledAmapPackage(): String? {
            val packageManager = context.packageManager
            
            // 优先级列表
            val amapPackages = listOf(
                "com.autonavi.minimap",      // 中文版（优先）
                "com.amap.android.ams",      // 国际版Amap
                "com.autonavi.amapauto"      // 车机版
            )
            
            for (packageName in amapPackages) {
                try {
                    packageManager.getApplicationInfo(packageName, 0)
                    return packageName
                } catch (e: Exception) {
                    // 继续查找
                }
            }
            
            // 查找任何amap相关的应用
            try {
                val packages = packageManager.getInstalledApplications(0)
                for (packageInfo in packages) {
                    val pkgName = packageInfo.packageName
                    if (pkgName.contains("amap", ignoreCase = true) || 
                        pkgName.contains("autonavi", ignoreCase = true)) {
                        return pkgName
                    }
                }
            } catch (e: Exception) {
                // ignore
            }
            
            return null
        }

        /**
         * 诊断高德地图安装情况（详细日志）
         */
        @JavascriptInterface
        fun diagnoseAmap(): String {
            val packageManager = context.packageManager
            val result = StringBuilder()
            
            result.append("=== 高德地图检测诊断 ===\n")
            
            // 列出所有包含"amap"的应用
            try {
                val packages = packageManager.getInstalledApplications(0)
                result.append("\n包含'amap'或'autonavi'的应用:\n")
                var found = false
                for (packageInfo in packages) {
                    val pkgName = packageInfo.packageName.lowercase()
                    if (pkgName.contains("amap") || 
                        pkgName.contains("autonavi") ||
                        pkgName.contains("gaode")) {  // 添加"高德"拼音
                        val appName = packageInfo.loadLabel(packageManager).toString()
                        result.append("  - ${packageInfo.packageName}\n")
                        result.append("    应用名: $appName\n")
                        found = true
                    }
                }
                if (!found) {
                    result.append("  (未找到相关应用)\n")
                    
                    // 列出所有地图相关应用
                    result.append("\n尝试查找所有地图相关应用:\n")
                    for (packageInfo in packages) {
                        val appName = packageInfo.loadLabel(packageManager).toString().lowercase()
                        if (appName.contains("map") || 
                            appName.contains("amap") ||
                            appName.contains("导航") ||
                            appName.contains("地图")) {
                            result.append("  - ${packageInfo.packageName}\n")
                            result.append("    应用名: ${packageInfo.loadLabel(packageManager)}\n")
                        }
                    }
                }
            } catch (e: Exception) {
                result.append("  错误: ${e.message}\n")
            }
            
            // 检测支持的包名
            result.append("\n检测支持的包名:\n")
            val supportedPackages = listOf(
                "com.autonavi.minimap" to "中文版高德地图",
                "com.amap.android.ams" to "国际版Amap",
                "com.autonavi.amapauto" to "高德地图车机版"
            )
            
            for ((pkg, name) in supportedPackages) {
                try {
                    packageManager.getApplicationInfo(pkg, 0)
                    result.append("  ✓ $name ($pkg)\n")
                } catch (e: Exception) {
                    result.append("  ✗ $name ($pkg)\n")
                }
            }
            
            // 检测实际安装的版本
            val installedPkg = getInstalledAmapPackage()
            result.append("\n检测结果: ")
            if (installedPkg != null) {
                result.append("✓ 已安装 ($installedPkg)\n")
            } else {
                result.append("✗ 未安装\n")
            }
            
            val diagResult = result.toString()
            Log.d(TAG, diagResult)
            return diagResult
        }
        
        @JavascriptInterface
        fun openInAMap(latitude: String, longitude: String, name: String) {
            (context as Activity).runOnUiThread {
                try {
                    // 先进行诊断
                    val diagnosis = diagnoseAmap()
                    val amapInstalled = isAmapInstalled()
                    Log.d(TAG, "高德地图检测结果: $amapInstalled")
                    
                    // 构建URI（按照官方文档标准格式）
                    val encodedName = android.net.Uri.encode(name, "UTF-8")
                    // viewMap查看地图，navi导航
                    // dev=0 表示使用高德坐标（GCJ-02火星坐标系）
                    val uriStr = "androidamap://viewMap?sourceApplication=照片位置查看&poiname=$encodedName&lat=$latitude&lon=$longitude&dev=0"
                    
                    Log.d(TAG, "准备打开高德地图")
                    Log.d(TAG, "URI: $uriStr")
                    
                    // ⭐ 按照官方文档的标准方式（关键修改）
                    val intent = Intent()
                    intent.action = Intent.ACTION_VIEW
                    intent.addCategory(Intent.CATEGORY_DEFAULT)
                    intent.data = Uri.parse(uriStr)
                    // ⚠️ 不使用 setPackage()，让系统自动选择能处理该URI的应用
                    
                    // 检查是否有应用可以处理这个Intent
                    val packageManager = context.packageManager
                    if (intent.resolveActivity(packageManager) != null) {
                        Log.d(TAG, "✓ 找到可以处理该URI的应用，启动...")
                        context.startActivity(intent)
                    } else {
                        Log.w(TAG, "❌ 没有应用可以处理该URI")
                        
                        // 友好的提示信息
                        val message = """
                            未安装高德地图
                            
                            您可以：
                            • 在应用商店搜索"高德地图"安装
                            • 或使用其他地图应用查看坐标：
                              ${latitude}, ${longitude}
                        """.trimIndent()
                        
                        Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                        
                        // 尝试打开应用商店（如果失败也不影响）
                        try {
                            val marketIntent = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=com.autonavi.minimap"))
                            if (marketIntent.resolveActivity(packageManager) != null) {
                                context.startActivity(marketIntent)
                            } else {
                                // 尝试浏览器打开
                                val webIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://mobile.amap.com/"))
                                if (webIntent.resolveActivity(packageManager) != null) {
                                    Log.d(TAG, "应用商店不可用，尝试用浏览器打开")
                                }
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "打开应用商店失败（这是正常的，可能在模拟器上）", e)
                            // 不再显示错误，已经通过Toast告知用户
                        }
                    }
                    
                } catch (e: Exception) {
                    Log.e(TAG, "打开高德地图失败", e)
                    Toast.makeText(context, "打开高德地图失败: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }

    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        webAppInterface = WebAppInterface(this)

        webView = WebView(this).apply {
            @Suppress("DEPRECATION")
            settings.apply {
                domStorageEnabled = true
                javaScriptEnabled = true
                blockNetworkImage = false
                allowFileAccess = true
                allowFileAccessFromFileURLs = true
                allowUniversalAccessFromFileURLs = true
            }

            addJavascriptInterface(webAppInterface, "Android")

            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    Log.d(TAG, "WebView页面加载完成")
                    isWebViewReady = true
                    
                    // 只在首次加载完成时处理Intent，避免重复
                    if (!hasProcessedIntent) {
                        hasProcessedIntent = true
                        handleIntent(intent)
                    }
                }
            }
            webChromeClient = WebChromeClient()
            loadUrl("file:///android_asset/main.html")
        }

        findViewById<LinearLayout>(R.id.main_container).addView(
            webView,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        Log.d(TAG, "收到新Intent: ${intent?.action}")
        setIntent(intent)
        
        // 如果WebView已准备好，立即处理；否则等待onPageFinished
        if (isWebViewReady) {
            handleIntent(intent)
        } else {
            Log.d(TAG, "WebView未准备好，等待加载完成")
            hasProcessedIntent = false  // 重置标志，让onPageFinished处理
        }
    }

    @Suppress("DEPRECATION")
    private fun handleIntent(intent: Intent?) {
        if (intent == null) return

        when (intent.action) {
            Intent.ACTION_SEND -> {
                if (intent.type?.startsWith("image/") == true) {
                    val imageUri = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
                    if (imageUri != null) {
                        Log.d(TAG, "【分享照片】URI: $imageUri")
                        handleImageUri(imageUri)
                    } else {
                        Log.e(TAG, "【分享照片】未获取到URI")
                    }
                }
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                if (intent.type?.startsWith("image/") == true) {
                    val imageUris = intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)
                    val imageUri = imageUris?.firstOrNull()
                    if (imageUri != null) {
                        Log.d(TAG, "【分享多张照片】处理第一张，URI: $imageUri")
                        handleImageUri(imageUri)
                    } else {
                        Log.e(TAG, "【分享多张照片】未获取到URI")
                    }
                }
            }
        }
    }

    private fun handleImageUri(uri: Uri) {
        Log.d(TAG, "")
        Log.d(TAG, "==================== 开始处理图片 ====================")
        Log.d(TAG, "URI: $uri")
        Log.d(TAG, "URI Scheme: ${uri.scheme}")
        Log.d(TAG, "URI Path: ${uri.path}")
        Log.d(TAG, "URI Authority: ${uri.authority}")
        
        // 确保WebView已准备好
        if (!isWebViewReady) {
            Log.w(TAG, "⚠️ WebView还未准备好，等待2秒后再试")
            webView.postDelayed({
                handleImageUri(uri)
            }, 2000)
            return
        }
        
        try {
            // 尝试多种方式读取EXIF信息
            val exif = getExifInterface(uri)
            
            if (exif == null) {
                Log.e(TAG, "❌ 所有方法都无法读取EXIF信息")
                runOnUiThread {
                    Toast.makeText(this, "无法读取照片信息\n请确保照片未被加密或损坏", Toast.LENGTH_LONG).show()
                }
                return
            }
            
            Log.d(TAG, "✓ 成功获取EXIF接口")
            
            // 读取GPS信息
            val latLong = FloatArray(2)
            @Suppress("DEPRECATION")
            val hasGPS = exif.getLatLong(latLong)
            
            Log.d(TAG, "GPS信息检查: $hasGPS")
            
            if (hasGPS) {
                val latitude = latLong[0].toDouble()
                val longitude = latLong[1].toDouble()
                
                Log.d(TAG, "✓ GPS坐标: [$latitude, $longitude]")
                
                // 读取其他信息
                val datetime = exif.getAttribute(ExifInterface.TAG_DATETIME) ?: "未知时间"
                val make = exif.getAttribute(ExifInterface.TAG_MAKE) ?: ""
                val model = exif.getAttribute(ExifInterface.TAG_MODEL) ?: ""
                
                Log.d(TAG, "拍摄时间: $datetime")
                Log.d(TAG, "设备信息: $make $model")
                
                val result = JSONObject().apply {
                    put("latitude", latitude)
                    put("longitude", longitude)
                    put("datetime", datetime)
                    put("device", if (make.isNotEmpty() && model.isNotEmpty()) "$make $model" else "未知设备")
                }
                
                Log.d(TAG, "✓ 准备显示位置信息")
                
                // 调用JavaScript显示位置
                runOnUiThread {
                    webView.evaluateJavascript(
                        "javascript:showLocation(${result})",
                        null
                    )
                    Log.d(TAG, "✓ 已调用JavaScript显示地图")
                }
            } else {
                Log.w(TAG, "⚠ 该照片的EXIF中没有GPS信息")
                
                // 尝试读取其他EXIF标签来验证EXIF是否完整
                val hasDateTime = exif.getAttribute(ExifInterface.TAG_DATETIME) != null
                val hasMake = exif.getAttribute(ExifInterface.TAG_MAKE) != null
                Log.d(TAG, "EXIF完整性检查 - 有时间: $hasDateTime, 有设备: $hasMake")
                
                runOnUiThread {
                    Toast.makeText(this, "该照片没有位置信息\n请使用拍摄时开启GPS的照片", Toast.LENGTH_LONG).show()
                }
            }
            Log.d(TAG, "==================== 处理完成 ====================")
        } catch (e: Exception) {
            e.printStackTrace()
            Log.e(TAG, "❌ 处理照片时发生异常", e)
            runOnUiThread {
                Toast.makeText(this, "读取照片信息失败: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }
    
    /**
     * 获取ExifInterface，尝试多种方式以兼容不同设备和分享场景
     */
    private fun getExifInterface(uri: Uri): ExifInterface? {
        Log.d(TAG, "URI scheme: ${uri.scheme}")
        
        // 方法1: 如果是file://，直接使用文件路径
        if (uri.scheme == "file") {
            uri.path?.let { path ->
                try {
                    Log.d(TAG, "尝试方法1: 直接文件路径")
                    return ExifInterface(path)
                } catch (e: Exception) {
                    Log.e(TAG, "方法1失败", e)
                }
            }
        }
        
        // 方法2: 尝试从ContentResolver获取真实路径
        if (uri.scheme == "content") {
            getRealPathFromURI(uri)?.let { path ->
                try {
                    Log.d(TAG, "尝试方法2: ContentResolver路径 - $path")
                    return ExifInterface(path)
                } catch (e: Exception) {
                    Log.e(TAG, "方法2失败", e)
                }
            }
        }
        
        // 方法3: 复制到临时文件再读取（最兼容的方式）
        try {
            Log.d(TAG, "尝试方法3: 临时文件")
            val inputStream = contentResolver.openInputStream(uri)
            if (inputStream != null) {
                val tempFile = File(cacheDir, "temp_image_${System.currentTimeMillis()}.jpg")
                FileOutputStream(tempFile).use { output ->
                    inputStream.copyTo(output)
                }
                inputStream.close()
                
                val exif = ExifInterface(tempFile.absolutePath)
                tempFile.delete() // 清理临时文件
                return exif
            }
        } catch (e: Exception) {
            Log.e(TAG, "方法3失败", e)
        }
        
        // 方法4: 直接从InputStream读取（某些情况下可能丢失EXIF）
        try {
            Log.d(TAG, "尝试方法4: InputStream")
            contentResolver.openInputStream(uri)?.use { stream ->
                return ExifInterface(stream)
            }
        } catch (e: Exception) {
            Log.e(TAG, "方法4失败", e)
        }
        
        return null
    }
    
    /**
     * 从Content URI获取真实文件路径
     */
    private fun getRealPathFromURI(uri: Uri): String? {
        var cursor: Cursor? = null
        try {
            // 尝试多种方式获取路径
            
            // 方式1: MediaStore.Images.Media.DATA
            val projection = arrayOf(MediaStore.Images.Media.DATA)
            cursor = contentResolver.query(uri, projection, null, null, null)
            cursor?.let {
                if (it.moveToFirst()) {
                    val columnIndex = it.getColumnIndexOrThrow(MediaStore.Images.Media.DATA)
                    val path = it.getString(columnIndex)
                    if (path != null && path.isNotEmpty()) {
                        Log.d(TAG, "通过DATA列获取到路径: $path")
                        return path
                    }
                }
            }
            cursor?.close()
            
            // 方式2: 如果是document URI，尝试解析
            if (uri.authority?.contains("documents") == true) {
                Log.d(TAG, "这是一个Document URI，尝试解析...")
                val docId = uri.lastPathSegment
                if (docId != null) {
                    // 解析document ID
                    val split = docId.split(":")
                    if (split.size >= 2) {
                        val type = split[0]
                        val id = split[1]
                        
                        Log.d(TAG, "Document类型: $type, ID: $id")
                        
                        // 根据类型构造查询URI
                        val contentUri = when (type) {
                            "image" -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                            "video" -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                            "audio" -> MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
                            else -> null
                        }
                        
                        if (contentUri != null) {
                            val selection = "${MediaStore.Images.Media._ID}=?"
                            val selectionArgs = arrayOf(id)
                            cursor = contentResolver.query(
                                contentUri,
                                arrayOf(MediaStore.Images.Media.DATA),
                                selection,
                                selectionArgs,
                                null
                            )
                            cursor?.let {
                                if (it.moveToFirst()) {
                                    val path = it.getString(0)
                                    Log.d(TAG, "通过Document解析获取到路径: $path")
                                    return path
                                }
                            }
                        }
                    }
                }
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "获取真实路径失败", e)
        } finally {
            cursor?.close()
        }
        return null
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            PERMISSION_REQUEST_CODE -> {
                if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    Toast.makeText(this, "权限已授予", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "需要存储权限才能读取照片", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
     }
}
