package com.ayuemin.disputeai

import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.material3.DrawerState
import androidx.compose.material3.DrawerValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Makes opening the drawer easy on large phones: a rightward swipe may start
 * from the left 80% of the screen, while the rightmost 20% stays outside this gesture.
 */
fun Modifier.wideDrawerOpenGesture(
    drawerState: DrawerState,
    scope: CoroutineScope,
    activeFraction: Float = 0.80f
): Modifier = pointerInput(drawerState.currentValue, activeFraction) {
    if (drawerState.currentValue != DrawerValue.Closed) return@pointerInput

    var startX = Float.MAX_VALUE
    var travel = 0f
    var triggered = false
    val triggerDistance = 18.dp.toPx()

    detectHorizontalDragGestures(
        onDragStart = { offset ->
            startX = offset.x
            travel = 0f
            triggered = false
        },
        onDragCancel = {
            startX = Float.MAX_VALUE
            travel = 0f
            triggered = false
        },
        onDragEnd = {
            startX = Float.MAX_VALUE
            travel = 0f
            triggered = false
        },
        onHorizontalDrag = { change, dragAmount ->
            if (triggered || startX > size.width * activeFraction || dragAmount <= 0f) {
                return@detectHorizontalDragGestures
            }
            travel += dragAmount
            if (travel >= triggerDistance) {
                change.consume()
                triggered = true
                scope.launch { drawerState.open() }
            }
        }
    )
}

/**
 * A forgiving back gesture for secondary screens. A leftward swipe can start
 * from the right 28% of the screen, which is much easier to hit than a tiny edge strip.
 */
fun Modifier.rightEdgeBackGesture(
    onBack: () -> Unit,
    activeFraction: Float = 0.28f
): Modifier = pointerInput(activeFraction, onBack) {
    var startX = Float.MIN_VALUE
    var travel = 0f
    var triggered = false
    val triggerDistance = 24.dp.toPx()

    detectHorizontalDragGestures(
        onDragStart = { offset ->
            startX = offset.x
            travel = 0f
            triggered = false
        },
        onDragCancel = {
            startX = Float.MIN_VALUE
            travel = 0f
            triggered = false
        },
        onDragEnd = {
            startX = Float.MIN_VALUE
            travel = 0f
            triggered = false
        },
        onHorizontalDrag = { change, dragAmount ->
            if (triggered || startX < size.width * (1f - activeFraction) || dragAmount >= 0f) {
                return@detectHorizontalDragGestures
            }
            travel += -dragAmount
            if (travel >= triggerDistance) {
                change.consume()
                triggered = true
                onBack()
            }
        }
    )
}
