package com.kabirbhasin.docscanner.ui.adaptive

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.ui.platform.LocalContext
import androidx.window.layout.FoldingFeature
import androidx.window.layout.WindowInfoTracker
import androidx.window.layout.WindowLayoutInfo

sealed interface DevicePosture {
    data object Normal : DevicePosture
    data class Book(val orientation: FoldingFeature.Orientation) : DevicePosture
    data class TableTop(val hingeTopPx: Int) : DevicePosture
    data class Separating(val orientation: FoldingFeature.Orientation) : DevicePosture
}

private fun WindowLayoutInfo.toDevicePosture(): DevicePosture {
    val fold = displayFeatures.filterIsInstance<FoldingFeature>().firstOrNull()
        ?: return DevicePosture.Normal
    return when {
        fold.state == FoldingFeature.State.HALF_OPENED &&
            fold.orientation == FoldingFeature.Orientation.HORIZONTAL ->
            DevicePosture.TableTop(fold.bounds.top)

        fold.state == FoldingFeature.State.HALF_OPENED ->
            DevicePosture.Book(fold.orientation)

        fold.isSeparating ->
            DevicePosture.Separating(fold.orientation)

        else -> DevicePosture.Normal
    }
}

private fun Context.findActivity(): Activity? {
    var current: Context = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return null
}

@Composable
fun rememberDevicePosture(): State<DevicePosture> {
    val activity = LocalContext.current.findActivity()
    return produceState<DevicePosture>(DevicePosture.Normal, activity) {
        if (activity == null) return@produceState
        WindowInfoTracker.getOrCreate(activity)
            .windowLayoutInfo(activity)
            .collect { layoutInfo -> value = layoutInfo.toDevicePosture() }
    }
}
