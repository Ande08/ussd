package com.example.siminfo

fun parseBalanceToMb(balanceStr: String): Double {
    return try {
        val clean = balanceStr.uppercase()
            .replace("MB", "")
            .replace(" ", "")
            .replace(",", ".")
            .trim()
        clean.toDoubleOrNull() ?: 0.0
    } catch (e: Exception) {
        0.0
    }
}
