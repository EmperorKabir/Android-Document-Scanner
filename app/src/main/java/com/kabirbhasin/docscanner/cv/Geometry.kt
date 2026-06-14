package com.kabirbhasin.docscanner.cv

/** A corner in normalised image coordinates (0f..1f), independent of display size. */
data class Corner(val x: Float, val y: Float)

/** Four ordered corners of a detected page: top-left, top-right, bottom-right, bottom-left. */
data class Quad(
    val tl: Corner,
    val tr: Corner,
    val br: Corner,
    val bl: Corner,
) {
    fun toList(): List<Corner> = listOf(tl, tr, br, bl)

    companion object {
        val FULL = Quad(
            tl = Corner(0f, 0f),
            tr = Corner(1f, 0f),
            br = Corner(1f, 1f),
            bl = Corner(0f, 1f),
        )

        fun of(corners: List<Corner>): Quad =
            Quad(corners[0], corners[1], corners[2], corners[3])
    }
}
