package com.vaishnava.alarm

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density

/**
 * App-wide text sizing.
 *
 * Every `.sp` in Compose is multiplied by [Density.fontScale] before it becomes pixels, so
 * overriding that one value rescales all text everywhere - including sizes hardcoded at call
 * sites. That is what makes this universal without editing individual screens.
 *
 * `dp` is deliberately left untouched, so padding, icons and card sizes stay put while text
 * adapts inside them.
 *
 * Two factors combine:
 *
 * 1. Screen width, relative to the ~400dp phone this UI was designed against. Small phones get
 *    slightly smaller text, tablets slightly larger, so lines wrap the same way everywhere.
 *
 * 2. The user's own font-size preference, clamped. This is the one that matters on MIUI/Redmi,
 *    where "Font size" and "Display size" are separate settings that multiply together and can
 *    push text far past what any fixed layout can absorb.
 *
 * The clamp still honours a meaningful increase or decrease in the user's chosen size; it only
 * refuses the extremes that no fixed layout can absorb.
 */
@Composable
fun ResponsiveTextScale(content: @Composable () -> Unit) {
    val configuration = LocalConfiguration.current
    val density = LocalDensity.current

    val scaledDensity = remember(
        density.density,
        density.fontScale,
        configuration.screenWidthDp
    ) {
        val widthFactor = (configuration.screenWidthDp / DESIGN_WIDTH_DP)
            .coerceIn(MIN_WIDTH_FACTOR, MAX_WIDTH_FACTOR)

        val userFactor = density.fontScale
            .coerceIn(MIN_USER_FONT_SCALE, MAX_USER_FONT_SCALE)

        Density(
            density = density.density,
            fontScale = widthFactor * userFactor
        )
    }

    CompositionLocalProvider(LocalDensity provides scaledDensity, content = content)
}

/** The screen width this UI was laid out against. */
private const val DESIGN_WIDTH_DP = 400f

private const val MIN_WIDTH_FACTOR = 0.88f
private const val MAX_WIDTH_FACTOR = 1.15f

/**
 * Accessibility boundary. Users who enlarge system text still get larger text here, but not
 * so much that alarm controls scroll off screen - which on an alarm means being unable to
 * dismiss it.
 */
private const val MIN_USER_FONT_SCALE = 0.90f
private const val MAX_USER_FONT_SCALE = 1.20f
