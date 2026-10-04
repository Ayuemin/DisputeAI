package com.ayuemin.disputeai

import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.material3.DrawerState
import androidx.compose.material3.DrawerValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Makes opening the drawer easier on large phones: a rightward swipe may start
 * from the left 28% of the screen, not only from a narrow system-edge strip.
 * Material3 still handles the actual drawer animation and close gesture.
 */
fun Modifier.wideDrawerOpenGesture(
    drawerState: DrawerState,
    scope: CoroutineScope,
    activeFraction: Float = 0.28f
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
            if (triggered || startX > size.width * activeFraction) return@detectHorizontalDragGestures
            if (dragAmount <= 0f) return@detectHorizontalDragGestures

            travel += dragAmount
            if (travel >= triggerDistance) {
                change.consume()
                triggered = true
                scope.launch { drawerState.open() }
            }
        }
    )
}
