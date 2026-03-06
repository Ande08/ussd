package com.example.siminfo

import android.content.Context
import android.content.SharedPreferences

class SessionManager(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    companion object {
        private const val PREF_NAME = "FambaPrefs"
        private const val KEY_USERNAME = "USERNAME"
        private const val KEY_PASSWORD = "PASSWORD"
        private const val KEY_ACCOUNT = "ACCOUNT"
        private const val KEY_POLLING_ENABLED = "POLLING_ENABLED"

        @Volatile
        private var instance: SessionManager? = null

        fun getInstance(context: Context): SessionManager {
            return instance ?: synchronized(this) {
                instance ?: SessionManager(context.applicationContext).also { instance = it }
            }
        }
    }

    var username: String?
        get() = prefs.getString(KEY_USERNAME, null)?.trim()
        set(value) = prefs.edit().putString(KEY_USERNAME, value?.trim()).apply()

    var password: String?
        get() = prefs.getString(KEY_PASSWORD, null)?.trim()
        set(value) = prefs.edit().putString(KEY_PASSWORD, value?.trim()).apply()

    var account: String?
        get() = prefs.getString(KEY_ACCOUNT, null)?.trim()
        set(value) = prefs.edit().putString(KEY_ACCOUNT, value?.trim()).apply()

    var isPollingEnabled: Boolean
        get() = prefs.getBoolean(KEY_POLLING_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_POLLING_ENABLED, value).apply()

    fun logout() {
        prefs.edit().clear().apply()
        AppState.isBackendPollingEnabled.value = false
        AppState.ussdBalances.clear()
        AppState.deviceList.clear()
    }

    fun isLoggedIn(): Boolean = !username.isNullOrBlank() && !account.isNullOrBlank()
}
