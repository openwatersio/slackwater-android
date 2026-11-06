package io.openwaters.slackwater.android.detail

import android.animation.ValueAnimator
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.SystemClock
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.openwaters.slackwater.android.core.timeline.TimelineAction
import io.openwaters.slackwater.android.core.timeline.TimelineMotion
import java.time.Duration
import kotlin.math.exp

internal class HingeReadings(
    private var width: Int = 0, var angle: Float = Float.NaN, private var lastOpening: Long = Long.MIN_VALUE,
) {
    var pending by mutableStateOf(false)
        private set
    var changedAt = 0L
        private set

    fun angle(value: Float, ms: Long) {
        if (!value.isFinite() || value == angle) return
        if (!angle.isNaN() && value > angle) lastOpening = ms
        if (value < angle) lastOpening = Long.MIN_VALUE
        angle = value
        changedAt = ms
    }

    fun width(new: Int, ms: Long) {
        if (width == new) return
        pending = width > 0 && new > width && angle in 0f..179.9f && lastOpening in (ms - 2000)..ms
        width = new
    }

    fun take() { pending = false }

    companion object {
        val Saver = listSaver<HingeReadings, Any>(
            save = { listOf(it.width, it.angle, it.lastOpening) },
            restore = { HingeReadings(it[0] as Int, it[1] as Float, it[2] as Long) },
        )
    }
}

internal class HingeFollow(private val start: Float, private val leadSeconds: Double, private val began: Long) {
    private var progress = 0.0
    private var shown = 0.0
    private var previousFrame = began

    fun frame(angle: Float, changedAt: Long, ms: Long): Double {
        val fraction = if (start >= 180f) 1.0 else ((angle - start) / (180.0 - start)).coerceIn(0.0, 1.0)
        progress = maxOf(progress, fraction)
        val timeout = if (progress > 0.0) 250L else 1000L
        if (ms - maxOf(changedAt, began) >= timeout) progress = 1.0
        shown += (progress - shown) * (1 - exp(-(ms - previousFrame).coerceAtLeast(0L) / 100.0))
        previousFrame = ms
        return if (progress == 1.0 && (1 - shown) * leadSeconds < 60) 1.0 else shown
    }
}

@Composable
internal fun DetailHinge(timeline: DetailTimeline?) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R || !ValueAnimator.areAnimatorsEnabled()) return
    val context = LocalContext.current
    val manager = remember(context) { context.getSystemService(SensorManager::class.java) } ?: return
    val sensor = remember(manager) { manager.getDefaultSensor(Sensor.TYPE_HINGE_ANGLE) } ?: return
    val readings = rememberSaveable(saver = HingeReadings.Saver) { HingeReadings() }
    val width = LocalConfiguration.current.screenWidthDp
    SideEffect { readings.width(width, SystemClock.elapsedRealtime()) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(sensor, lifecycle) {
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                readings.angle(event.values[0], event.timestamp / 1_000_000)
            }
            override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) = Unit
        }
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> manager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_GAME)
                Lifecycle.Event.ON_PAUSE -> manager.unregisterListener(listener)
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); manager.unregisterListener(listener) }
    }
    LaunchedEffect(timeline, readings.pending, (timeline?.state?.viewport ?: 0.0) > 0) {
        if (timeline != null && readings.pending && timeline.state.viewport > 0) {
            readings.take()
            if (readings.angle < 180f) timeline.dispatch(TimelineAction.BeginHingeFollow)
        }
    }
    val state = timeline?.state ?: return
    LaunchedEffect(timeline, state.motion, state.generation, width) {
        val move = state.move ?: return@LaunchedEffect
        if (state.motion != TimelineMotion.HINGE_FOLLOW) return@LaunchedEffect
        val follow = HingeFollow(readings.angle, Duration.between(move.to, move.from).toMillis() / 1000.0,
            SystemClock.elapsedRealtime())
        try {
            while (timeline.state.motion == TimelineMotion.HINGE_FOLLOW && timeline.state.generation == state.generation)
                withFrameNanos {
                    timeline.dispatch(TimelineAction.AnimationFrame(state.generation,
                        follow.frame(readings.angle, readings.changedAt, SystemClock.elapsedRealtime())))
                }
        } finally {
            // A second resize or screen removal parks the original target. A newer user gesture owns its generation.
            timeline.dispatch(TimelineAction.AnimationFrame(state.generation, 1.0))
        }
    }
}
