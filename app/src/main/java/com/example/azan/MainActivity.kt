package com.example.azan

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.webkit.GeolocationPermissions
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {
    private lateinit var webView: WebView
    private val CHANNEL_ID = "azan_web_channel"
    private val NOTIFICATION_ID = 2001
    private val LOCATION_PERMISSION_REQUEST = 10
    private val NOTIFICATION_PERMISSION_REQUEST = 11

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        createNotificationChannel()

        // Request notification permission for Android 13+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), NOTIFICATION_PERMISSION_REQUEST)
            }
        }

        // Request location permission if not granted
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), LOCATION_PERMISSION_REQUEST)
        }

        webView = findViewById(R.id.webView)
        webView.apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = true
            settings.allowContentAccess = true
            settings.setGeolocationEnabled(true)

            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    // Inject JavaScript bridge to override Notification API
                    view?.evaluateJavascript(
                        """
                        (function() {
                            // Override Notification constructor
                            var originalNotification = window.Notification;
                            window.Notification = function(title, options) {
                                if (typeof AndroidBridge !== 'undefined') {
                                    var body = (options && options.body) ? options.body : '';
                                    AndroidBridge.showNotification(title, body);
                                }
                                // Optionally call original if you want to keep browser behavior
                            };
                            // Override requestPermission to grant automatically if we have permission
                            window.Notification.requestPermission = function(callback) {
                                // Ask Android side for permission
                                if (typeof AndroidBridge !== 'undefined') {
                                    AndroidBridge.requestNotificationPermission();
                                }
                                // For simplicity, we'll just grant
                                if (typeof callback === 'function') callback('granted');
                                return Promise.resolve('granted');
                            };
                            console.log('Azan Notify bridge injected');
                        })();
                        """.trimIndent(),
                        null
                    )
                }
            }

            webChromeClient = object : WebChromeClient() {
                override fun onGeolocationPermissionsShowPrompt(origin: String?, callback: GeolocationPermissions.Callback?) {
                    // Grant geolocation if we have permission
                    if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                        callback?.invoke(origin, true, false)
                    } else {
                        callback?.invoke(origin, false, false)
                    }
                }

                override fun onPermissionRequest(request: PermissionRequest?) {
                    // Handle geolocation and other permissions
                    val resources = request?.resources
                    if (resources != null) {
                        val granted = mutableListOf<String>()
                        for (resource in resources) {
                            when (resource) {
                                PermissionRequest.RESOURCE_GEOLOCATION -> {
                                    if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                                        granted.add(resource)
                                    }
                                }
                                else -> {
                                    // For other permissions, deny for simplicity
                                }
                            }
                        }
                        if (granted.isNotEmpty()) {
                            request.grant(granted.toTypedArray())
                        } else {
                            request.deny()
                        }
                    }
                }
            }

            // Load the HTML from assets
            loadUrl("file:///android_asset/azan-notify.html")
        }

        // Add JavaScript interface for notification bridge
        webView.addJavascriptInterface(
            object {
                @JavascriptInterface
                fun showNotification(title: String, body: String) {
                    // Show Android notification
                    if (ActivityCompat.checkSelfPermission(this@MainActivity, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
                        val builder = NotificationCompat.Builder(this@MainActivity, CHANNEL_ID)
                            .setSmallIcon(android.R.drawable.ic_dialog_info)
                            .setContentTitle(title)
                            .setContentText(body)
                            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                            .setAutoCancel(true)
                        NotificationManagerCompat.from(this@MainActivity).notify(NOTIFICATION_ID, builder.build())
                    } else {
                        // Request permission if not granted
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
            },
            "AndroidBridge"
        )
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
}
