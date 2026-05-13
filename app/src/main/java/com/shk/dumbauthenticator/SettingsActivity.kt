package com.shk.dumbauthenticator

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts

class SettingsActivity : ComponentActivity() {

    private lateinit var storageHelper: StorageHelper
    private var pendingExportPassword: String? = null

    private val createEncryptedLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        App.endSubIntent()
        if (result.resultCode != RESULT_OK) { pendingExportPassword = null; return@registerForActivityResult }
        val uri = result.data?.data ?: run { pendingExportPassword = null; return@registerForActivityResult }
        val pw = pendingExportPassword ?: return@registerForActivityResult
        pendingExportPassword = null
        try {
            val rawJson = storageHelper.exportAsJson()
            val encryptedData = storageHelper.encryptBackup(rawJson, pw)
            contentResolver.openOutputStream(uri)?.use { it.write(encryptedData.toByteArray()) }
            Toast.makeText(this, "Encrypted backup saved", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Export failed: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private val createPlainLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        App.endSubIntent()
        if (result.resultCode != RESULT_OK) return@registerForActivityResult
        val uri = result.data?.data ?: return@registerForActivityResult
        try {
            val rawJson = storageHelper.exportAsJson()
            contentResolver.openOutputStream(uri)?.use { it.write(rawJson.toByteArray()) }
            Toast.makeText(this, "Plaintext backup saved", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Export failed: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private val openImportLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        App.endSubIntent()
        if (result.resultCode != RESULT_OK) return@registerForActivityResult
        val uri = result.data?.data ?: return@registerForActivityResult
        try {
            val content = contentResolver.openInputStream(uri)?.use {
                it.readBytes().toString(Charsets.UTF_8)
            } ?: ""
            if (StorageHelper.isEncryptedBackup(content)) {
                promptDecryptAndImport(content)
            } else {
                doImportPlain(content)
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Import failed: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        findViewById<TextView>(R.id.topBarTitle).text = "SETTINGS"
        findViewById<ImageButton>(R.id.btnTopBack).setOnClickListener { finish() }

        storageHelper = StorageHelper(this)

        findViewById<Button>(R.id.itemManageAccounts).setOnClickListener {
            startActivity(Intent(this, ManageAccountsActivity::class.java))
        }

        findViewById<Button>(R.id.btnExport).setOnClickListener { exportEncrypted() }
        findViewById<Button>(R.id.btnExportPlain).setOnClickListener { exportPlainWithWarning() }
        findViewById<Button>(R.id.btnImport).setOnClickListener { startImport() }

        findViewById<Button>(R.id.itemPin).setOnClickListener {
            val intent = Intent(this, PinActivity::class.java)
            val mode = if (storageHelper.hasPinSet()) "change" else "setup"
            intent.putExtra("mode", mode)
            startActivity(intent)
        }

        findViewById<Button>(R.id.itemCheckUpdate).setOnClickListener {
            UpdateChecker.check(this)
        }

        findViewById<Button>(R.id.itemAbout).setOnClickListener {
            startActivity(Intent(this, InfoActivity::class.java))
        }
    }

    private fun exportEncrypted() {
        val passwordInput = EditText(this).apply {
            hint = "Create Backup Password"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }

        AlertDialog.Builder(this, R.style.Theme_DA_Dialog)
            .setTitle("Encrypted Export")
            .setMessage("Set a password to encrypt your backup file. You will need this to import it later.")
            .setView(passwordInput)
            .setPositiveButton("Continue") { _, _ ->
                val password = passwordInput.text.toString()
                if (password.length < 4) {
                    Toast.makeText(this, "Password too short!", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                pendingExportPassword = password
                val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                    type = "application/octet-stream"
                    addCategory(Intent.CATEGORY_OPENABLE)
                    putExtra(Intent.EXTRA_TITLE, "dumbauth_backup.enc")
                }
                App.beginSubIntent()
                createEncryptedLauncher.launch(intent)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun exportPlainWithWarning() {
        AlertDialog.Builder(this, R.style.Theme_DA_Dialog)
            .setTitle("Plaintext Export")
            .setMessage("This will save your TOTP secrets in unencrypted JSON. Anyone with access to the file will be able to read your codes. Continue?")
            .setPositiveButton("Continue") { _, _ ->
                val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                    type = "application/json"
                    addCategory(Intent.CATEGORY_OPENABLE)
                    putExtra(Intent.EXTRA_TITLE, "dumbauth_backup.json")
                }
                App.beginSubIntent()
                createPlainLauncher.launch(intent)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun startImport() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            type = "*/*"
            addCategory(Intent.CATEGORY_OPENABLE)
        }
        App.beginSubIntent()
        openImportLauncher.launch(intent)
    }

    private fun promptDecryptAndImport(encryptedContent: String, errorMessage: String? = null) {
        val passwordInput = EditText(this).apply {
            hint = "Enter Backup Password"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        AlertDialog.Builder(this, R.style.Theme_DA_Dialog)
            .setTitle("Encrypted Backup")
            .setMessage(errorMessage ?: "Enter the password used when exporting.")
            .setView(passwordInput)
            .setPositiveButton("Import") { _, _ ->
                val password = passwordInput.text.toString()
                if (password.isEmpty()) {
                    promptDecryptAndImport(encryptedContent, "Password is empty. Try again.")
                    return@setPositiveButton
                }
                try {
                    val decryptedJson = storageHelper.decryptBackup(encryptedContent, password)
                    val count = storageHelper.importFromJson(decryptedJson)
                    if (count >= 0) {
                        Toast.makeText(this, "Imported $count accounts!", Toast.LENGTH_LONG).show()
                    } else {
                        Toast.makeText(this, "Import failed: invalid data", Toast.LENGTH_SHORT).show()
                    }
                } catch (_: Exception) {
                    promptDecryptAndImport(encryptedContent, "Wrong password. Try again.")
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun doImportPlain(json: String) {
        val count = storageHelper.importFromJson(json)
        if (count >= 0) {
            Toast.makeText(this, "Imported $count accounts!", Toast.LENGTH_LONG).show()
        } else {
            Toast.makeText(this, "Import failed: not a valid backup", Toast.LENGTH_SHORT).show()
        }
    }
}
