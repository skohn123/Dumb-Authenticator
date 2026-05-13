package com.shk.dumbauthenticator

import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.ImageButton
import android.widget.TextView
import androidx.activity.ComponentActivity

class InfoActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_info)

        findViewById<TextView>(R.id.topBarTitle).text = "ABOUT"
        findViewById<ImageButton>(R.id.btnTopBack).setOnClickListener { finish() }

        val version = try {
            @Suppress("DEPRECATION")
            packageManager.getPackageInfo(packageName, 0).versionName ?: "0.0.0"
        } catch (_: PackageManager.NameNotFoundException) { "0.0.0" }
        findViewById<TextView>(R.id.tvVersion).text = "v$version"
    }
}
