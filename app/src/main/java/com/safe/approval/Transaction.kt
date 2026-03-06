package com.safe.approval

import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

data class Transaction(
    val id: String = UUID.randomUUID().toString().take(8).uppercase(),
    val recipientName: String,
    val recipientAccount: String,
    val amount: Double,
    val currency: String = "USD",
    val description: String,
    val timestamp: Date = Date()
) {
    val formattedAmount: String
        get() {
            val formatter = NumberFormat.getCurrencyInstance(Locale.US)
            return formatter.format(amount)
        }

    val formattedDate: String
        get() {
            val sdf = SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.getDefault())
            return sdf.format(timestamp)
        }

    /** Short summary shown inside the TEE-protected confirmation dialog. */
    fun toConfirmationMessage(): String {
        return "Pay $formattedAmount to $recipientName ($recipientAccount)?\nRef: $id"
    }
}
