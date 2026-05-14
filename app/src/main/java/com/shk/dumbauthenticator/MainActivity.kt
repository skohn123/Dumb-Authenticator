package com.shk.dumbauthenticator

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import dev.turingcomplete.kotlinonetimepassword.HmacAlgorithm
import dev.turingcomplete.kotlinonetimepassword.TimeBasedOneTimePasswordConfig
import dev.turingcomplete.kotlinonetimepassword.TimeBasedOneTimePasswordGenerator
import java.util.concurrent.TimeUnit

class MainActivity : ComponentActivity() {

    private lateinit var storageHelper: StorageHelper

    private lateinit var listView: ListView
    private lateinit var emptyState: View
    private var accounts = mutableListOf<StorageHelper.Account>()
    private lateinit var adapter: AccountAdapter
    private var mainScreenLoaded = false

    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private val refreshRunnable = object : Runnable {
        override fun run() {
            refreshCodes()
            handler.postDelayed(this, 1000L)
        }
    }

    private val pinLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            App.isUnlocked = true
            loadMainScreenIfNeeded()
            processPendingOtpauth()
            handler.post(refreshRunnable)
        } else {
            finish()
        }
    }

    private val imagePickerLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        App.endSubIntent()
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let { decodeImageUri(it) }
        }
    }

    private val cameraPermLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            startActivity(Intent(this, ScannerActivity::class.java))
        } else {
            Toast.makeText(this, "Camera permission denied", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)

        storageHelper = StorageHelper(this)
        val appPrefs = getSharedPreferences("app_prefs", MODE_PRIVATE)

        if (appPrefs.getBoolean("is_first_launch", true)) {
            startActivity(Intent(this, SetupActivity::class.java))
            finish()
            return
        }

        captureOtpauthFromIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        captureOtpauthFromIntent(intent)
    }

    private fun captureOtpauthFromIntent(intent: Intent?) {
        if (intent == null) return
        if (Intent.ACTION_VIEW != intent.action) return
        val data = intent.data ?: return
        if (!"otpauth".equals(data.scheme, ignoreCase = true)) return
        App.pendingOtpauth = data.toString()
    }

    private fun processPendingOtpauth() {
        val raw = App.pendingOtpauth ?: return
        App.pendingOtpauth = null
        val account = StorageHelper.parseOtpAuthUri(raw)
        if (account == null) {
            Toast.makeText(this, "Could not parse otpauth URI", Toast.LENGTH_LONG).show()
            return
        }
        storageHelper.saveAccount(account)
        Toast.makeText(this, "Added: ${account.label}", Toast.LENGTH_SHORT).show()
        refreshFromStorage()
    }

    private fun loadMainScreenIfNeeded() {
        if (mainScreenLoaded) {
            refreshFromStorage()
            return
        }
        mainScreenLoaded = true
        setContentView(R.layout.activity_main)
        showDisclaimerIfFirstLaunch()

        listView = findViewById(R.id.listViewAccounts)
        listView.itemsCanFocus = true
        emptyState = findViewById(R.id.emptyState)

        findViewById<Button>(R.id.btnScan).setOnClickListener { showAddQrChooser() }
        findViewById<Button>(R.id.btnManual).setOnClickListener { showManualEntryDialog() }
        findViewById<ImageButton>(R.id.btnSettings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        findViewById<ImageButton>(R.id.btnInfo).setOnClickListener {
            startActivity(Intent(this, InfoActivity::class.java))
        }

        accounts = storageHelper.getAllAccounts().toMutableList()
        adapter = AccountAdapter()
        listView.adapter = adapter

        updateEmptyState()
    }

    private fun copyAccountCode(account: StorageHelper.Account) {
        val code = generateTotp(account)
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("TOTP Code", code)

        val extras = android.os.PersistableBundle()
        extras.putBoolean("android.content.extra.IS_SENSITIVE", true)
        clip.description.extras = extras

        clipboard.setPrimaryClip(clip)
        Toast.makeText(this, "Copied code for ${account.label}", Toast.LENGTH_SHORT).show()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (mainScreenLoaded && App.isUnlocked && keyCode == KeyEvent.KEYCODE_MENU) {
            showAddQrChooser()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun showAddQrChooser() {
        val options = arrayOf("Live Camera Scan", "Pick Image from Gallery")
        AlertDialog.Builder(this, R.style.Theme_DA_Dialog)
            .setTitle("Add via QR")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> launchScanner()
                    1 -> launchImagePicker()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun generateTotp(account: StorageHelper.Account): String {
        return try {
            val secretBytes = base32Decode(account.secret)
            val config = TimeBasedOneTimePasswordConfig(
                codeDigits = account.digits,
                hmacAlgorithm = HmacAlgorithm.valueOf(account.algorithm),
                timeStep = account.period.toLong(),
                timeStepUnit = TimeUnit.SECONDS
            )
            val generator = TimeBasedOneTimePasswordGenerator(secretBytes, config)
            generator.generate()
        } catch (_: Exception) {
            "ERROR"
        }
    }

    private fun base32Decode(input: String): ByteArray {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
        val cleaned = input.replace(" ", "").uppercase().trimEnd('=')
        var buffer = 0L
        var bitsLeft = 0
        val result = mutableListOf<Byte>()
        for (char in cleaned) {
            val value = alphabet.indexOf(char)
            if (value < 0) continue
            buffer = (buffer shl 5) or value.toLong()
            bitsLeft += 5
            if (bitsLeft >= 8) {
                bitsLeft -= 8
                result.add(((buffer shr bitsLeft) and 0xFFL).toByte())
            }
        }
        return result.toByteArray()
    }

    private fun showManualEntryDialog() {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 8)
        }
        val etLabel = EditText(this).apply { hint = "Account Name" }
        val etSecret = EditText(this).apply {
            hint = "Secret Key"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        }
        layout.addView(etLabel)
        layout.addView(etSecret)

        AlertDialog.Builder(this, R.style.Theme_DA_Dialog)
            .setTitle("Manual Entry")
            .setView(layout)
            .setPositiveButton("Add") { _, _ ->
                val label = etLabel.text.toString().trim()
                val secret = etSecret.text.toString().trim()
                if (label.isNotEmpty() && secret.isNotEmpty()) {
                    storageHelper.saveAccount(StorageHelper.Account(label, secret))
                    refreshFromStorage()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun refreshFromStorage() {
        if (!::storageHelper.isInitialized || !::adapter.isInitialized) return

        accounts.clear()
        accounts.addAll(storageHelper.getAllAccounts())
        adapter.notifyDataSetChanged()
        updateEmptyState()
    }

    private fun refreshCodes() {
        if (::adapter.isInitialized) adapter.notifyDataSetChanged()
    }

    private fun updateEmptyState() {
        if (::emptyState.isInitialized && ::listView.isInitialized) {
            emptyState.visibility = if (accounts.isEmpty()) View.VISIBLE else View.GONE
            listView.visibility = if (accounts.isEmpty()) View.GONE else View.VISIBLE
        }
    }

    private fun showDisclaimerIfFirstLaunch() {
        val prefs = getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        if (prefs.getBoolean("disclaimer_accepted", false)) return

        AlertDialog.Builder(this, R.style.Theme_DA_Dialog)
            .setTitle("Legal Disclaimer")
            .setCancelable(false)
            .setMessage(
                "This app is provided 'AS-IS' for personal use only. By tapping 'I Understand', you agree to the following:\n\n" +
                        "- NO LIABILITY: The developer is NOT responsible for any loss of account access, data loss, or damages.\n\n" +
                        "- ENCRYPTION: Secrets are stored in a secure vault.\n\n" +
                        "- NO RECOVERY: If you forget your PIN, the developer CANNOT recover your codes.\n\n" +
                        "- BACKUP SECURITY: Exported backups are the user's responsibility to secure."
            )
            .setPositiveButton("I Understand") { _, _ ->
                prefs.edit().putBoolean("disclaimer_accepted", true).apply()
            }
            .show()
    }

    private fun launchScanner() {
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            cameraPermLauncher.launch(Manifest.permission.CAMERA)
        } else {
            startActivity(Intent(this, ScannerActivity::class.java))
        }
    }

    private fun launchImagePicker() {
        val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
            type = "image/*"
            addCategory(Intent.CATEGORY_OPENABLE)
        }
        App.beginSubIntent()
        imagePickerLauncher.launch(intent)
    }

    private fun decodeImageUri(uri: Uri) {
        val bitmap: Bitmap? = try {
            contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
        } catch (_: Exception) { null }

        if (bitmap == null) {
            Toast.makeText(this, "Could not load image", Toast.LENGTH_SHORT).show()
            return
        }

        decodeBitmap(bitmap) { result ->
            bitmap.recycle()
            if (result == null) {
                Toast.makeText(this, "No QR code found in image", Toast.LENGTH_LONG).show()
                return@decodeBitmap
            }
            val account = StorageHelper.parseOtpAuthUri(result)
            if (account == null) {
                Toast.makeText(this, "QR is not an otpauth code", Toast.LENGTH_LONG).show()
                return@decodeBitmap
            }
            storageHelper.saveAccount(account)
            Toast.makeText(this, "Added: ${account.label}", Toast.LENGTH_SHORT).show()
            refreshFromStorage()
        }
    }

    private fun decodeBitmap(bitmap: Bitmap, onResult: (String?) -> Unit) {
        val scanner = BarcodeScanning.getClient()
        val finish: (String?) -> Unit = { value ->
            try { scanner.close() } catch (_: Exception) {}
            onResult(value)
        }
        scanner.process(InputImage.fromBitmap(bitmap, 0))
            .addOnSuccessListener { barcodes ->
                val hit = barcodes.firstOrNull { it.format == Barcode.FORMAT_QR_CODE }?.rawValue
                if (hit != null) { finish(hit); return@addOnSuccessListener }
                val inverted = invertBitmap(bitmap)
                if (inverted == null) { finish(null); return@addOnSuccessListener }
                scanner.process(InputImage.fromBitmap(inverted, 0))
                    .addOnSuccessListener { bs2 ->
                        val hit2 = bs2.firstOrNull { it.format == Barcode.FORMAT_QR_CODE }?.rawValue
                        inverted.recycle()
                        finish(hit2)
                    }
                    .addOnFailureListener {
                        inverted.recycle()
                        finish(null)
                    }
            }
            .addOnFailureListener { finish(null) }
    }

    private fun invertBitmap(src: Bitmap): Bitmap? = try {
        val w = src.width
        val h = src.height
        val pixels = IntArray(w * h)
        src.getPixels(pixels, 0, w, 0, 0, w, h)
        for (i in pixels.indices) pixels[i] = pixels[i] xor 0x00FFFFFF
        Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
    } catch (_: Exception) { null }

    override fun onResume() {
        super.onResume()
        if (storageHelper.hasPinSet() && !App.isUnlocked) {
            val intent = Intent(this, PinActivity::class.java).apply {
                putExtra("mode", "verify")
            }
            pinLauncher.launch(intent)
        } else {
            App.isUnlocked = true
            loadMainScreenIfNeeded()
            processPendingOtpauth()
            handler.post(refreshRunnable)
        }
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(refreshRunnable)
    }

    inner class AccountAdapter : BaseAdapter() {
        override fun getCount() = accounts.size
        override fun getItem(position: Int) = accounts[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView ?: LayoutInflater.from(this@MainActivity)
                .inflate(R.layout.list_item_account, parent, false)
            val account = accounts[position]

            view.findViewById<TextView>(R.id.tvAccountLabel).text = account.label

            val rawCode = generateTotp(account)
            val formatted = if (rawCode.length == 6)
                "${rawCode.substring(0, 3)} ${rawCode.substring(3)}"
            else rawCode
            view.findViewById<TextView>(R.id.tvTotpCode).text = formatted

            val avatar = view.findViewById<TextView>(R.id.tvAvatar)
            avatar.text = account.label.trim().firstOrNull()?.uppercase() ?: "?"

            val secondsRemaining =
                (account.period - (System.currentTimeMillis() / 1000) % account.period).toInt()

            val tvSeconds = view.findViewById<TextView>(R.id.tvSecondsRemaining)
            tvSeconds.text = secondsRemaining.toString()
            val warn = secondsRemaining <= 5
            tvSeconds.setTextColor(
                getColor(if (warn) R.color.warn_red else R.color.accent_green)
            )

            val progressBar = view.findViewById<ProgressBar>(R.id.progressBarItem)
            progressBar.max = account.period
            progressBar.progress = secondsRemaining

            view.setOnClickListener { copyAccountCode(account) }
            view.setOnLongClickListener { copyAccountCode(account); true }

            return view
        }
    }
}
