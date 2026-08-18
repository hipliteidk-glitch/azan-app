package com.example.azan

import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.widget.TextView
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.Query
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import android.util.Log

data class Timings(val Fajr: String, val Dhuhr: String, val Asr: String, val Maghrib: String, val Isha: String)
data class Data(val timings: Timings)
data class ApiResponse(val data: Data)

interface ApiService {
    @GET("v1/timingsByCity")
    fun getTimings(@Query("city") city: String, @Query("country") country: String, @Query("method") method: Int): Call<ApiResponse>
}

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val textView = findViewById<TextView>(R.id.textView)
        val retrofit = Retrofit.Builder()
            .baseUrl("http://api.aladhan.com/")
            .addConverterFactory(GsonConverterFactory.create())
            .build()
        val service = retrofit.create(ApiService::class.java)
        val call = service.getTimings("Mecca", "Saudi Arabia", 2)
        call.enqueue(object : Callback<ApiResponse> {
            override fun onResponse(call: Call<ApiResponse>, response: Response<ApiResponse>) {
                if (response.isSuccessful) {
                    val timings = response.body()?.data?.timings
                    if (timings != null) {
                        textView.text = "Fajr: ${timings.Fajr}\nDhuhr: ${timings.Dhuhr}\nAsr: ${timings.Asr}\nMaghrib: ${timings.Maghrib}\nIsha: ${timings.Isha}"
                    } else {
                        textView.text = "No data"
                    }
                } else {
                    textView.text = "Error: ${response.code()}"
                }
            }
            override fun onFailure(call: Call<ApiResponse>, t: Throwable) {
                textView.text = "Failed: ${t.message}"
            }
        })
    }
}
