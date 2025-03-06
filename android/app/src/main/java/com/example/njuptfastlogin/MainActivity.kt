package com.example.njuptfastlogin

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.njuptfastlogin.ui.theme.AppTheme
import kotlinx.coroutines.*
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import java.security.cert.X509Certificate
import androidx.compose.foundation.text.ClickableText
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Save
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import kotlin.coroutines.resume

class MainActivity : ComponentActivity() {
    // 日志标签
    private val TAG = "NjuptNetLogin"

    private val PREFS_NAME = "NetworkLoginPrefs"
    private val KEY_USERNAME = "username"
    private val KEY_PASSWORD = "password"
    private val KEY_ISP = "isp"
    private val KEY_SAVED = "saved"
    private val KEY_WIFI_NAME = "wifi_name"

    // 从C++代码中提取的服务器信息
    private val SERVER_IP = "10.10.244.11"
    private val SERVER_PORT = 801
    private val LOGIN_ENDPOINT = "/eportal/portal/login"
    private val CHECK_ENDPOINT = "/a79.htm"

    // 检查页面URL
    private val CHECK_PAGE_URL = "https://p.njupt.edu.cn/a79.htm"

    // WebView实例和JS结果
    private var webView: WebView? = null
    private var jsResult = ""

    // WiFi管理工具
    private lateinit var wifiUtils: WifiUtils

    // 运营商对应的WiFi名称
    private val ispToWifi = mapOf(
        "校园网" to "NJUPT",
        "移动" to "NJUPT-CMCC",
        "电信" to "NJUPT-CHINANET"
    )

    // 权限请求
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.entries.all { it.value }
        Log.d(TAG, "位置权限请求结果: $allGranted")

        if (!allGranted) {
            Toast.makeText(this, "需要位置权限来正确检测WiFi网络", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d(TAG, "应用启动")

        // 初始化WiFi工具
        wifiUtils = WifiUtils(this)

        // 程序启动立即申请权限
        requestWifiPermissions()

        // 添加信任所有证书的设置 - 仅用于校园网内部访问
        setupTrustAllCerts()

        // 初始化WebView
        initWebView()

        // 检查是否已保存配置
        val settings = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val saved = settings.getBoolean(KEY_SAVED, false)

        // 改进初始登录流程（在onCreate中）
        if (saved) {
            // 已有保存的数据
            val username = settings.getString(KEY_USERNAME, "") ?: ""
            val password = settings.getString(KEY_PASSWORD, "") ?: ""
            val isp = settings.getString(KEY_ISP, "校园网") ?: "校园网"

            Log.d(TAG, "检测到已保存配置，账号: $username, ISP: $isp")

            // 创建新线程执行登录流程
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    // 先处理WiFi连接
                    val wifiConnected = handleWifiConnection(isp)

                    // 即使WiFi连接失败也尝试登录，因为用户可能已经手动连接了
                    try {
                        // 检查是否通过WiFi连接 - 连接失败也尝试登录请求
                        if (!wifiUtils.isConnectedViaWifi()) {
                            Log.w(TAG, "未通过WiFi连接，但仍然尝试登录")
                        }

                        // 尝试登录
                        val (success, message) = sendLoginRequest(username, password, isp)

                        withContext(Dispatchers.Main) {
                            if (success) {
                                // 包含"成功"字样，登录成功
                                Toast.makeText(this@MainActivity, "登录成功\uD83D\uDE0B", Toast.LENGTH_SHORT).show()
                                Log.d(TAG, "登录成功: $message")
                                finish() // 完成后退出
                            } else {
                                // 没有"成功"字样，检查是否已经登录
                                checkLoginStatusWithWebView { isLoggedIn ->
                                    if (isLoggedIn) {
                                        Toast.makeText(this@MainActivity, "已经登录\uD83D\uDE0B", Toast.LENGTH_SHORT).show()
                                    } else {
                                        Toast.makeText(this@MainActivity, "登录失败", Toast.LENGTH_SHORT).show()
                                    }
                                    finish() // 完成后退出
                                }
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "登录过程异常: ${e.message}")
                        withContext(Dispatchers.Main) {
                            Toast.makeText(this@MainActivity, "登录失败: ${e.message}", Toast.LENGTH_SHORT).show()
                            finish()
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "WiFi连接过程异常: ${e.message}")
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@MainActivity, "连接WiFi失败: ${e.message}", Toast.LENGTH_SHORT).show()
                        finish()
                    }
                }
            }
        } else {
            // 首次启动，显示界面
            Log.d(TAG, "首次启动，显示登录界面")
            setContent {
                AppTheme {
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = MaterialTheme.colorScheme.background
                    ) {
                        LoginScreen()
                    }
                }
            }
        }
    }

    // 处理WiFi连接逻辑 - 优化后的纯监测和提示版本
    private suspend fun handleWifiConnection(isp: String): Boolean {
        val targetWifi = ispToWifi[isp] ?: return false
        Log.d(TAG, "目标WiFi网络: $targetWifi")

        // 检查当前WiFi状态
        var currentWifi = wifiUtils.getCurrentWifiSSID()

        // 如果当前已经连接正确WiFi，直接返回成功
        if (currentWifi == targetWifi) {
            Log.d(TAG, "当前已连接到正确WiFi: $targetWifi")
            return true
        }

        // 根据WiFi状态构建提示信息
        val wifiStatusMessage = if (!wifiUtils.isWifiEnabled())
            "请开启WiFi并连接到 $targetWifi"
        else
            "请连接到 $targetWifi"

        // 显示一次提示信息
        // 显示一次提示信息，位置在屏幕上方
        withContext(Dispatchers.Main) {
            val toast = Toast.makeText(
                this@MainActivity,
                wifiStatusMessage,
                Toast.LENGTH_LONG
            )
            // 设置Toast显示在屏幕上方
            toast.setGravity(android.view.Gravity.TOP or android.view.Gravity.CENTER_HORIZONTAL, 0, 10)
            toast.show()
        }

        // 在单独线程中打开WiFi设置，不阻塞主线程监测
        val settingsJob = CoroutineScope(Dispatchers.Main).launch {
            if (!wifiUtils.isWifiEnabled()) {
                wifiUtils.openWifiSettings()
            } else {
                wifiUtils.openNetworkSettings()
            }
        }

        // 主线程开始异步持续监控WiFi状态
        val connectionTimeout = 60000L // 60秒超时
        Log.d(TAG, "开始监控WiFi连接状态，等待正确WiFi连接...")

        val connected = withTimeoutOrNull(connectionTimeout) {
            // 持续检测WiFi状态直到连接正确或超时
            while (true) {
                currentWifi = wifiUtils.getCurrentWifiSSID()
                Log.d(TAG, "WiFi状态检测: 当前=$currentWifi, 目标=$targetWifi")

                if (currentWifi == targetWifi) {
                    // 找到正确WiFi连接
                    Log.d(TAG, "检测到正确WiFi连接: $targetWifi")
                    delay(1000) // 给网络稳定提供时间
                    return@withTimeoutOrNull true
                }

                delay(500) // 每500ms检查一次，更快响应
            }
        } ?: false

        // 取消设置界面线程
        settingsJob.cancel()

        // 根据连接结果返回
        if (connected as Boolean) {
            Log.d(TAG, "成功连接到目标WiFi: $targetWifi")
            return true
        } else {
            Log.d(TAG, "连接WiFi超时，未能连接到: $targetWifi")
            withContext(Dispatchers.Main) {
                Toast.makeText(
                    this@MainActivity,
                    "未能连接到网络，将尝试直接登录",
                    Toast.LENGTH_SHORT
                ).show()
            }
            return false
        }
    }

    // 简化权限请求方法
    private fun requestWifiPermissions() {
        val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,  // Android 10+需要精确位置权限获取SSID
                Manifest.permission.ACCESS_WIFI_STATE,
                Manifest.permission.CHANGE_WIFI_STATE,
                Manifest.permission.CHANGE_NETWORK_STATE
            )
        } else {
            arrayOf(
                Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.ACCESS_WIFI_STATE,
                Manifest.permission.CHANGE_WIFI_STATE,
                Manifest.permission.CHANGE_NETWORK_STATE
            )
        }

        val permissionsToRequest = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }.toTypedArray()

        if (permissionsToRequest.isNotEmpty()) {
            requestPermissionLauncher.launch(permissionsToRequest)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun initWebView() {
        webView = WebView(applicationContext).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.loadsImagesAutomatically = false

            // 添加JS接口
            addJavascriptInterface(
                object {
                    @JavascriptInterface
                    fun processPageKind(result: String) {
                        Log.d(TAG, "JavaScript返回page.kind: $result")
                        jsResult = result
                    }
                },
                "Android"
            )
        }
    }

    private fun checkLoginStatusWithWebView(callback: (Boolean) -> Unit) {
        // 使用WebView执行JavaScript检查登录状态
        webView?.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                // 页面加载完成后，执行JavaScript获取page.kind
                view.evaluateJavascript(
                    "(function() { " +
                            "try { " +
                            "   if(typeof page !== 'undefined' && page.kind) { " +
                            "      return page.kind; " +
                            "   } else { " +
                            "      return 'undefined'; " +
                            "   } " +
                            "} catch(e) { " +
                            "   return 'error: ' + e.message; " +
                            "} " +
                            "})();",
                    { result ->
                        Log.d(TAG, "JavaScript执行结果: $result")
                        val cleanResult = result.replace("\"", "") // 去掉JavaScript返回的引号
                        val isLoggedIn = cleanResult.contains("mobile_") || cleanResult.contains("pc_")
                        Log.d(TAG, "是否已登录: $isLoggedIn (page.kind=$cleanResult)")
                        callback(isLoggedIn)
                    }
                )
            }
        }

        // 加载检查页面
        webView?.loadUrl(CHECK_PAGE_URL)
    }

    // 设置信任所有证书 - 仅用于校园网应用
    private fun setupTrustAllCerts() {
        try {
            val trustAllCerts = arrayOf<TrustManager>(object : X509TrustManager {
                override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
                override fun checkClientTrusted(certs: Array<X509Certificate>, authType: String) {}
                override fun checkServerTrusted(certs: Array<X509Certificate>, authType: String) {}
            })

            val sc = SSLContext.getInstance("TLS")
            sc.init(null, trustAllCerts, java.security.SecureRandom())
            HttpsURLConnection.setDefaultSSLSocketFactory(sc.socketFactory)
            HttpsURLConnection.setDefaultHostnameVerifier { _, _ -> true }

            Log.d(TAG, "已配置信任所有证书")
        } catch (e: Exception) {
            Log.e(TAG, "配置信任所有证书失败", e)
        }
    }

    // 安全地读取输入流，使用指定编码
    private fun readStreamSafely(inputStream: InputStream?, charset: String): String {
        if (inputStream == null) return ""

        return try {
            val reader = BufferedReader(InputStreamReader(inputStream, charset))
            val response = StringBuilder()
            var line: String?

            while (reader.readLine().also { line = it } != null) {
                response.append(line).append("\n")
            }
            reader.close()
            response.toString()
        } catch (e: Exception) {
            Log.e(TAG, "读取数据流失败: ${e.message}")
            ""
        }
    }

    @Composable
    fun LoginScreen() {
        val context = LocalContext.current
        val coroutineScope = rememberCoroutineScope()

        var username by remember { mutableStateOf("") }
        var password by remember { mutableStateOf("") }
        var selectedIspIndex by remember { mutableStateOf(0) }
        val ispOptions = listOf("校园网", "移动", "电信")

        var isLoading by remember { mutableStateOf(false) }
        var showLoginButton by remember { mutableStateOf(true) }

        Scaffold(
            containerColor = MaterialTheme.colorScheme.surface
        ) { paddingValues ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // 页面标题
                    AnimatedVisibility(
                        visible = true,
                        enter = fadeIn() + slideInVertically(
                            initialOffsetY = { -50 },
                            animationSpec = spring(
                                dampingRatio = Spring.DampingRatioLowBouncy,
                                stiffness = Spring.StiffnessLow
                            )
                        )
                    ) {
                        Text(
                            text = "NJUPT Fast Login",
                            style = MaterialTheme.typography.headlineMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(vertical = 24.dp)
                        )
                    }

                    // 登录卡片
                    ElevatedCard(
                        modifier = Modifier
                            .fillMaxWidth()
                            .shadow(
                                elevation = 4.dp,
                                shape = RoundedCornerShape(16.dp)
                            ),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.elevatedCardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                        )
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            // 账号输入框
                            LoginTextField(
                                value = username,
                                onValueChange = { username = it },
                                label = "账号",
                                placeholder = "请输入校园网账号",
                                leadingIcon = Icons.Filled.Person
                            )

                            // 密码输入框
                            LoginTextField(
                                value = password,
                                onValueChange = { password = it },
                                label = "密码",
                                placeholder = "请输入密码",
                                leadingIcon = Icons.Filled.Lock,
                                isPassword = true
                            )

                            // 运营商选择
                            Text(
                                text = "运营商类型",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 8.dp, bottom = 4.dp)
                            )

                            // 运营商选择器
                            SegmentedButtons(
                                options = ispOptions,
                                selectedIndex = selectedIspIndex,
                                onSelectionChanged = { selectedIspIndex = it }
                            )
                        }
                    }

                    Spacer(modifier = Modifier.weight(1f))
                    // 添加帮助信息卡片 - 在登录卡片和按钮之间

                    // 添加帮助信息卡片 - 在登录卡片和按钮之间
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 16.dp),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.8f)
                        )
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 12.dp), // 减小内边距使内容更紧凑
                            verticalArrangement = Arrangement.spacedBy(8.dp) // 减小垂直间距
                        ) {
                            // 使用说明部分标题
                            Text(
                                text = "使用说明",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )

                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(start = 8.dp)
                            ) {
                                Icon(
                                    Icons.Filled.Save,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp), // 略微减小图标尺寸
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(modifier = Modifier.width(6.dp)) // 减小间距
                                Text(
                                    "首次启动会保存账户密码",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }

                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(start = 8.dp)
                            ) {
                                Icon(
                                    Icons.Filled.Refresh,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    "再次启动不会启动界面会直接登录",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }

                            // 重置部分
                            Text(
                                text = "重置",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 2.dp) // 减小顶部间距
                            )

                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(start = 8.dp)
                            ) {
                                Icon(
                                    Icons.Filled.RestartAlt,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    "在应用设置中清除该应用数据可以重新进入该界面",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }

                            // 关于部分
                            Text(
                                text = "关于",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 2.dp)
                            )

                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(start = 8.dp)
                            ) {
                                Icon(
                                    Icons.Filled.Person,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(modifier = Modifier.width(6.dp))

                                // 使用缩短的链接文本
                                val annotatedString = buildAnnotatedString {
                                    append("@Lux-QAQ 制作, github: ")

                                    pushStringAnnotation(
                                        tag = "URL",
                                        annotation = "https://github.com/lux-QAQ"
                                    )
                                    withStyle(
                                        style = SpanStyle(
                                            color = MaterialTheme.colorScheme.primary,
                                            textDecoration = TextDecoration.Underline
                                        )
                                    ) {
                                        append("lux-QAQ")  // 缩短显示的链接文本
                                    }
                                    pop()
                                }

                                ClickableText(
                                    text = annotatedString,
                                    style = MaterialTheme.typography.bodyMedium.copy(
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    ),
                                    onClick = { offset ->
                                        annotatedString.getStringAnnotations(tag = "URL", start = offset, end = offset)
                                            .firstOrNull()?.let { annotation ->
                                                val uri = Uri.parse(annotation.item)
                                                val intent = Intent(Intent.ACTION_VIEW, uri)
                                                context.startActivity(intent)
                                            }
                                    }
                                )
                            }
                        }
                    }

                    // 增加固定间距，确保卡片和按钮之间有足够的空间
                    Spacer(modifier = Modifier.height(24.dp))

                    // 保留弹性间距，但可能会被固定间距覆盖如果屏幕太小
                    Spacer(modifier = Modifier.weight(0.5f)) // 减小weight值
                    // 登录按钮或加载指示器
                    AnimatedVisibility(
                        visible = showLoginButton,
                        exit = fadeOut()
                    ) {
                        Button(
                            onClick = {
                                if (username.isBlank() || password.isBlank()) {
                                    Toast.makeText(context, "账户和密码不能为空", Toast.LENGTH_SHORT).show()
                                    return@Button
                                }

                                Log.d(TAG, "用户点击登录按钮，账号: $username, ISP: ${ispOptions[selectedIspIndex]}")

                                isLoading = true
                                showLoginButton = false

                                // 保存数据
                                val editor = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
                                editor.putString(KEY_USERNAME, username)
                                editor.putString(KEY_PASSWORD, password)
                                editor.putString(KEY_ISP, ispOptions[selectedIspIndex])
                                editor.putString(KEY_WIFI_NAME, ispToWifi[ispOptions[selectedIspIndex]])
                                editor.putBoolean(KEY_SAVED, true)
                                editor.apply()
                                Log.d(TAG, "配置已保存到本地存储")

                                // 立即尝试登录
                                coroutineScope.launch {
                                    try {
                                        // 先处理WiFi连接
                                        val wifiConnected = handleWifiConnection(ispOptions[selectedIspIndex])

                                        // 检查是否通过WiFi连接
                                        if (!wifiUtils.isConnectedViaWifi()) {
                                            Toast.makeText(context, "请确保已连接WiFi", Toast.LENGTH_LONG).show()
                                            isLoading = false
                                            showLoginButton = true
                                            return@launch
                                        }

                                        // 然后尝试登录
                                        val (success, message) = withContext(Dispatchers.IO) {
                                            sendLoginRequest(username, password, ispOptions[selectedIspIndex])
                                        }

                                        if (success) {
                                            // 包含"成功"字样，登录成功
                                            Toast.makeText(context, "登录成功\uD83D\uDE0B", Toast.LENGTH_SHORT).show()
                                            Log.d(TAG, "登录成功: $message")
                                            delay(800)  // 短暂延迟以显示结果
                                            finish()
                                        } else {
                                            // 没有"成功"字样，检查是否已经登录
                                            withContext(Dispatchers.Main) {
                                                checkLoginStatusWithWebView { isLoggedIn ->
                                                    if (isLoggedIn) {
                                                        Toast.makeText(context, "已经登录\uD83D\uDE0B", Toast.LENGTH_SHORT).show()
                                                    } else {
                                                        Toast.makeText(context, "登录错误", Toast.LENGTH_SHORT).show()
                                                    }
                                                    finish() // 完成后退出
                                                }
                                            }
                                        }
                                    } catch (e: Exception) {
                                        Log.e(TAG, "登录过程异常: ${e.message}")
                                        Toast.makeText(context, "登录失败: ${e.message}", Toast.LENGTH_SHORT).show()

                                        isLoading = false
                                        showLoginButton = true
                                    }
                                }
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(56.dp),
                            shape = RoundedCornerShape(28.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primaryContainer,
                                contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        ) {
                            Text(
                                "登录",
                                style = MaterialTheme.typography.titleMedium
                            )
                        }
                    }

                    // 加载指示器
                    AnimatedVisibility(
                        visible = isLoading,
                        enter = fadeIn()
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier
                                .size(56.dp)
                                .padding(8.dp),
                            color = MaterialTheme.colorScheme.primary
                        )
                    }

                    // 底部空白
                    Spacer(modifier = Modifier.height(24.dp))
                }
            }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    fun LoginTextField(
        value: String,
        onValueChange: (String) -> Unit,
        label: String,
        placeholder: String,
        leadingIcon: ImageVector,
        isPassword: Boolean = false
    ) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            label = { Text(label) },
            placeholder = { Text(placeholder) },
            leadingIcon = { Icon(leadingIcon, contentDescription = label) },
            singleLine = true,
            visualTransformation = if (isPassword) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
            keyboardOptions = if (isPassword)
                KeyboardOptions(keyboardType = KeyboardType.Password)
            else
                KeyboardOptions.Default,
            shape = RoundedCornerShape(12.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = MaterialTheme.colorScheme.primary,
                unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                focusedLabelColor = MaterialTheme.colorScheme.primary,
                unfocusedLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
            ),
            modifier = Modifier.fillMaxWidth()
        )
    }

    @Composable
    fun SegmentedButtons(
        options: List<String>,
        selectedIndex: Int,
        onSelectionChanged: (Int) -> Unit
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            options.forEachIndexed { index, option ->
                val isSelected = index == selectedIndex
                val containerColor = if (isSelected)
                    MaterialTheme.colorScheme.primaryContainer
                else
                    MaterialTheme.colorScheme.surfaceVariant
                val contentColor = if (isSelected)
                    MaterialTheme.colorScheme.onPrimaryContainer
                else
                    MaterialTheme.colorScheme.onSurfaceVariant

                FilledTonalButton(
                    onClick = { onSelectionChanged(index) },
                    shape = when (index) {
                        0 -> RoundedCornerShape(
                            topStart = 20.dp, bottomStart = 20.dp,
                            topEnd = 0.dp, bottomEnd = 0.dp
                        )
                        options.lastIndex -> RoundedCornerShape(
                            topStart = 0.dp, bottomStart = 0.dp,
                            topEnd = 20.dp, bottomEnd = 20.dp
                        )
                        else -> RoundedCornerShape(0.dp)
                    },
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = containerColor,
                        contentColor = contentColor
                    ),
                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 8.dp),  // 减小内边距
                    modifier = Modifier.weight(1f)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        if (isSelected) {
                            Icon(
                                Icons.Filled.Check,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(2.dp))  // 减小间距
                        }
                        Text(
                            text = option,
                            textAlign = TextAlign.Center,
                            maxLines = 1,  // 限制最大行数为1
                            overflow = TextOverflow.Visible,  // 文本溢出策略
                            fontSize = 13.sp,  // 稍微减小字体大小
                            modifier = Modifier.alignByBaseline()  // 基线对齐
                        )
                    }
                }
            }
        }
    }

    private fun getFormattedAccount(username: String, isp: String): String {
        return when (isp) {
            "移动" -> "$username@cmcc"
            "电信" -> "$username@njxy"
            else -> username // 校园网不需要后缀
        }
    }

    // 发送登录请求并返回是否成功和响应内容
    private fun sendLoginRequest(username: String, password: String, isp: String): Pair<Boolean, String> {
        // 确保通过WiFi连接
        if (!wifiUtils.isConnectedViaWifi()) {
            Log.e(TAG, "登录请求必须通过WiFi发送")
            return Pair(false, "请确保已连接到WiFi网络")
        }

        val formattedAccount = getFormattedAccount(username, isp)
        var connection: HttpURLConnection? = null



        try {
            // 构建完整请求URL
            val requestUrl = "http://$SERVER_IP:$SERVER_PORT$LOGIN_ENDPOINT?login_method=1&user_account=,0,$formattedAccount&user_password=$password"
            Log.d(TAG, "请求URL: $requestUrl")

            val url = URL(requestUrl)
            connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 5000
            connection.readTimeout = 5000

            // 添加请求头
            connection.setRequestProperty("Host", "$SERVER_IP:$SERVER_PORT")
            connection.setRequestProperty("Connection", "close")
            connection.setRequestProperty("Accept", "*/*")

            // 记录所有请求头
            Log.d(TAG, "请求头:")
            connection.requestProperties.forEach { (key, value) ->
                Log.d(TAG, "  $key: $value")
            }

            // 发送请求并记录响应
            val responseCode = connection.responseCode
            Log.d(TAG, "响应状态码: $responseCode")

            // 尝试读取响应内容
            if (responseCode == HttpURLConnection.HTTP_OK) {
                // 尝试使用GB2312编码读取响应
                var responseContent = readStreamSafely(connection.inputStream, "UTF-8")

                // 如果GB2312读取失败，尝试使用UTF-8
                if (responseContent.isEmpty() || responseContent.contains("�")) {
                    connection.disconnect()

                    // 重新连接
                    val newConnection = URL(requestUrl).openConnection() as HttpURLConnection
                    newConnection.requestMethod = "GET"
                    newConnection.connectTimeout = 5000
                    newConnection.readTimeout = 5000
                    newConnection.setRequestProperty("Host", "$SERVER_IP:$SERVER_PORT")
                    newConnection.setRequestProperty("Connection", "close")
                    newConnection.setRequestProperty("Accept", "*/*")

                    val newResponseCode = newConnection.responseCode
                    if (newResponseCode == HttpURLConnection.HTTP_OK) {
                        responseContent = readStreamSafely(newConnection.inputStream, "UTF-8")
                    }
                    newConnection.disconnect()
                }

                // 记录响应内容摘要
                if (responseContent.isNotEmpty()) {
                    val contentSummary = if (responseContent.length > 500)
                        responseContent.substring(0, 500) + "..."
                    else
                        responseContent
                    Log.d(TAG, "响应内容摘要: $contentSummary")

                    // 检查登录是否成功的关键词 - 检查"成功"字样
                    val success = responseContent.contains("成功") ||
                            responseContent.contains("success")

                    Log.d(TAG, "是否登录成功: $success")
                    return Pair(success, responseContent)
                } else {
                    Log.d(TAG, "响应内容为空")
                    return Pair(false, "响应内容为空")
                }
            } else {
                Log.d(TAG, "响应失败: $responseCode")
                return Pair(false, "响应失败: $responseCode")
            }

        } catch (e: Exception) {
            Log.e(TAG, "登录请求异常", e)
            Log.e(TAG, "异常类型: ${e.javaClass.simpleName}, 消息: ${e.message}")
            e.printStackTrace()
            return Pair(false, "异常: ${e.message}")
        } finally {
            connection?.disconnect()
        }
    }


    override fun onDestroy() {
        super.onDestroy()
        webView?.destroy()
        webView = null
    }
}