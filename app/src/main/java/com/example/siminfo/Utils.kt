package com.example.siminfo

fun parseBalanceToMb(balanceStr: String): Double {
    return try {
        val uppercase = balanceStr.uppercase().trim()
        val numericPart = uppercase.replace(Regex("[^0-9.,]"), "").replace(",", ".").trim()
        val value = numericPart.toDoubleOrNull() ?: 0.0
        
        when {
            uppercase.contains("GB") -> value * 1024.0
            uppercase.contains("KB") -> value / 1024.0
            else -> value // Default to MB
        }
    } catch (e: Exception) {
        0.0
    }
}

