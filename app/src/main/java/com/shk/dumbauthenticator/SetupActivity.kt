package com.shk.dumbauthenticator

import android.Manifest
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.Toast
import android.app.AlertDialog
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts

class SetupActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_setup)

        findViewById<Button>(R.id.btnGrantCamera).setOnClickListener {
            cameraLauncher.launch(Manifest.permission.CAMERA)
        }

        findViewById<Button>(R.id.btnCreatePin).setOnClickListener {
            completeSetup(usePin = true)
        }

        findViewById<Button>(R.id.btnSkipPin).setOnClickListener {
            showSkipWarning()
        }
    }

    private fun showSkipWarning() {
        AlertDialog.Builder(this, R.style.Theme_DA_Dialog)
            .setTitle("Unsecure App")
            .setMessage("Anyone with access to your phone will be able to see your codes. Continue?")
            .setPositiveButton("Skip Anyway") { _, _ -> completeSetup(false) }
            .setNegativeButton("Go Back", null)
            .show()
    }

    private fun completeSetup(usePin: Boolean) {
        getSharedPreferences("app_prefs", MODE_PRIVATE).edit()
            .putBoolean("is_first_launch", false).apply()

        if (usePin) {
            val intent = Intent(this, PinActivity::class.java)
            intent.putExtra("mode", "setup")
            startActivity(intent)
        } else {
            startActivity(Intent(this, MainActivity::class.java))
        }
        finish()
    }

    private val cameraLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
        if (isGranted) Toast.makeText(this, "Camera Ready", Toast.LENGTH_SHORT).show()
    }
}
