package com.kabirbhasin.docscanner.deeplog

import android.app.Application
import android.content.Context
import androidx.startup.Initializer

class DeepLoggerInitializer : Initializer<Unit> {
    override fun create(context: Context) {
        val app = context.applicationContext as? Application ?: return
        DeepLogger.install(app)
    }

    override fun dependencies(): List<Class<out Initializer<*>>> = emptyList()
}
