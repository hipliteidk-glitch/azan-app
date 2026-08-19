package com.example.azan

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.GeolocationPermissions
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {
    private lateinit var webView: WebView
    private lateinit var updateButton: Button
    private val CHANNEL_ID = "azan_web_channel"
    private val NOTIFICATION_ID = 2001
    private val LOCATION_PERMISSION_REQUEST = 10
    private val NOTIFICATION_PERMISSION_REQUEST = 11
    private var updateCheckInProgress = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        createNotificationChannel()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            WebView.setWebContentsDebuggingEnabled(true)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), NOTIFICATION_PERMISSION_REQUEST)
            }
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), LOCATION_PERMISSION_REQUEST)
        }

        webView = findViewById(R.id.webView)
        updateButton = findViewById(R.id.updateButton)

        webView.apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = true
            settings.allowContentAccess = true
            settings.setGeolocationEnabled(true)
            settings.setAllowUniversalAccessFromFileURLs(true)
            settings.setAllowFileAccessFromFileURLs(true)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            }

            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    view?.evaluateJavascript(
                        """
                        (function() {
                            var originalFetch = window.fetch;
                            window.fetch = function(url, options) {
                                return originalFetch(url, options).catch(function(e) {
                                    console.error('Fetch error:', e);
                                    if (typeof AndroidBridge !== 'undefined') {
                                        AndroidBridge.showToast('Network error: ' + e.message);
                                    }
                                    throw e;
                                });
                            };
                            console.log('Azan Notify fetch interceptor installed');
                        })();
                        """.trimIndent(),
                        null
                    )
                }
            }

            webChromeClient = object : WebChromeClient() {
                override fun onGeolocationPermissionsShowPrompt(origin: String?, callback: GeolocationPermissions.Callback?) {
                    if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                        callback?.invoke(origin, true, false)
                    } else {
                        callback?.invoke(origin, false, false)
                    }
                }

                override fun onPermissionRequest(request: PermissionRequest?) {
                    request?.deny()
                }

                override fun onConsoleMessage(consoleMessage: android.webkit.ConsoleMessage): Boolean {
                    Log.d("WebView", "JS Console: ${consoleMessage.message()}")
                    return super.onConsoleMessage(consoleMessage)
                }
            }

            loadUrl("file:///android_asset/azan-notify.html")
        }

        webView.addJavascriptInterface(
            object {
                @JavascriptInterface
                fun showNotification(title: String, body: String) {
                    if (ActivityCompat.checkSelfPermission(this@MainActivity, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
                        val builder = NotificationCompat.Builder(this@MainActivity, CHANNEL_ID)
                            .setSmallIcon(android.R.drawable.ic_dialog_info)
                            .setContentTitle(title)
                            .setContentText(body)
                            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                            .setAutoCancel(true)
                        NotificationManagerCompat.from(this@MainActivity).notify(NOTIFICATION_ID, builder.build())
                    } else {
                        runOnUiThread {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                ActivityCompat.requestPermissions(this@MainActivity, arrayOf(Manifest.permission.POST_NOTIFICATIONS), NOTIFICATION_PERMISSION_REQUEST)
                            }
                        }
                    }
                }

                @JavascriptInterface
                fun requestNotificationPermission() {
                    runOnUiThread {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                                ActivityCompat.requestPermissions(this@MainActivity, arrayOf(Manifest.permission.POST_NOTIFICATIONS), NOTIFICATION_PERMISSION_REQUEST)
                            }
                        }
                    }
                }

                @JavascriptInterface
                fun showToast(message: String) {
                    runOnUiThread {
                        Toast.makeText(this@MainActivity, message, Toast.LENGTH_LONG).show()
                    }
                }
            },
            "AndroidBridge"
        )

        updateButton.setOnClickListener {
            checkForUpdate()
        }

        checkForUpdateSilently()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "Azan Web Notifications"
            val descriptionText = "Notifications from Azan Notify web app"
            val importance = NotificationManager.IMPORTANCE_DEFAULT
            val channel = NotificationChannel(CHANNEL_ID, name, importance).apply {
                description = descriptionText
            }
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == LOCATION_PERMISSION_REQUEST) {
            if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                Toast.makeText(this, "Location permission granted. Reloading...", Toast.LENGTH_SHORT).show()
                webView.reload()
            } else {
                Toast.makeText(this, "Location permission denied. Some features may not work.", Toast.LENGTH_LONG).show()
            }
        }
        if (requestCode == NOTIFICATION_PERMISSION_REQUEST) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "Notification permission granted.", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Notification permission denied. You will not receive alerts.", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun checkForUpdate() {
        if (updateCheckInProgress) {
            Toast.makeText(this, "Check already in progress", Toast.LENGTH_SHORT).show()
            return
        }
        updateCheckInProgress = true
        updateButton.text = "Checking..."
        updateButton.isEnabled = false
        Toast.makeText(this, "Checking for updates...", Toast.LENGTH_SHORT).show()

        val client = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()
        // Use raw GitHub URL for version.json (always accessible)
        val request = Request.Builder()
            .url("https://raw.githubusercontent.com/hipliteidk-glitch/azan-app/main/version.json")
            .build()

        client.newCall(request).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: IOException) {
                runOnUiThread {
                    Toast.makeText(this@MainActivity, "Update check failed: ${e.message}", Toast.LENGTH_SHORT).show()
                    updateCheckInProgress = false
                    updateButton.text = "Check for Updates"
                    updateButton.isEnabled = true
                }
            }

            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                response.use {
                    if (!it.isSuccessful) {
                        runOnUiThread {
                            Toast.makeText(this@MainActivity, "Update check error: ${it.code}", Toast.LENGTH_SHORT).show()
                            updateCheckInProgress = false
                            updateButton.text = "Check for Updates"
                            updateButton.isEnabled = true
                        }
                        return
                    }
                    val body = it.body?.string()
                    if (body == null) {
                        runOnUiThread {
                            Toast.makeText(this@MainActivity, "Empty version response", Toast.LENGTH_SHORT).show()
                            updateCheckInProgress = false
                            updateButton.text = "Check for Updates"
                            updateButton.isEnabled = true
                        }
                        return
                    }
                    try {
                        val json = JSONObject(body)
                        val remoteVersion = json.getInt("version_code")
                        val downloadUrl = json.getString("download_url")
                        val currentVersion = packageManager.getPackageInfo(packageName, 0).versionCode

                        runOnUiThread {
                            if (remoteVersion > currentVersion) {
                                Toast.makeText(this@MainActivity, "Update available (v$remoteVersion). Downloading...", Toast.LENGTH_LONG).show()
                                updateButton.text = "Downloading..."
                                downloadAndInstallUpdate(downloadUrl)
                            } else {
                                Toast.makeText(this@MainActivity, "You have the latest version ($currentVersion).", Toast.LENGTH_SHORT).show()
                                updateCheckInProgress = false
                                updateButton.text = "Check for Updates"
                                updateButton.isEnabled = true
                            }
                        }
                    } catch (e: Exception) {
                        runOnUiThread {
                            Toast.makeText(this@MainActivity, "Failed to parse version: ${e.message}", Toast.LENGTH_SHORT).show()
                            updateCheckInProgress = false
                            updateButton.text = "Check for Updates"
                            updateButton.isEnabled = true
                        }
                    }
                }
            }
        })
    }

    private fun checkForUpdateSilently() {
        if (updateCheckInProgress) return
        updateCheckInProgress = true

        val client = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .build()
        val request = Request.Builder()
            .url("https://raw.githubusercontent.com/hipliteidk-glitch/azan-app/main/version.json")
            .build()

        client.newCall(request).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: IOException) {
                updateCheckInProgress = false
            }

            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                response.use {
                    if (!it.isSuccessful) {
                        updateCheckInProgress = false
                        return
                    }
                    val body = it.body?.string()
                    if (body == null) {
                        updateCheckInProgress = false
                        return
                    }
                    try {
                        val json = JSONObject(body)
                        val remoteVersion = json.getInt("version_code")
                        val currentVersion = packageManager.getPackageInfo(packageName, 0).versionCode

                        if (remoteVersion > currentVersion) {
                            runOnUiThread {
                                Toast.makeText(this@MainActivity, "Update available! Tap 'Check for Updates' to install.", Toast.LENGTH_LONG).show()
                                updateButton.text = "Update Available!"
                                updateButton.isEnabled = true
                            }
                        }
                        updateCheckInProgress = false
                    } catch (e: Exception) {
                        updateCheckInProgress = false
                    }
                }
            }
        })
    }

    private fun downloadAndInstallUpdate(url: String) {
        val client = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
        val request = Request.Builder().url(url).build()

        client.newCall(request).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: IOException) {
                runOnUiThread {
                    Toast.makeText(this@MainActivity, "Download failed: ${e.message}", Toast.LENGTH_SHORT).show()
                    updateCheckInProgress = false
                    updateButton.text = "Check for Updates"
                    updateButton.isEnabled = true
                }
            }

            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                response.use {
                    if (!it.isSuccessful) {
                        runOnUiThread {
                            Toast.makeText(this@MainActivity, "Download error: ${it.code}", Toast.LENGTH_SHORT).show()
                            updateCheckInProgress = false
                            updateButton.text = "Check for Updates"
                            updateButton.isEnabled = true
                        }
                        return
                    }
                    val body = it.body
                    if (body == null) {
                        runOnUiThread {
                            Toast.makeText(this@MainActivity, "Empty response body", Toast.LENGTH_SHORT).show()
                            updateCheckInProgress = false
                            updateButton.text = "Check for Updates"
                            updateButton.isEnabled = true
                        }
                        return
                    }
                    try {
                        val apkFile = File(getExternalFilesDir(null), "update.apk")
                        if (apkFile.exists()) apkFile.delete()
                        val fos = FileOutputStream(apkFile)
                        fos.write(body.bytes())
                        fos.close()

                        runOnUiThread {
                            Toast.makeText(this@MainActivity, "Download complete. Installing...", Toast.LENGTH_SHORT).show()
                            installApk(apkFile)
                        }
                    } catch (e: Exception) {
                        runOnUiThread {
                            Toast.makeText(this@MainActivity, "Install failed: ${e.message}", Toast.LENGTH_SHORT).show()
                            updateCheckInProgress = false
                            updateButton.text = "Check for Updates"
                            updateButton.isEnabled = true
                        }
                    }
                }
            }
        })
    }

    private fun installApk(apkFile: File) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val uri = FileProvider.getUriForFile(this, "${packageName}.fileprovider", apkFile)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(intent)
        } else {
            val uri = Uri.fromFile(apkFile)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
        }
        Handler(Looper.getMainLooper()).postDelayed({
            updateCheckInProgress = false
            updateButton.text = "Check for Updates"
            updateButton.isEnabled = true
        }, 5000)
    }
}
