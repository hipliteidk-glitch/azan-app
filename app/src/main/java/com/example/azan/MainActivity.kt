package com.example.azan

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.FileProvider
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.Query
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.Locale
import java.util.concurrent.TimeUnit

data class Timings(val Fajr: String, val Dhuhr: String, val Asr: String, val Maghrib: String, val Isha: String)
data class Data(val timings: Timings)
data class ApiResponse(val data: Data)

interface ApiService {
    @GET("v1/timingsByCity")
    fun getTimings(
        @Query("city") city: String,
        @Query("country") country: String,
        @Query("method") method: Int
    ): Call<ApiResponse>
}

class MainActivity : AppCompatActivity() {
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private val CHANNEL_ID = "azan_channel"
    private val NOTIFICATION_ID = 1001
    private val LOCATION_PERMISSION_REQUEST = 2
    private val NOTIFICATION_PERMISSION_REQUEST = 1
    private var updateCheckInProgress = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        createNotificationChannel()
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        val textView = findViewById<TextView>(R.id.textView)
        val testButton = findViewById<Button>(R.id.testButton)
        val refreshButton = findViewById<Button>(R.id.refreshButton)
        val updateButton = findViewById<Button>(R.id.updateButton)

        // Request notification permission for Android 13+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), NOTIFICATION_PERMISSION_REQUEST)
            }
        }

        testButton.setOnClickListener {
            sendNotification("Test Notification", "Azan app test notification!")
            Toast.makeText(this, "Test notification sent", Toast.LENGTH_SHORT).show()
        }

        refreshButton.setOnClickListener {
            fetchLocationAndPrayerTimes(textView)
        }

        updateButton.setOnClickListener {
            checkForUpdate(textView)
        }

        // Initial fetch
        textView.text = "Fetching location and prayer times..."
        fetchLocationAndPrayerTimes(textView)

        // Auto-check for updates on launch
        checkForUpdateSilently(textView)
    }

    private fun fetchLocationAndPrayerTimes(textView: TextView) {
        Toast.makeText(this, "Getting location...", Toast.LENGTH_SHORT).show()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED
                && checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
                    LOCATION_PERMISSION_REQUEST
                )
                textView.text = "Location permission required. Please grant and refresh."
                return
            }
        }

        fusedLocationClient.lastLocation.addOnSuccessListener { location: Location? ->
            if (location != null) {
                getCityAndCountry(location, textView)
            } else {
                textView.text = "Location unavailable, using Mecca as default."
                fetchPrayerTimes("Makkah", "Saudi Arabia", textView)
            }
        }.addOnFailureListener {
            textView.text = "Failed to get location: ${it.message}. Using Mecca."
            fetchPrayerTimes("Makkah", "Saudi Arabia", textView)
        }
    }

    private fun getCityAndCountry(location: Location, textView: TextView) {
        val geocoder = Geocoder(this, Locale.getDefault())
        try {
            val addresses = geocoder.getFromLocation(location.latitude, location.longitude, 1)
            if (addresses?.isNotEmpty() == true) {
                val address = addresses[0]
                val city = address.locality ?: address.subAdminArea ?: "Makkah"
                val country = address.countryName ?: "Saudi Arabia"
                textView.text = "Location: $city, $country"
                fetchPrayerTimes(city, country, textView)
            } else {
                textView.text = "Could not determine city, using Mecca."
                fetchPrayerTimes("Makkah", "Saudi Arabia", textView)
            }
        } catch (e: IOException) {
            textView.text = "Geocoding error: ${e.message}. Using Mecca."
            fetchPrayerTimes("Makkah", "Saudi Arabia", textView)
        }
    }

    private fun fetchPrayerTimes(city: String, country: String, textView: TextView) {
        textView.text = "Fetching prayer times for $city, $country..."
        val retrofit = Retrofit.Builder()
            .baseUrl("https://api.aladhan.com/")
            .addConverterFactory(GsonConverterFactory.create())
            .build()
        val service = retrofit.create(ApiService::class.java)
        val call = service.getTimings(city, country, 4) // Umm Al-Qura method
        call.enqueue(object : Callback<ApiResponse> {
            override fun onResponse(call: Call<ApiResponse>, response: Response<ApiResponse>) {
                if (response.isSuccessful) {
                    val timings = response.body()?.data?.timings
                    if (timings != null) {
                        val result = "$city, $country\n\n" +
                                "Fajr: ${timings.Fajr}\n" +
                                "Dhuhr: ${timings.Dhuhr}\n" +
                                "Asr: ${timings.Asr}\n" +
                                "Maghrib: ${timings.Maghrib}\n" +
                                "Isha: ${timings.Isha}"
                        textView.text = result
                    } else {
                        textView.text = "No data"
                    }
                } else {
                    textView.text = "Error: ${response.code()}"
                }
            }

            override fun onFailure(call: Call<ApiResponse>, t: Throwable) {
                textView.text = "Failed: ${t.message}"
                Toast.makeText(this@MainActivity, "Failed to fetch times: ${t.message}", Toast.LENGTH_LONG).show()
            }
        })
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "Azan channel"
            val descriptionText = "Azan notifications"
            val importance = NotificationManager.IMPORTANCE_DEFAULT
            val channel = NotificationChannel(CHANNEL_ID, name, importance).apply {
                description = descriptionText
            }
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun sendNotification(title: String, content: String) {
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(content)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)

        with(NotificationManagerCompat.from(this)) {
            if (ActivityCompat.checkSelfPermission(this@MainActivity, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
                notify(NOTIFICATION_ID, builder.build())
            }
        }
    }

    // Update checking
    private fun checkForUpdate(textView: TextView) {
        if (updateCheckInProgress) {
            Toast.makeText(this, "Check already in progress", Toast.LENGTH_SHORT).show()
            return
        }
        updateCheckInProgress = true
        textView.append("\nChecking for updates...")

        val client = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()
        val request = Request.Builder()
            .url("https://raw.githubusercontent.com/hipliteidk-glitch/azan-app/main/version.json")
            .build()

        client.newCall(request).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: IOException) {
                runOnUiThread {
                    textView.append("\nUpdate check failed: ${e.message}")
                    Toast.makeText(this@MainActivity, "Update check failed", Toast.LENGTH_SHORT).show()
                    updateCheckInProgress = false
                }
            }

            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                response.use {
                    if (!it.isSuccessful) {
                        runOnUiThread {
                            textView.append("\nUpdate check error: ${it.code}")
                            updateCheckInProgress = false
                        }
                        return
                    }
                    val body = it.body?.string()
                    if (body == null) {
                        runOnUiThread {
                            textView.append("\nEmpty version response")
                            updateCheckInProgress = false
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
                                textView.append("\nUpdate available (v$remoteVersion). Downloading...")
                                Toast.makeText(this@MainActivity, "Downloading update...", Toast.LENGTH_SHORT).show()
                                downloadAndInstallUpdate(downloadUrl, textView)
                            } else {
                                textView.append("\nYou have the latest version ($currentVersion).")
                                Toast.makeText(this@MainActivity, "Already up to date", Toast.LENGTH_SHORT).show()
                                updateCheckInProgress = false
                            }
                        }
                    } catch (e: Exception) {
                        runOnUiThread {
                            textView.append("\nFailed to parse version: ${e.message}")
                            updateCheckInProgress = false
                        }
                    }
                }
            }
        })
    }

    private fun checkForUpdateSilently(textView: TextView) {
        // Do a silent check without user interaction, but only once
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
                        val downloadUrl = json.getString("download_url")
                        val currentVersion = packageManager.getPackageInfo(packageName, 0).versionCode

                        if (remoteVersion > currentVersion) {
                            runOnUiThread {
                                Toast.makeText(this@MainActivity, "Update available! Tap 'Check for Updates' to install.", Toast.LENGTH_LONG).show()
                                textView.append("\nUpdate available (v$remoteVersion).")
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

    private fun downloadAndInstallUpdate(url: String, textView: TextView) {
        // Show notification about download progress (simplified)
        sendNotification("Downloading update", "Your update is being downloaded...")

        val client = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
        val request = Request.Builder().url(url).build()

        client.newCall(request).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: IOException) {
                runOnUiThread {
                    textView.append("\nDownload failed: ${e.message}")
                    Toast.makeText(this@MainActivity, "Download failed", Toast.LENGTH_SHORT).show()
                    updateCheckInProgress = false
                }
            }

            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                response.use {
                    if (!it.isSuccessful) {
                        runOnUiThread {
                            textView.append("\nDownload error: ${it.code}")
                            updateCheckInProgress = false
                        }
                        return
                    }
                    val body = it.body
                    if (body == null) {
                        runOnUiThread {
                            textView.append("\nEmpty response body")
                            updateCheckInProgress = false
                        }
                        return
                    }
                    try {
                        // Save to external files directory
                        val apkFile = File(getExternalFilesDir(null), "update.apk")
                        if (apkFile.exists()) apkFile.delete()
                        val fos = FileOutputStream(apkFile)
                        fos.write(body.bytes())
                        fos.close()

                        runOnUiThread {
                            textView.append("\nDownload complete. Installing...")
                            Toast.makeText(this@MainActivity, "Installing update...", Toast.LENGTH_SHORT).show()
                            installApk(apkFile)
                        }
                    } catch (e: Exception) {
                        runOnUiThread {
                            textView.append("\nInstall failed: ${e.message}")
                            Toast.makeText(this@MainActivity, "Install failed", Toast.LENGTH_SHORT).show()
                            updateCheckInProgress = false
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
        // Reset update check state after installation attempt
        updateCheckInProgress = false
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == LOCATION_PERMISSION_REQUEST) {
            if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                Toast.makeText(this, "Location permission granted. Refreshing...", Toast.LENGTH_SHORT).show()
                fetchLocationAndPrayerTimes(findViewById(R.id.textView))
            } else {
                Toast.makeText(this, "Location permission denied. Using Mecca as fallback.", Toast.LENGTH_LONG).show()
                fetchPrayerTimes("Makkah", "Saudi Arabia", findViewById(R.id.textView))
            }
        }
        if (requestCode == NOTIFICATION_PERMISSION_REQUEST) {
            // handled by system
        }
    }
}
