package com.shk.dumbauthenticator

import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity

class PinActivity : ComponentActivity() {

    private lateinit var storageHelper: StorageHelper
    private lateinit var etPinInput: EditText
    private lateinit var tvPinTitle: TextView
    private lateinit var btnSubmit: Button
    private var mode: String? = null

    private var isConfirming = false
    private var firstEntry = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_pin)

        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)

        storageHelper = StorageHelper(this)
        etPinInput = findViewById(R.id.etPinInput)
        tvPinTitle = findViewById(R.id.tvPinTitle)
        btnSubmit = findViewById(R.id.btnSubmitPin)

        mode = intent.getStringExtra("mode") ?: "verify"

        updateUIForMode()

        btnSubmit.setOnClickListener {
            handlePinSubmit()
        }

        etPinInput.setOnEditorActionListener { _, actionId, event ->
            val enter = event != null &&
                event.action == KeyEvent.ACTION_DOWN &&
                event.keyCode == KeyEvent.KEYCODE_ENTER
            if (actionId == EditorInfo.IME_ACTION_DONE || actionId == EditorInfo.IME_ACTION_GO || enter) {
                handlePinSubmit()
                true
            } else false
        }
    }

    private fun updateUIForMode() {
        when (mode) {
            "setup" -> {
                tvPinTitle.text = if (isConfirming) "Confirm New PIN" else "Create New PIN"
                btnSubmit.text = if (isConfirming) "CONFIRM" else "NEXT"
            }
            "change" -> {
                tvPinTitle.text = "Enter Old PIN"
                btnSubmit.text = "VERIFY"
            }
            "verify" -> {
                tvPinTitle.text = "Enter App PIN"
                btnSubmit.text = "UNLOCK"
            }
            else -> {
                tvPinTitle.text = "Enter App PIN"
                btnSubmit.text = "UNLOCK"
            }
        }
    }

    private fun handlePinSubmit() {
        val input = etPinInput.text.toString()
        if (input.length < 4) {
            Toast.makeText(this, "PIN must be at least 4 digits", Toast.LENGTH_SHORT).show()
            return
        }

        if (mode == "verify" || mode == "change") {
            val locked = storageHelper.remainingLockSeconds()
            if (locked > 0) {
                Toast.makeText(this, "Locked. Try again in ${locked}s.", Toast.LENGTH_LONG).show()
                etPinInput.text.clear()
                return
            }
        }

        when (mode) {
            "setup" -> {
                if (!isConfirming) {
                    firstEntry = input
                    isConfirming = true
                    etPinInput.text.clear()
                    updateUIForMode()
                } else {
                    if (input == firstEntry) {
                        storageHelper.savePin(input)
                        Toast.makeText(this, "PIN Secured", Toast.LENGTH_SHORT).show()
                        setResult(RESULT_OK)
                        finish()
                    } else {
                        Toast.makeText(this, "PINs do not match. Try again.", Toast.LENGTH_SHORT).show()
                        isConfirming = false
                        firstEntry = ""
                        etPinInput.text.clear()
                        updateUIForMode()
                    }
                }
            }
            "verify" -> {
                if (storageHelper.verifyPin(input)) {
                    setResult(RESULT_OK)
                    finish()
                } else {
                    Toast.makeText(this, "Incorrect PIN", Toast.LENGTH_SHORT).show()
                    etPinInput.text.clear()
                }
            }
            "change" -> {
                if (storageHelper.verifyPin(input)) {
                    mode = "setup"
                    isConfirming = false
                    etPinInput.text.clear()
                    updateUIForMode()
                } else {
                    Toast.makeText(this, "Incorrect Old PIN", Toast.LENGTH_SHORT).show()
                    etPinInput.text.clear()
                }
            }
        }
    }
}