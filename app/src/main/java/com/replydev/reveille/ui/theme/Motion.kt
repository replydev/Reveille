package com.replydev.reveille.ui.theme

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp

/** Material 3 motion tokens (m3.material.io/styles/motion), kept to the few the app uses. */
object Motion {
    val EmphasizedDecelerate = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
    val EmphasizedAccelerate = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)
    val Emphasized = CubicBezierEasing(0.2f, 0f, 0f, 1f)

    const val SHORT2 = 100
    const val SHORT3 = 150
    const val MEDIUM1 = 250
    const val MEDIUM2 = 300

    /** Shared axis X: the screens travel this far while they fade through. */
    private val SharedAxisOffset = 30.dp

    private fun offsetPx(density: Density) = with(density) { SharedAxisOffset.roundToPx() }

    /** Forward: the new screen slides in from the end while the old one fades out early. */
    fun sharedAxisEnter(density: Density, forward: Boolean): EnterTransition =
        slideInHorizontally(tween(MEDIUM2, easing = Emphasized)) { if (forward) offsetPx(density) else -offsetPx(density) } +
            fadeIn(tween(MEDIUM1 - SHORT2, delayMillis = SHORT2, easing = EmphasizedDecelerate))

    fun sharedAxisExit(density: Density, forward: Boolean): ExitTransition =
        slideOutHorizontally(tween(MEDIUM2, easing = Emphasized)) { if (forward) -offsetPx(density) else offsetPx(density) } +
            fadeOut(tween(SHORT2, easing = EmphasizedAccelerate))

    /** Lazy list items: a quick fade and a critically damped spring that settles fast without overshoot. */
    val itemFade: FiniteAnimationSpec<Float> = tween(SHORT3, easing = LinearEasing)
    val itemPlacement: FiniteAnimationSpec<IntOffset> =
        spring(stiffness = Spring.StiffnessMedium, visibilityThreshold = IntOffset.VisibilityThreshold)

    /** Size changes inside a card or row. */
    val size: FiniteAnimationSpec<IntSize> =
        spring(stiffness = Spring.StiffnessMediumLow, visibilityThreshold = IntSize.VisibilityThreshold)

    /** Content that appears or disappears inside a column. */
    val expand: EnterTransition = expandVertically(size) + fadeIn(tween(SHORT3, delayMillis = SHORT2 / 2))
    val collapse: ExitTransition = shrinkVertically(size) + fadeOut(tween(SHORT2))
}

/** Animates insertion, removal and reordering of a lazy list item with the app's motion. */
fun Modifier.animatedItem(scope: LazyItemScope): Modifier = with(scope) {
    this@animatedItem.animateItem(fadeInSpec = Motion.itemFade, placementSpec = Motion.itemPlacement, fadeOutSpec = Motion.itemFade)
}
