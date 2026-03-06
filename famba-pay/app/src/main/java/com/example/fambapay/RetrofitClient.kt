package com.example.fambapay

import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.POST

data class PaymentConfirmation(
    val type: String,
    val code: String,
    val amount: String,
    val secret: String = "famba-admin"
)

interface FambaPayApi {
    @POST("/api/confirmations/register")
    suspend fun registerPayment(@Body confirmation: PaymentConfirmation)
}

object RetrofitClient {
    private const val BASE_URL = "http://144.91.121.85:3003"

    val api: FambaPayApi by lazy {
        Retrofit.Builder()
            .baseUrl(BASE_URL)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(FambaPayApi::class.java)
    }
}
