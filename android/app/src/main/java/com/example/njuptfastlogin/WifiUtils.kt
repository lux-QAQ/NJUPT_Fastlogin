package com.example.njuptfastlogin

import android.Manifest
import android.app.Activity
import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Looper
import android.provider.Settings
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.resume






class WifiUtils(private val context: Context) {
    private val TAG = "NjuptWifi"
    private val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    // 简化权限检查 - 只检查关键WiFi权限
    fun hasRequiredPermissions(): Boolean {
        val locationPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ActivityCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            ActivityCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        }

        val wifiPermissions = ActivityCompat.checkSelfPermission(
            context,
            Manifest.permission.CHANGE_WIFI_STATE
        ) == PackageManager.PERMISSION_GRANTED &&
                ActivityCompat.checkSelfPermission(
                    context,
                    Manifest.permission.ACCESS_WIFI_STATE
                ) == PackageManager.PERMISSION_GRANTED

        return locationPermission && wifiPermissions
    }

    // 检查WiFi是否开启
    fun isWifiEnabled(): Boolean {
        return wifiManager.isWifiEnabled
    }

    // 开启WiFi
    fun enableWifi(): Boolean {
        if (!wifiManager.isWifiEnabled) {
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val intent = Intent(Settings.Panel.ACTION_WIFI)
                intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK

                context.startActivity(intent)
                false
            } else {
                wifiManager.isWifiEnabled = true
                true
            }
        }
        return true
    }

    // 使用WifiManager获取SSID
    fun getCurrentSsid(context: Context): String? {
        val wifiManager = context.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val wifiInfo = wifiManager.connectionInfo
        return wifiInfo.ssid
    }

    // 使用LocationManager请求位置权限
    fun requestLocationPermission(activity: Activity?) {
        if (ContextCompat.checkSelfPermission(activity!!, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                activity,
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION),
                1
            )
        }
    }
    fun isGPSEnabled(context: Context): Boolean {
        val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        return locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
    }
    fun openGPSSettings(activity: Activity) {
        if (!isGPSEnabled(activity)) {
            val intent = Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
            activity.startActivityForResult(intent, 1)
        }
    }
    fun enableGPS(context: Context) {
        val contentResolver = context.contentResolver
        Settings.Secure.putInt(
            contentResolver,
            Settings.Secure.LOCATION_MODE,
            Settings.Secure.LOCATION_MODE_HIGH_ACCURACY
        )
    }

    // 获取当前连接的WiFi名称
    // 获取当前连接的WiFi名称 - 增强GPS处理版
    // 获取当前连接的WiFi名称 - 简化GPS处理版本
// 修改 getCurrentWifiSSID 函数
    fun getCurrentWifiSSID(activity: Activity? = null): String? {
        // 检查基本WiFi权限
        if (!hasRequiredPermissions()) {
            Log.w(TAG, "获取SSID失败: 缺少必要权限")
            return null
        }

        // 检查GPS状态
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && !isGPSEnabled(context)) {
            Log.w(TAG, "获取SSID失败: GPS未开启")
            return null
        }

        // 确保我们在主线程调用，这有助于确保前台状态
        if (Thread.currentThread() != Looper.getMainLooper().thread && activity != null) {
            // 如果不在主线程且有Activity，尝试在主线程获取
            val result = CompletableDeferred<String?>()
            activity.runOnUiThread {
                result.complete(getCurrentWifiSSIDInternal())
            }
            return try {
                runBlocking { result.await() }
            } catch (e: Exception) {
                Log.e(TAG, "在主线程获取SSID失败: ${e.message}")
                null
            }
        }

        return getCurrentWifiSSIDInternal()
    }

    // 分离内部实现以便于重用
    private fun getCurrentWifiSSIDInternal(): String? {
        try {
            @Suppress("DEPRECATION")
            val wifiInfo = wifiManager.connectionInfo
            var ssid = wifiInfo.ssid

            if (ssid.startsWith("\"") && ssid.endsWith("\"")) {
                ssid = ssid.substring(1, ssid.length - 1)
            }

            // 详细日志记录为调试目的
            Log.d(TAG, "原始SSID: '$ssid', 是否连接: ${wifiInfo.networkId != -1}")

            // 检查是否获取到有效SSID
            if (ssid == "<unknown ssid>" || ssid.isEmpty()) {
                Log.w(TAG, "无法获取SSID，在Android 10+上需要开启GPS且应用具有前台状态")
                return null
            }

            return ssid
        } catch (e: Exception) {
            Log.e(TAG, "获取SSID时发生异常: ${e.message}")
            return null
        }
    }
    // 检查是否通过WiFi连接
    fun isConnectedViaWifi(): Boolean {
        val network = connectivityManager.activeNetwork
        val capabilities = connectivityManager.getNetworkCapabilities(network)
        return capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
    }

    // 持续监测WiFi连接状态（替代原来的自动连接方法）
    suspend fun waitForCorrectWifi(targetSsid: String, timeoutSeconds: Int = 60): Boolean = suspendCancellableCoroutine { continuation ->
        Log.d(TAG, "等待连接到目标WiFi: $targetSsid")

        // 直接检查当前连接状态
        val currentSsid = getCurrentWifiSSID()
        if (currentSsid == targetSsid) {
            Log.d(TAG, "已连接到目标WiFi: $targetSsid")
            continuation.resume(true)
            return@suspendCancellableCoroutine
        }

        // 否则启动监听任务
        val monitorJob = CoroutineScope(Dispatchers.IO).launch {
            var counter = 0

            while (isActive && counter < timeoutSeconds) {
                val ssid = getCurrentWifiSSID()
                Log.d(TAG, "当前WiFi: $ssid, 目标WiFi: $targetSsid")

                if (ssid == targetSsid) {
                    Log.d(TAG, "检测到正确的WiFi连接: $targetSsid")
                    // 给网络一点时间稳定
                    delay(1000)

                    if (continuation.isActive) {
                        continuation.resume(true)
                    }
                    return@launch
                }

                delay(1000) // 每秒检查一次
                counter++
            }

            // 超时
            Log.d(TAG, "等待WiFi连接超时")
            if (continuation.isActive) {
                continuation.resume(false)
            }
        }

        // 取消时清理资源
        continuation.invokeOnCancellation {
            monitorJob.cancel()
        }
    }

    // 打开WiFi设置面板（不主动修改WiFi状态）
    fun openWifiSettings() {
        val intent = Intent(Settings.Panel.ACTION_WIFI)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
        context.startActivity(intent)
        Log.d(TAG, "已打开WiFi设置面板")
    }

    // 打开网络连接面板（用于 Android 10 以上系统）
    fun openNetworkSettings() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                val intent = Intent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY)
                intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                context.startActivity(intent)
                Log.d(TAG, "已打开网络设置面板")
            } catch (e: Exception) {
                Log.e(TAG, "无法打开网络设置面板: ${e.message}")
                // 回退到WiFi设置
                openWifiSettings()
            }
        } else {
            openWifiSettings()
        }
    }


    // 添加一个无参数版本的网络可用性检测方法，使用当前活动网络
    fun isNetworkUsable(): Boolean {
        try {
            val network = connectivityManager.activeNetwork ?: return false
            return isNetworkUsable(network)
        } catch (e: Exception) {
            Log.e(TAG, "检查当前网络可用性失败: ${e.message}")
            return false
        }
    }
    fun isNetworkUsable(network: Network): Boolean {
        try {
            val capabilities = connectivityManager.getNetworkCapabilities(network)
            if (capabilities != null) {
                val hasInternet = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                val isValidated = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)

                Log.d(TAG, "网络状态: 互联网=$hasInternet, 已验证=$isValidated")

                val testConnection = try {
                    val url = URL("http://10.10.244.11")
                    val conn = network.openConnection(url) as HttpURLConnection
                    conn.connectTimeout = 3000
                    conn.readTimeout = 3000
                    conn.connect()
                    val responseCode = conn.responseCode
                    conn.disconnect()

                    Log.d(TAG, "连接测试响应码: $responseCode")
                    responseCode in 200..599
                } catch (e: Exception) {
                    Log.e(TAG, "连接测试失败: ${e.message}")
                    false
                }

                return hasInternet && (isValidated || testConnection)
            }
            return false
        } catch (e: Exception) {
            Log.e(TAG, "检查网络可用性失败: ${e.message}")
            return false
        }
    }
}