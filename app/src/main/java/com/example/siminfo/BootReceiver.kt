package com.example.siminfo

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent?.action == Intent.ACTION_BOOT_COMPLETED || 
            intent?.action == "android.intent.action.QUICKBOOT_POWERON" ||
            intent?.action == "com.htc.intent.action.QUICKBOOT_POWERON") {
            
            val prefs = context.getSharedPreferences("FambaPrefs", Context.MODE_PRIVATE)
            val username = prefs.getString("USERNAME", null)
            val isEnabled = prefs.getBoolean("POLLING_ENABLED", false)
            
            // Only start if user was logged in and polling is enabled
            if (username != null && isEnabled) {
                val serviceIntent = Intent(context, PollService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(serviceIntent)
                } else {
                    context.startService(serviceIntent)
                }
            }
        }
    }
}
