package com.igorg.scanereyes

import android.Manifest
import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.MediaStore
import android.text.InputType
import android.util.Log
import android.view.MotionEvent
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.igorg.scanereyes.databinding.ActivityMainBinding
import com.igorg.scanereyes.websocket.WebSocketManager
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var ws: WebSocketManager

    private var camera: Camera? = null
    private var imageCapture: ImageCapture? = null
    private var lastFocusPoint: Pair<Float, Float>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ws = WebSocketManager {
            runOnUiThread { takePhoto() }
        }

        val serverIp = getSavedServerIp()
        ws.connect(serverIp)

        if (allPermissionsGranted()) {
            startCamera()
        } else {
            requestPermissions()
        }

        binding.imageCaptureButton.setOnClickListener {
            takePhoto()
        }

        binding.settingsButton.setOnClickListener {
            showSettingsDialog()
        }

        binding.focusButton.setOnClickListener {
            triggerFocus()
            Toast.makeText(this, R.string.focusing, Toast.LENGTH_SHORT).show()
        }

        setupTapToFocus()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupTapToFocus() {
        binding.viewFinder.setOnTouchListener { view, event ->
            if (event.action == MotionEvent.ACTION_UP) {
                view.performClick()
                triggerFocus(event.x, event.y)
                Toast.makeText(this, R.string.focusing, Toast.LENGTH_SHORT).show()
            }
            true
        }
    }

    private fun triggerFocus(
        x: Float? = null,
        y: Float? = null,
        onComplete: (() -> Unit)? = null
    ) {
        val cam = camera ?: run {
            onComplete?.invoke()
            return
        }

        val targetX = x ?: lastFocusPoint?.first ?: (binding.viewFinder.width / 2f)
        val targetY = y ?: lastFocusPoint?.second ?: (binding.viewFinder.height / 2f)

        if (x != null && y != null) {
            lastFocusPoint = Pair(x, y)
        }

        val factory = binding.viewFinder.meteringPointFactory
        val point = factory.createPoint(targetX, targetY)

        val action = FocusMeteringAction.Builder(
            point,
            FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE
        ).setAutoCancelDuration(5, TimeUnit.SECONDS).build()

        val future = cam.cameraControl.startFocusAndMetering(action)
        future.addListener({
            try {
                val result = future.get()
                if (result.isFocusSuccessful) {
                    Log.d(TAG, "Autofocus succeeded")
                } else {
                    Log.w(TAG, "Autofocus failed")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error during focus", e)
            } finally {
                onComplete?.invoke()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun showSettingsDialog() {
        val currentIp = getSavedServerIp()
        val currentForceAf = getSavedForceAutofocus()

        val context = this
        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val margin = (16 * resources.displayMetrics.density).toInt()
            setPadding(margin, margin / 2, margin, 0)
        }

        val ipInput = EditText(context).apply {
            setText(currentIp)
            setSelection(currentIp.length)
            hint = DEFAULT_SERVER_IP
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        }

        val afCheckBox = MaterialCheckBox(context).apply {
            text = getString(R.string.force_autofocus)
            isChecked = currentForceAf
            val topPadding = (8 * resources.displayMetrics.density).toInt()
            setPadding(0, topPadding, 0, 0)
        }

        layout.addView(ipInput)
        layout.addView(afCheckBox)

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.settings_dialog_title)
            .setMessage(R.string.settings_dialog_message)
            .setView(layout)
            .setPositiveButton(R.string.save) { _, _ ->
                val newIp = ipInput.text.toString().trim()
                if (newIp.isNotEmpty()) {
                    saveServerIp(newIp)
                    saveForceAutofocus(afCheckBox.isChecked)
                    ws.connect(newIp)
                    Toast.makeText(this, R.string.ip_saved, Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, R.string.ip_empty_error, Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun getSavedServerIp(): String {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        return prefs.getString(KEY_SERVER_IP, DEFAULT_SERVER_IP) ?: DEFAULT_SERVER_IP
    }

    private fun saveServerIp(ip: String) {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        prefs.edit { putString(KEY_SERVER_IP, ip) }
    }

    private fun getSavedForceAutofocus(): Boolean {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        return prefs.getBoolean(KEY_FORCE_AUTOFOCUS, true)
    }

    private fun saveForceAutofocus(enabled: Boolean) {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        prefs.edit { putBoolean(KEY_FORCE_AUTOFOCUS, enabled) }
    }

    private fun takePhoto() {
        val imageCapture = imageCapture ?: return

        if (getSavedForceAutofocus()) {
            var hasRun = false
            val runCaptureOnce = {
                if (!hasRun) {
                    hasRun = true
                    performCapture(imageCapture)
                }
            }

            // Fallback safety timeout after 2 seconds
            binding.root.postDelayed({ runCaptureOnce() }, 2000L)

            triggerFocus {
                runCaptureOnce()
            }
        } else {
            performCapture(imageCapture)
        }
    }

    private fun performCapture(imageCapture: ImageCapture) {
        val name = SimpleDateFormat(
            FILENAME_FORMAT,
            Locale.US
        ).format(System.currentTimeMillis())

        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            put(
                MediaStore.Images.Media.RELATIVE_PATH,
                "Pictures/Scanereyes"
            )
        }

        val outputOptions = ImageCapture.OutputFileOptions.Builder(
            contentResolver,
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            contentValues
        ).build()

        imageCapture.takePicture(
            outputOptions,
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {

                override fun onError(exc: ImageCaptureException) {
                    Log.e(TAG, "Photo capture failed", exc)
                }

                override fun onImageSaved(
                    output: ImageCapture.OutputFileResults
                ) {
                    ws.notifyPhotoTaken()

                    val message =
                        "Photo capture succeeded: ${output.savedUri}"

                    Toast.makeText(
                        this@MainActivity,
                        message,
                        Toast.LENGTH_SHORT
                    ).show()

                    Log.d(TAG, message)
                }
            }
        )
    }

    private fun startCamera() {
        val cameraProviderFuture =
            ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({

            val cameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder()
                .build()
                .also {
                    it.surfaceProvider = binding.viewFinder.surfaceProvider
                }

            imageCapture = ImageCapture.Builder().build()

            try {
                cameraProvider.unbindAll()

                camera = cameraProvider.bindToLifecycle(
                    this,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    imageCapture
                )

            } catch (exc: Exception) {
                Log.e(TAG, "Use case binding failed", exc)
            }

        }, ContextCompat.getMainExecutor(this))
    }

    private fun requestPermissions() {
        permissionLauncher.launch(REQUIRED_PERMISSIONS)
    }

    private fun allPermissionsGranted(): Boolean =
        REQUIRED_PERMISSIONS.all {
            ContextCompat.checkSelfPermission(
                this,
                it
            ) == PackageManager.PERMISSION_GRANTED
        }

    private val permissionLauncher =
        registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { permissions ->

            if (permissions.all { it.value }) {
                startCamera()
            } else {
                Toast.makeText(
                    this,
                    "Camera permission denied",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }

    override fun onDestroy() {
        ws.disconnect()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "Scanereyes"
        private const val FILENAME_FORMAT = "yyyy-MM-dd-HH-mm-ss-SSS"
        private const val DEFAULT_SERVER_IP = "192.168.100.118"
        private const val PREFS_NAME = "scanereyes_prefs"
        private const val KEY_SERVER_IP = "server_ip"
        private const val KEY_FORCE_AUTOFOCUS = "force_autofocus"

        private val REQUIRED_PERMISSIONS = arrayOf(
            Manifest.permission.CAMERA
        )
    }
}