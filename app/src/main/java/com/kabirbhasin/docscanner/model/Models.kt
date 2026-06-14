package com.kabirbhasin.docscanner.model

import kotlinx.serialization.Serializable

@Serializable
enum class FilterType { ORIGINAL, GREYSCALE, BLACK_WHITE, MAGIC, LIGHTEN }

@Serializable
data class PageMeta(
    val id: String,
    val filter: FilterType = FilterType.ORIGINAL,
    val rev: Int = 0,
)

@Serializable
data class DocumentMeta(
    val id: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    val pages: List<PageMeta> = emptyList(),
    val watermark: String? = null,
)
