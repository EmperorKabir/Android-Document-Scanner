package com.kabirbhasin.docscanner.ui

sealed interface Screen {
    data object Home : Screen
    data class Camera(val documentId: String, val isNewDocument: Boolean) : Screen
    data class Crop(
        val documentId: String,
        val pageId: String,
        val isNewDocument: Boolean,
        val rawPath: String,
    ) : Screen
    data class Review(val documentId: String) : Screen
}
