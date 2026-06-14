package com.kabirbhasin.docscanner.model

import kotlinx.serialization.Serializable

@Serializable
enum class FilterType { ORIGINAL, GREYSCALE, BLACK_WHITE, MAGIC }

@Serializable
data class PageMeta(
    val id: String,
    val filter: FilterType = FilterType.ORIGINAL,
)

@Serializable
data class DocumentMeta(
    val id: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    val pages: List<PageMeta> = emptyList(),
)
