package com.example.siminfo

import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST

// Models
data class TransferJob(
    val id: Int,
    val number: String,
    val amount: String,
    val status: String
)

data class PendingResponse(
    val message: String?,
    val job: TransferJob?
)

data class PendingRequest(
    val username: String,
    val jobId: Int? = null
)

data class UpdateStatusRequest(
    val id: Int,
    val status: String,
    val username: String
)

data class UpdateResponse(
    val message: String?,
    val error: String?
)

data class LoginRequest(
    val username: String,
    val password: String,
    val account: String,
    val name: String? = null
)

data class LoginResponse(
    val success: Boolean,
    val message: String?,
    val error: String?,
    val name: String?
)

data class RegisterRequest(
    val username: String,
    val password: String,
    val name: String,
    val account: String
)

data class PauseRequest(
    val username: String,
    val paused: Boolean
)

data class DeviceStatusRequest(
    val username: String,
    val balance: String,
    val paused: Boolean,
    val battery: Int
)

data class Device(
    val username: String,
    val name: String?,
    val balance: String?,
    val paused: Int, // SQLite returns 1/0
    val battery: Int,
    val last_seen: String?
) {
    val isPaused: Boolean get() = paused == 1
}

data class DeviceListResponse(
    val devices: List<Device>
)

data class SimpleResponse(
    val success: Boolean,
    val error: String?
)

data class ScheduleTransferRequest(
    val number: String,
    val amount: String,
    val account: String, // Renamed from username to match backend
    val targetDevice: String? = null,
    val secret: String? = "famba-admin"
)

data class RetryFailedRequest(
    val account: String
)

data class ScheduleTransferResponse(
    val id: Int?,
    val message: String?,
    val error: String?
)

// API Interface
interface BackendApi {
    @POST("api/device/login")
    suspend fun loginDevice(@Body request: LoginRequest): LoginResponse

    @POST("api/device/status")
    suspend fun updateDeviceStatus(@Body request: DeviceStatusRequest): SimpleResponse

    @POST("api/transfer/pending")
    suspend fun getPendingTransfer(@Body request: PendingRequest): PendingResponse

    @POST("api/transfer/update")
    suspend fun updateTransferStatus(@Body request: UpdateStatusRequest): UpdateResponse
    
    @GET("api/devices")
    suspend fun getDevices(@retrofit2.http.Query("account") account: String?): DeviceListResponse

    @POST("api/transfer")
    suspend fun scheduleTransfer(@Body request: ScheduleTransferRequest): ScheduleTransferResponse

    @POST("api/device/register")
    suspend fun registerDevice(@Body request: RegisterRequest): SimpleResponse

    @POST("api/device/pause")
    suspend fun togglePause(@Body request: PauseRequest): SimpleResponse

    @POST("api/transfer/retry_failed")
    suspend fun retryFailedJobs(@Body request: RetryFailedRequest): SimpleResponse
}

// Retrofit Object
object RetrofitClient {
    // Note: For an emulator, use "http://10.0.2.2:3000/". 
    // For a real device, use your computer's IP or an Ngrok URL.
    // Changing this to the local network IP or ngrok later is required.
    private const val BASE_URL = "http://144.91.121.85:3003/" 

    val api: BackendApi by lazy {
        Retrofit.Builder()
            .baseUrl(BASE_URL)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(BackendApi::class.java)
    }
}
