package app.mjolnir.android.ui

import android.view.HapticFeedbackConstants
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.geometry.Offset
import androidx.compose.foundation.layout.offset
import kotlin.math.roundToInt
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import app.mjolnir.android.throwing.FlickSensor

/** The label under the hammer: what the button says in each state. */
fun hammerLabel(phase: ThrowPhase, hasTarget: Boolean): String = when {
    !hasTarget -> "Pair a device first"
    phase == ThrowPhase.Armed -> "Flick!"
    phase == ThrowPhase.Throwing -> "Throwing…"
    else -> "Hold & flick to throw"
}

/**
 * Press and hold to arm, flick the phone to throw. [sensor] counts flicks only while held; the view model decides
 * whether a press arms (needs a target and a selection).
 */
@Composable
fun HammerButton(vm: ThrowViewModel, sensor: FlickSensor, phase: ThrowPhase, hasTarget: Boolean, flight: HammerFlight, modifier: Modifier = Modifier) {
    val view = LocalView.current
    val enabled = hasTarget
    val currentVm by rememberUpdatedState(vm)
    val currentSensor by rememberUpdatedState(sensor)

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .height(200.dp)
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                awaitEachGesture {
                    awaitFirstDown()
                    currentVm.onHoldStart()
                    val armed = currentVm.state.value.phase == ThrowPhase.Armed
                    if (armed) {
                        currentSensor.start()
                        view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                    }
                    try {
                        waitForUpOrCancellation()
                    } finally { // also runs when this coroutine is cancelled mid-hold (e.g. `enabled` flips)
                        if (armed) {
                            currentSensor.stop()
                            currentVm.onHoldEnd()
                        }
                    }
                }
            },
        shape = RoundedCornerShape(24.dp),
        color = if (!enabled) MaterialTheme.colorScheme.surfaceVariant
        else if (phase == ThrowPhase.Armed) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.primaryContainer,
    ) {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Box(Modifier.size(96.dp).alpha(flight.restAlpha.value), contentAlignment = Alignment.Center) { Text("🔨", fontSize = 64.sp) }
            Text(
                hammerLabel(phase, hasTarget),
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(bottom = 16.dp),
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** The haptic for a registered flick: CONFIRM on API 30+, LONG_PRESS before. */
fun confirmHaptic(view: android.view.View) {
    view.performHapticFeedback(
        if (android.os.Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.LONG_PRESS,
    )
}

/** Animation state shared by the resting hammer in the button and the [FlyingHammer] overlay. */
class HammerFlight(val progress: Animatable<Float, *>, val restAlpha: Animatable<Float, *>)

@Composable
fun rememberHammerFlight(phase: ThrowPhase): HammerFlight {
    val flight = remember { HammerFlight(Animatable(0f), Animatable(1f)) }
    LaunchedEffect(phase == ThrowPhase.Throwing) {
        if (phase == ThrowPhase.Throwing) {
            flight.restAlpha.snapTo(0f) // the resting hammer is the one that flies
            flight.progress.snapTo(0f)
            flight.progress.animateTo(1f, tween(400))
        } else {
            flight.progress.snapTo(0f)
            flight.restAlpha.animateTo(1f, tween(250)) // fades back in at rest
        }
    }
    return flight
}

/**
 * The flying hammer, drawn at the root of the throw screen (outside the button's clipped surface): 400 ms from the
 * button up past the top edge, 0 to 720 degrees. [start] is the resting hammer's centre (the button's centre) in this
 * overlay's coordinates, measured from the button's layout; null until the button has been laid out. Takes no
 * pointer input.
 */
@Composable
fun FlyingHammer(flight: HammerFlight, phase: ThrowPhase, start: Offset?, modifier: Modifier = Modifier) {
    val density = LocalDensity.current
    val screenHeightPx = with(density) { LocalConfiguration.current.screenHeightDp.dp.toPx() }
    if (phase != ThrowPhase.Throwing || flight.progress.value >= 1f || start == null) return
    val halfPx = with(density) { 48.dp.roundToPx() }
    Box(modifier.fillMaxSize()) {
        Box(
            Modifier
                .offset { IntOffset(start.x.roundToInt() - halfPx, start.y.roundToInt() - halfPx) }
                .size(96.dp)
                .graphicsLayer {
                    clip = false
                    translationY = -screenHeightPx * flight.progress.value
                    rotationZ = 720f * flight.progress.value
                },
            contentAlignment = Alignment.Center,
        ) { Text("🔨", fontSize = 64.sp) }
    }
}
