package com.kabirbhasin.docscanner

import android.app.Application
import android.util.Log
import org.opencv.android.OpenCVLoader

class DocScannerApp : Application() {

    override fun onCreate() {
        super.onCreate()
        if (!OpenCVLoader.initLocal()) {
            Log.e(TAG, "OpenCV failed to initialise")
        }
    }

    private companion object {
        const val TAG = "DocScannerApp"
    }
}
