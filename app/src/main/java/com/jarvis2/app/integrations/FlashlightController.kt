package com.jarvis2.app.integrations

import android.content.Context
import android.hardware.camera2.CameraManager
import android.util.Log

/** Torch control via the modern CameraManager API — no CAMERA permission needed on API 23+ for torch alone. */
class FlashlightController(context: Context) {

    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private val torchCameraId: String? by lazy {
        cameraManager.cameraIdList.firstOrNull { id ->
            cameraManager.getCameraCharacteristics(id)
                .get(android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        }
    }

    var isOn: Boolean = false
        private set

    fun setTorch(on: Boolean): Boolean {
        val id = torchCameraId ?: return false
        return try {
            cameraManager.setTorchMode(id, on)
            isOn = on
            true
        } catch (e: Exception) {
            Log.w("FlashlightController", "setTorch failed", e)
            false
        }
    }

    fun toggle(): Boolean = setTorch(!isOn)

    fun isAvailable(): Boolean = torchCameraId != null
}
