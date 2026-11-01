package io.github.dishan18.books.ui

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.navigation.NavBackStackEntry

private const val DURATION = 320

/** Push/pop like iOS: the new screen slides over, the old one drifts back a little. */
object NavTransitions {
    val enter: AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition =
        { slideInHorizontally(tween(DURATION, easing = FastOutSlowInEasing)) { it } + fadeIn(tween(DURATION)) }
    val exit: AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition =
        { slideOutHorizontally(tween(DURATION, easing = FastOutSlowInEasing)) { -it / 4 } + fadeOut(tween(DURATION)) }
    val popEnter: AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition =
        { slideInHorizontally(tween(DURATION, easing = FastOutSlowInEasing)) { -it / 4 } + fadeIn(tween(DURATION)) }
    val popExit: AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition =
        { slideOutHorizontally(tween(DURATION, easing = FastOutSlowInEasing)) { it } + fadeOut(tween(DURATION)) }
}
