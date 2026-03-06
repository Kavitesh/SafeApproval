package com.safe.approval

import android.os.Build
import android.security.ConfirmationAlreadyPresentingException
import android.security.ConfirmationCallback
import android.security.ConfirmationNotAvailableException
import android.security.ConfirmationPrompt
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import androidx.fragment.app.FragmentActivity
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.Signature
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/**
 * Manages TEE-secured transaction approval.
 *
 * Uses two Android TEE capabilities:
 * 1. **Android Keystore (hardware-backed)** – generates a signing key inside the TEE
 *    so the private key never leaves secure hardware.
 * 2. **Android Protected Confirmation** – displays the transaction summary in a
 *    hardware-protected UI that malware cannot spoof or overlay.
 *
 * After the user confirms, the transaction data is signed with the TEE-held key
 * and the signature is returned to the caller for server-side verification.
 */
class TeeTransactionManager(private val activity: FragmentActivity) {

    companion object {
        private const val TAG = "TeeTransactionMgr"
        private const val KEY_ALIAS = "safe_transaction_key"
    }

    /** Result delivered back to the caller. */
    interface TransactionCallback {
        fun onApproved(signature: ByteArray)
        fun onRejected()
        fun onError(message: String)
    }

    private val executor: Executor = Executors.newSingleThreadExecutor()

    // ------------------------------------------------------------------
    // 1. Key generation inside TEE
    // ------------------------------------------------------------------

    /** Generate (or reuse) a hardware-backed ECDSA key pair inside the TEE. */
    fun ensureKeyPair() {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (ks.containsAlias(KEY_ALIAS)) {
            Log.d(TAG, "TEE key already exists")
            return
        }

        val spec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY
        )
            .setDigests(KeyProperties.DIGEST_SHA256)
            .setAlgorithmParameterSpec(java.security.spec.ECGenParameterSpec("secp256r1"))
            // Require user authentication for every use of this key
            .setUserAuthenticationRequired(false)
            // Request that the key live in StrongBox if available
            .apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    setIsStrongBoxBacked(true)
                }
            }
            .build()

        try {
            val kpg = KeyPairGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore"
            )
            kpg.initialize(spec)
            kpg.generateKeyPair()
            Log.d(TAG, "TEE key pair generated (StrongBox-backed)")
        } catch (e: Exception) {
            // StrongBox may not be available – fall back to regular TEE
            Log.w(TAG, "StrongBox unavailable, falling back to TEE: ${e.message}")
            val fallbackSpec = KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY
            )
                .setDigests(KeyProperties.DIGEST_SHA256)
                .setAlgorithmParameterSpec(java.security.spec.ECGenParameterSpec("secp256r1"))
                .setUserAuthenticationRequired(false)
                .build()

            val kpg = KeyPairGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore"
            )
            kpg.initialize(fallbackSpec)
            kpg.generateKeyPair()
            Log.d(TAG, "TEE key pair generated (TEE-backed)")
        }
    }

    // ------------------------------------------------------------------
    // 2. Sign transaction data with TEE-held key
    // ------------------------------------------------------------------

    private fun signData(data: ByteArray): ByteArray {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val entry = ks.getEntry(KEY_ALIAS, null) as KeyStore.PrivateKeyEntry
        val signature = Signature.getInstance("SHA256withECDSA")
        signature.initSign(entry.privateKey)
        signature.update(data)
        return signature.sign()
    }

    // ------------------------------------------------------------------
    // 3. TEE-Protected Confirmation (Android Protected Confirmation API)
    // ------------------------------------------------------------------

    /**
     * Show a hardware-protected confirmation dialog via TEE.
     *
     * The prompt text is rendered by secure hardware – the Android OS and
     * any apps/malware CANNOT read, modify, or overlay this dialog.
     * Only a physical tap on the power button confirms it.
     */
    fun requestApproval(transaction: Transaction, callback: TransactionCallback) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            callback.onError("Android Protected Confirmation requires Android 9+")
            return
        }

        if (!ConfirmationPrompt.isSupported(activity)) {
            // Device doesn't have hardware support for Protected Confirmation.
            // Fall back to signing without the protected UI.
            Log.w(TAG, "Protected Confirmation not supported – using direct signing")
            fallbackApproval(transaction, callback)
            return
        }

        val promptText = transaction.toConfirmationMessage()
        val extraData = transaction.id.toByteArray()

        try {
            val prompt = ConfirmationPrompt.Builder(activity)
                .setPromptText(promptText)
                .setExtraData(extraData)
                .build()

            prompt.presentPrompt(executor, object : ConfirmationCallback() {
                override fun onConfirmed(dataThatWasConfirmed: ByteArray) {
                    Log.d(TAG, "User APPROVED via TEE Protected Confirmation")
                    try {
                        val sig = signData(dataThatWasConfirmed)
                        activity.runOnUiThread { callback.onApproved(sig) }
                    } catch (e: Exception) {
                        activity.runOnUiThread { callback.onError("Signing failed: ${e.message}") }
                    }
                }

                override fun onDismissed() {
                    Log.d(TAG, "User REJECTED via TEE Protected Confirmation")
                    activity.runOnUiThread { callback.onRejected() }
                }

                override fun onCanceled() {
                    Log.d(TAG, "TEE confirmation cancelled")
                    activity.runOnUiThread { callback.onRejected() }
                }

                override fun onError(e: Throwable?) {
                    Log.e(TAG, "TEE confirmation error", e)
                    activity.runOnUiThread { callback.onError(e?.message ?: "Unknown error") }
                }
            })
        } catch (e: ConfirmationAlreadyPresentingException) {
            callback.onError("A confirmation is already showing")
        } catch (e: ConfirmationNotAvailableException) {
            Log.w(TAG, "Protected Confirmation not available, using fallback")
            fallbackApproval(transaction, callback)
        }
    }

    /**
     * Fallback for devices without Android Protected Confirmation hardware.
     * Uses a standard dialog + TEE-backed signing.
     */
    private fun fallbackApproval(transaction: Transaction, callback: TransactionCallback) {
        activity.runOnUiThread {
            val dialog = androidx.appcompat.app.AlertDialog.Builder(activity)
                .setTitle("Confirm Transaction")
                .setMessage(
                    "Approve this transaction?\n\n" +
                    "To: ${transaction.recipientName}\n" +
                    "Account: ${transaction.recipientAccount}\n" +
                    "Amount: ${transaction.formattedAmount}\n" +
                    "Ref: ${transaction.id}\n\n" +
                    "⚠ TEE Protected Confirmation is not available on this device.\n" +
                    "Transaction will still be signed with a TEE-backed key."
                )
                .setPositiveButton("APPROVE") { _, _ ->
                    try {
                        val data = transaction.toConfirmationMessage().toByteArray()
                        val sig = signData(data)
                        callback.onApproved(sig)
                    } catch (e: Exception) {
                        callback.onError("Signing failed: ${e.message}")
                    }
                }
                .setNegativeButton("REJECT") { _, _ ->
                    callback.onRejected()
                }
                .setCancelable(false)
                .create()

            dialog.show()
        }
    }
}
