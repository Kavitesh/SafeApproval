package com.safe.approval

import android.os.Bundle
import android.util.Base64
import android.view.View
import android.view.animation.AnimationUtils
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.card.MaterialCardView

class MainActivity : AppCompatActivity() {

    private lateinit var teeManager: TeeTransactionManager

    // Views
    private lateinit var btnShowTransaction: Button
    private lateinit var cardTransaction: MaterialCardView
    private lateinit var tvRecipientName: TextView
    private lateinit var tvRecipientAccount: TextView
    private lateinit var tvAmount: TextView
    private lateinit var tvDescription: TextView
    private lateinit var tvDate: TextView
    private lateinit var tvRefId: TextView
    private lateinit var btnApprove: Button
    private lateinit var layoutResult: LinearLayout
    private lateinit var ivResultIcon: ImageView
    private lateinit var tvResultTitle: TextView
    private lateinit var tvResultDetail: TextView
    private lateinit var tvTeeBadge: TextView

    // Sample transaction
    private val sampleTransaction = Transaction(
        recipientName = "John Smith",
        recipientAccount = "IBAN GB82 WEST 1234 5698 7654 32",
        amount = 2500.00,
        currency = "USD",
        description = "Invoice #INV-2026-0342 Payment"
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        bindViews()
        initTee()
        setupListeners()
    }

    private fun bindViews() {
        btnShowTransaction = findViewById(R.id.btn_show_transaction)
        cardTransaction = findViewById(R.id.card_transaction)
        tvRecipientName = findViewById(R.id.tv_recipient_name)
        tvRecipientAccount = findViewById(R.id.tv_recipient_account)
        tvAmount = findViewById(R.id.tv_amount)
        tvDescription = findViewById(R.id.tv_description)
        tvDate = findViewById(R.id.tv_date)
        tvRefId = findViewById(R.id.tv_ref_id)
        btnApprove = findViewById(R.id.btn_approve)
        layoutResult = findViewById(R.id.layout_result)
        ivResultIcon = findViewById(R.id.iv_result_icon)
        tvResultTitle = findViewById(R.id.tv_result_title)
        tvResultDetail = findViewById(R.id.tv_result_detail)
        tvTeeBadge = findViewById(R.id.tv_tee_badge)
    }

    private lateinit var tvTeeStatusDetail: TextView

    private fun initTee() {
        teeManager = TeeTransactionManager(this)
        teeManager.ensureKeyPair()

        tvTeeStatusDetail = findViewById(R.id.tv_tee_status_detail)

        val status = teeManager.getTeeStatus()
        if (status.isInsideSecureHardware) {
            tvTeeBadge.text = "TEE Verified"
            tvTeeBadge.setBackgroundResource(R.drawable.badge_tee)
        } else {
            tvTeeBadge.text = "Software Key"
            tvTeeBadge.setBackgroundResource(R.drawable.badge_software)
        }

        val statusText = buildString {
            append("Key Storage: ${status.securityLevel}\n")
            append("Hardware-Backed: ${if (status.isInsideSecureHardware) "YES" else "NO"}\n")
            append("Protected Confirmation: ${if (status.protectedConfirmation) "YES" else "NO"}")
        }
        tvTeeStatusDetail.text = statusText
        tvTeeStatusDetail.visibility = View.VISIBLE
    }

    private fun setupListeners() {
        btnShowTransaction.setOnClickListener {
            showTransactionDetails()
        }

        btnApprove.setOnClickListener {
            requestTeeApproval()
        }
    }

    /** Populate and reveal the transaction card. */
    private fun showTransactionDetails() {
        tvRecipientName.text = sampleTransaction.recipientName
        tvRecipientAccount.text = sampleTransaction.recipientAccount
        tvAmount.text = sampleTransaction.formattedAmount
        tvDescription.text = sampleTransaction.description
        tvDate.text = sampleTransaction.formattedDate
        tvRefId.text = "REF: ${sampleTransaction.id}"

        // Hide button, show card with slide-in
        btnShowTransaction.visibility = View.GONE
        layoutResult.visibility = View.GONE
        cardTransaction.visibility = View.VISIBLE
        btnApprove.visibility = View.VISIBLE

        val slideIn = AnimationUtils.loadAnimation(this, android.R.anim.fade_in)
        cardTransaction.startAnimation(slideIn)
    }

    /** Trigger TEE-protected confirmation prompt. */
    private fun requestTeeApproval() {
        btnApprove.isEnabled = false
        btnApprove.text = "Waiting for TEE confirmation…"

        teeManager.requestApproval(sampleTransaction, object : TeeTransactionManager.TransactionCallback {
            override fun onApproved(signature: ByteArray) {
                val sigBase64 = Base64.encodeToString(signature, Base64.NO_WRAP)
                val status = teeManager.getTeeStatus()
                showResult(
                    approved = true,
                    detail = "Signed by: ${status.securityLevel}\n" +
                            "Hardware-Backed: ${if (status.isInsideSecureHardware) "YES" else "NO"}\n\n" +
                            "Signature (Base64):\n${sigBase64.take(64)}…"
                )
            }

            override fun onRejected() {
                showResult(approved = false, detail = "You declined the transaction.")
            }

            override fun onError(message: String) {
                showResult(approved = false, detail = "Error: $message")
                Toast.makeText(this@MainActivity, message, Toast.LENGTH_LONG).show()
            }
        })
    }

    private fun showResult(approved: Boolean, detail: String) {
        cardTransaction.visibility = View.GONE
        btnApprove.visibility = View.GONE
        layoutResult.visibility = View.VISIBLE

        if (approved) {
            ivResultIcon.setImageResource(android.R.drawable.ic_dialog_info)
            tvResultTitle.text = "Transaction Approved"
            tvResultTitle.setTextColor(getColor(R.color.tee_green))
        } else {
            ivResultIcon.setImageResource(android.R.drawable.ic_dialog_alert)
            tvResultTitle.text = "Transaction Rejected"
            tvResultTitle.setTextColor(getColor(R.color.tee_red))
        }

        tvResultDetail.text = detail

        // Allow retrying
        btnShowTransaction.visibility = View.VISIBLE
        btnShowTransaction.text = "Show Another Transaction"
        btnApprove.isEnabled = true
        btnApprove.text = "Approve with TEE"
    }
}
