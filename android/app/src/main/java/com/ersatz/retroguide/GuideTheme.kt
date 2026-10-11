package com.ersatz.retroguide

import android.view.Gravity

/**
 * A look for the guide, picked in SETTINGS and saved in [Prefs].
 *
 * Every theme shares one layout, modelled on two cable guides the owner
 * supplied photos of: an info panel and the picture across the top, a dense
 * grid below with a single highlighted cell, and a key-hint bar.
 *
 * Every surface is a flat colour drawn as an embossed plate ([Emboss]), the
 * way the Prevue Channel grid of 1993-99 was - not a gradient, which read as
 * too modern. The bevel shades come from the fill, so a theme only names
 * fills and inks.
 *
 * Colours are ARGB ints. Art is sourced, not drawn - see THEMES.md at the
 * root of the repo.
 */
data class GuideTheme(
    val key: String,
    val label: String,
    /** The screen behind everything. */
    val bg: Int,
    /** Info panel, top left. */
    val panel: Int, val rule: Int,
    val titleInk: Int, val infoInk: Int, val accentInk: Int, val clock: Int,
    /** Logo: two words in two colours, with optional art before them. */
    val logoA: String, val logoAInk: Int, val logoB: String, val logoBInk: Int,
    val logoArt: Int = 0,
    /** Border round the picture. */
    val frame: Int,
    /** The time bar over the grid. */
    val head: Int, val headInk: Int,
    /** Channel boxes: number over call sign, as Prevue had them. */
    val chan: Int, val numInk: Int, val chanInk: Int, val tunedInk: Int,
    /** Programme blocks. */
    val cell: Int, val cellInk: Int,
    /** The one highlighted block. */
    val sel: Int, val selInk: Int,
    /** Key-hint bar. */
    val foot: Int, val footInk: Int,
    /** res/font for logo and clock, or 0 for bold monospace. */
    val font: Int = 0,
    /** Art laid over the screen edges; it never sits on the grid or picture. */
    val deco: List<Deco> = emptyList(),
) {
    /** One piece of art: a drawable, its size in dp, and where it sits. */
    data class Deco(val drawable: Int, val sizeDp: Int, val gravity: Int, val xDp: Int, val yDp: Int)

    companion object {
        /** Indigo and amber, the look this app started with. */
        val CLASSIC = GuideTheme(
            key = "classic", label = "CLASSIC",
            bg = 0xFF0C0C3A.toInt(),
            panel = 0xFF24248C.toInt(), rule = 0xFFFFC020.toInt(),
            titleInk = 0xFFFFFFFF.toInt(), infoInk = 0xFFDADAFF.toInt(),
            accentInk = 0xFF66E0FF.toInt(), clock = 0xFFFFC020.toInt(),
            logoA = "RETRO", logoAInk = 0xFFFFC020.toInt(), logoB = "GUIDE", logoBInk = 0xFFFFFFFF.toInt(),
            frame = 0xFFFFC020.toInt(),
            head = 0xFF16166A.toInt(), headInk = 0xFFFFC020.toInt(),
            chan = 0xFF24248C.toInt(), numInk = 0xFFFFFFFF.toInt(), chanInk = 0xFFFFC020.toInt(),
            tunedInk = 0xFF66E0FF.toInt(),
            cell = 0xFF2C2CA0.toInt(), cellInk = 0xFFFFFFFF.toInt(),
            sel = 0xFFFFC020.toInt(), selInk = 0xFF140A00.toInt(),
            foot = 0xFF16166A.toInt(), footInk = 0xFFDADAFF.toInt(),
        )

        /** The Prevue Channel, 1993-99, colours sampled from a broadcast
         *  capture: navy plates with near-white bevels, white titles, the
         *  channel number in white over a yellow call sign, and yellow
         *  times on a lighter blue time bar. WeatherSTAR face on the logo. */
        val RETRO = GuideTheme(
            key = "retro", label = "RETRO 90S",
            bg = 0xFF000000.toInt(),
            panel = 0xFF323278.toInt(), rule = 0xFFF2DC4A.toInt(),
            titleInk = 0xFFFFFFFF.toInt(), infoInk = 0xFFDCDCF5.toInt(),
            accentInk = 0xFFF2DC4A.toInt(), clock = 0xFFFFFFFF.toInt(),
            logoA = "RETRO", logoAInk = 0xFFFFFFFF.toInt(), logoB = "GUIDE", logoBInk = 0xFFF2DC4A.toInt(),
            logoArt = R.drawable.theme_tv,
            frame = 0xFFDCDCF5.toInt(),
            head = 0xFF5A6EC8.toInt(), headInk = 0xFFF2DC4A.toInt(),
            chan = 0xFF323278.toInt(), numInk = 0xFFFFFFFF.toInt(), chanInk = 0xFFF2DC4A.toInt(),
            tunedInk = 0xFF7FE0FF.toInt(),
            cell = 0xFF323278.toInt(), cellInk = 0xFFFFFFFF.toInt(),
            sel = 0xFFF2DC4A.toInt(), selInk = 0xFF14143C.toInt(),
            foot = 0xFF323278.toInt(), footInk = 0xFFFFFFFF.toInt(),
            font = R.font.star4000,
        )

        /** Purple plates, a slime-green cursor and pumpkin-orange lettering,
         *  under a jack-o'-lantern logo, a moon with bats, and pumpkins on
         *  the edge. */
        val HALLOWEEN = GuideTheme(
            key = "halloween", label = "HALLOWEEN",
            bg = 0xFF0E0418.toInt(),
            panel = 0xFF3A1258.toInt(), rule = 0xFF7CFF3A.toInt(),
            titleInk = 0xFFFFFFFF.toInt(), infoInk = 0xFFE6D8FF.toInt(),
            accentInk = 0xFFFF9A2E.toInt(), clock = 0xFF9CFF57.toInt(),
            logoA = "RETRO", logoAInk = 0xFFFF8A1C.toInt(), logoB = "guide", logoBInk = 0xFF9CFF57.toInt(),
            logoArt = R.drawable.theme_pumpkin,
            frame = 0xFF7CFF3A.toInt(),
            head = 0xFF2A0C42.toInt(), headInk = 0xFFFF9A2E.toInt(),
            chan = 0xFF2A0C42.toInt(), numInk = 0xFFFF9A2E.toInt(), chanInk = 0xFF9CFF57.toInt(),
            tunedInk = 0xFFFFFFFF.toInt(),
            cell = 0xFF54207A.toInt(), cellInk = 0xFFFFFFFF.toInt(),
            sel = 0xFF7CFF3A.toInt(), selInk = 0xFF12061C.toInt(),
            foot = 0xFF2A0C42.toInt(), footInk = 0xFFE6D8FF.toInt(),
            deco = listOf(
                // Moon and bats in the empty strip between logo and clock.
                Deco(R.drawable.theme_moon, 34, Gravity.TOP or Gravity.START, 404, 26),
                Deco(R.drawable.theme_bat, 24, Gravity.TOP or Gravity.START, 368, 40),
                Deco(R.drawable.theme_bat, 18, Gravity.TOP or Gravity.START, 446, 30),
                // Pumpkins and a web on the ends of the hint bar.
                Deco(R.drawable.theme_pumpkin, 30, Gravity.BOTTOM or Gravity.START, 14, 14),
                Deco(R.drawable.theme_pumpkin, 24, Gravity.BOTTOM or Gravity.START, 44, 14),
                Deco(R.drawable.theme_web, 46, Gravity.BOTTOM or Gravity.END, 6, 6),
            ),
        )

        val ALL = listOf(CLASSIC, RETRO, HALLOWEEN)

        fun of(key: String?): GuideTheme = ALL.firstOrNull { it.key == key } ?: CLASSIC
    }
}
