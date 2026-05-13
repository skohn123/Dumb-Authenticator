package com.shk.dumbauthenticator

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.Executors


class ScannerActivity : ComponentActivity() {

    private lateinit var previewView: PreviewView
    private lateinit var tvScanStatus: TextView
    private lateinit var btnCancel: ImageButton

    private lateinit var storageHelper: StorageHelper

    private val cameraExecutor = Executors.newSingleThreadExecutor()
    private val scanner: BarcodeScanner = BarcodeScanning.getClient()

    private var scanComplete = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_scanner)

        previewView = findViewById(R.id.previewView)
        tvScanStatus = findViewById(R.id.tvScanStatus)
        btnCancel = findViewById(R.id.btnCancelScan)

        storageHelper = StorageHelper(this)

        btnCancel.setOnClickListener { finish() }

        startCamera()
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        try { scanner.close() } catch (_: Exception) {}
    }


    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder()
                .build()
                .also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }

            val imageAnalyzer = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also {
                    it.setAnalyzer(cameraExecutor, QrCodeAnalyzer { rawValue ->
                        runOnUiThread { handleScanResult(rawValue) }
                    })
                }

            try {
                cameraProvider.unbindAll()

                cameraProvider.bindToLifecycle(
                    this,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    imageAnalyzer
                )
            } catch (e: Exception) {
                Log.e("ScannerActivity", "Camera bind failed: ${e.message}")
                Toast.makeText(this, "Camera error: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }


    private fun handleScanResult(rawValue: String) {
        if (scanComplete) return
        scanComplete = true

        tvScanStatus.text = "QR Code found"

        val account = StorageHelper.parseOtpAuthUri(rawValue)

        if (account == null) {
            Toast.makeText(
                this,
                "Not a valid otpauth:// QR code.\n\nRaw value: $rawValue",
                Toast.LENGTH_LONG
            ).show()
            finish()
            return
        }

        storageHelper.saveAccount(account)

        playBeep()
        vibrate()

        Toast.makeText(this, "Added: ${account.label}", Toast.LENGTH_SHORT).show()
        finish()
    }


    private fun playBeep() {
        try {
            val toneGen = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 80 /* volume 0-100 */)
            toneGen.startTone(ToneGenerator.TONE_PROP_BEEP, 150 /* duration ms */)
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                toneGen.release()
            }, 300)
        } catch (e: Exception) {
            Log.w("ScannerActivity", "Beep failed (silent mode?): ${e.message}")
        }
    }


    private fun vibrate() {
        try {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = getSystemService(Context.VIBRATOR_MANAGER_SERVICE)
                        as android.os.VibratorManager
                vm.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            }
            vibrator.vibrate(
                VibrationEffect.createOneShot(200L, VibrationEffect.DEFAULT_AMPLITUDE)
            )
        } catch (e: Exception) {
            Log.w("ScannerActivity", "Vibration failed: ${e.message}")
        }
    }


    private inner class QrCodeAnalyzer(
        private val onQrCodeFound: (String) -> Unit
    ) : ImageAnalysis.Analyzer {

        @androidx.camera.core.ExperimentalGetImage
        override fun analyze(imageProxy: ImageProxy) {
            val mediaImage = imageProxy.image
            if (mediaImage == null) {
                imageProxy.close()
                return
            }

            val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)

            scanner.process(image)
                .addOnSuccessListener { barcodes ->
                    for (barcode in barcodes) {
                        if (barcode.format == Barcode.FORMAT_QR_CODE) {
                            val raw = barcode.rawValue ?: continue
                            onQrCodeFound(raw)
                            break
                        }
                    }
                }
                .addOnFailureListener { e ->
                    Log.e("QrAnalyzer", "Scan error: ${e.message}")
                }
                .addOnCompleteListener {
                    imageProxy.close()
                }
        }
    }
}