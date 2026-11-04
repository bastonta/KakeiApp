package com.kakei.app.ui.screens

import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.ui.unit.IntOffset

private const val PAGE_TRANSITION_MS = 300

/**
 * Push / pop transition between in-screen pages (e.g. the record editor and its
 * category / details pages). Going [forward], the new page slides in from the end
 * over the old one, which drifts slightly back; going back, the current page slides
 * out to the end and reveals the previous one. Pages must draw an opaque background.
 */
internal fun pageSlideTransition(forward: Boolean): ContentTransform {
    val spec = tween<IntOffset>(PAGE_TRANSITION_MS, easing = FastOutSlowInEasing)
    val fade = tween<Float>(PAGE_TRANSITION_MS, easing = FastOutSlowInEasing)
    return if (forward) {
        (slideInHorizontally(spec) { it } + fadeIn(fade, initialAlpha = 0.6f)) togetherWith
            (slideOutHorizontally(spec) { -it / 4 } + fadeOut(fade, targetAlpha = 0.6f))
    } else {
        (slideInHorizontally(spec) { -it / 4 } + fadeIn(fade, initialAlpha = 0.6f)) togetherWith
            (slideOutHorizontally(spec) { it } + fadeOut(fade, targetAlpha = 0.6f))
    }.apply { targetContentZIndex = if (forward) 1f else -1f }
}
