package com.enderthor.kSafe.datatype

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.view.View
import android.widget.RemoteViews
import com.enderthor.kSafe.R
import com.enderthor.kSafe.activity.FieldTapReceiver
import com.enderthor.kSafe.data.EmergencyStatus
import com.enderthor.kSafe.data.FIELD_COLOR_AUTO
import com.enderthor.kSafe.extension.managers.ConfigurationManager
import com.enderthor.kSafe.extension.managers.EmergencyManager
import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.extension.DataTypeImpl
import io.hammerhead.karooext.internal.ViewEmitter
import io.hammerhead.karooext.models.ShowCustomStreamState
import io.hammerhead.karooext.models.UpdateGraphicConfig
import io.hammerhead.karooext.models.ViewConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.produceIn
import kotlinx.coroutines.launch
import timber.log.Timber

private const val COLOR_COUNTDOWN = 0xFFE65100.toInt()
private const val COLOR_ALERTING  = 0xFFB71C1C.toInt()

/** karoo-ext 1.1.9 `ViewEmitter.updateView` silently DROPS any call < 900 ms after the
 *  last accepted one (1 Hz limit, logs "ignoring updateView, too soon") and never resends
 *  it, so a dropped final frame leaves the field stale. Every render waits until at least
 *  this long after the previous one (50 ms slop over the SDK's 900). */
internal const val COALESCE_GUARD_MS = 950L

/** Suspends until a render after [lastRenderMs] will be accepted by the SDK. [lastRenderMs]
 *  must be taken AFTER the previous updateView returned (the SDK stamps on entry, so ours
 *  is then never earlier). The deadline is NOT capped: after a backward wall-clock jump the
 *  SDK drops everything until its own deadline, so returning early would just hand it a frame
 *  to drop. Each delay IS capped, so a clock that recovers mid-hold is noticed within one
 *  guard period instead of after the whole jump. */
internal suspend fun awaitRenderSlot(lastRenderMs: Long, now: () -> Long = System::currentTimeMillis) {
    while (true) {
        val wait = lastRenderMs + COALESCE_GUARD_MS - now()
        if (wait <= 0) return
        delay(minOf(wait, COALESCE_GUARD_MS))
    }
}

/** Conflated + paced for a [ViewEmitter]: at most one value per [COALESCE_GUARD_MS], and
 *  the value painted after a hold is the newest one, not the one that started the hold.
 *  [seedRenderMs] = time a synchronous seed updateView RETURNED, if any. */
internal fun <T> Flow<T>.pacedForView(seedRenderMs: Long = 0L): Flow<T> = flow {
    var last = seedRenderMs
    coroutineScope {
        // conflate() does not always conflate: on a StateFlow it fuses to the flow itself and
        // produceIn falls back to a BUFFERED channel, so drain to the newest value. isSuccess
        // (not getOrNull) keeps a newer null value from being mistaken for "nothing newer".
        val ch = conflate().produceIn(this)
        for (v in ch) {
            awaitRenderSlot(last)
            var latest = v
            while (true) {
                val r = ch.tryReceive()
                if (!r.isSuccess) break
                latest = r.getOrThrow()
            }
            emit(latest)
            last = System.currentTimeMillis()
        }
    }
}

class SOSDataType(
    datatype: String,
    private val context: Context,
    private val karooSystem: KarooSystemService,
) : DataTypeImpl("ksafe", datatype) {

    private val configManager = ConfigurationManager(context)

    // Cached PendingIntent — see CarbLogDataType for the rationale (PI identity is
    // stable across emissions, so build once and reuse).
    @Volatile private var cachedPi: PendingIntent? = null
    private fun pendingIntentFor(context: Context): PendingIntent {
        cachedPi?.let { return it }
        return PendingIntent.getBroadcast(
            context, 101,
            Intent(FieldTapReceiver.ACTION_SOS).setPackage(context.packageName),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        ).also { cachedPi = it }
    }

    /** Builds a field view with optional click PendingIntent (requestCode 101 = SOS). */
    private fun buildView(context: Context, config: ViewConfig, bgColor: Int, main: String, hint: String = "", clickable: Boolean = true): RemoteViews {
        // See CarbLogDataType.buildView — same layout-switch + center alignment
        // (tap-target field) + auto-mode text colour contract.
        val isAuto = bgColor == FIELD_COLOR_AUTO
        val layout = if (isAuto) R.layout.field_view_auto else R.layout.field_view
        val content = RemoteViews(context.packageName, layout).apply {
            if (!isAuto) setInt(R.id.field_container, "setBackgroundColor", bgColor)
            setTextViewText(R.id.field_text_main, main.take(9))
            setTextViewText(R.id.field_text_hint, hint.take(9))
            setViewVisibility(R.id.field_text_hint, if (hint.isEmpty()) View.GONE else View.VISIBLE)
            if (isAuto) {
                val dark = context.isKarooNightMode()
                setTextColor(R.id.field_text_main, if (dark) Color.WHITE else Color.BLACK)
                setTextColor(R.id.field_text_hint, if (dark) 0xCCFFFFFF.toInt() else 0xCC000000.toInt())
            }
        }
        if (!config.preview && clickable) {
            val wrapper = RemoteViews(context.packageName, R.layout.field_tap_wrapper)
            wrapper.setOnClickPendingIntent(R.id.field_tap_wrapper, pendingIntentFor(context))
            wrapper.addView(R.id.field_tap_wrapper, content)
            return wrapper
        }
        return content
    }

    override fun startView(context: Context, config: ViewConfig, emitter: ViewEmitter) {
        // Synchronous seed frame BEFORE launching any coroutine. Without it Karoo
        // paints the host theme background while waiting for the first
        // Dispatchers.Default emission, which in day mode shows as a blank white
        // field (white text on white host bg) — see CarbStatusDataType. SOS is the
        // safety-critical field, so the seed mirrors the ACTUAL current state read
        // synchronously from the canonical StateFlow (not a blind "SAFE"):
        // the SDK's 1 Hz limit can hold the next frame back ~1 s, so it must be
        // correct even if a countdown/alert is already running when the field
        // re-attaches (page swap, ride-app restart). The configured idle colour is
        // only available asynchronously (DataStore), so the IDLE seed uses the
        // default green and the real colour lands on the colorFlow emission below.
        val seedState = EmergencyManager.uiState.value
        when (seedState.status) {
            EmergencyStatus.COUNTDOWN -> emitter.updateView(buildView(
                context, config, COLOR_COUNTDOWN,
                context.getString(R.string.sos_countdown, seedState.countdownRemaining()),
                context.getString(R.string.sos_tap_cancel),
            ))
            EmergencyStatus.ALERTING -> emitter.updateView(buildView(
                context, config, COLOR_ALERTING,
                context.getString(R.string.sos_alerting),
                clickable = false,
            ))
            else -> emitter.updateView(buildView(
                context, config, 0xFF1B5E20.toInt(),
                context.getString(R.string.sos_safe),
                context.getString(R.string.sos_field_tap_sos),
            ))
        }
        // Taken AFTER the seed updateView returned — see awaitRenderSlot.
        val seedRenderMs = System.currentTimeMillis()

        val scopeJob = Job()
        val scope = CoroutineScope(Dispatchers.Default + scopeJob)

        val configJob = scope.launch {
            emitter.onNext(UpdateGraphicConfig(showHeader = false))
            emitter.onNext(ShowCustomStreamState(message = "", color = null))
            awaitCancellation()
        }

        val viewJob = scope.launch {
            try {
                // Time the last updateView RETURNED, seeded with the synchronous seed frame's.
                // The loop waits on it (awaitRenderSlot) before every render, so no frame
                // is ever dropped by the SDK's 1 Hz limit: an unguarded colour frame lost
                // the configured colour for the ride, and an unguarded ALERTING frame
                // right after the last countdown tick left the field on "SOS 1s".
                var lastRenderMs = seedRenderMs
                // Track config-driven idle colour in its own StateFlow so the IDLE branch
                // can suspend on `merge(uiState, colorFlow)` instead of polling every 5 s.
                // Previous code did `withTimeoutOrNull(5_000L) { uiState.first { ≠ IDLE } }`
                // — woke the coroutine every 5 s for the whole ride just to redraw on a
                // colour change. With the merge below, a colour change in config produces
                // its own emission and the IDLE branch redraws exactly when it needs to.
                val colorFlow = MutableStateFlow(0xFF1B5E20.toInt())
                launch {
                    configManager.loadConfigFlow().collect { c -> colorFlow.value = c.sosFieldColor }
                }
                while (true) {
                    // Hold BEFORE reading state, so the frame painted after the hold is
                    // the current one (steady countdown ticks are ≥1 s apart: no wait).
                    awaitRenderSlot(lastRenderMs)
                    val state = EmergencyManager.uiState.value
                    when (state.status) {
                        EmergencyStatus.IDLE -> {
                            val renderedColor = colorFlow.value
                            emitter.updateView(buildView(
                                context, config, renderedColor,
                                context.getString(R.string.sos_safe),
                                context.getString(R.string.sos_field_tap_sos),
                            ))
                            lastRenderMs = System.currentTimeMillis()
                            // Suspend until EITHER the emergency state changes OR the
                            // configured idle colour changes — no timeout-based wakeups.
                            // `filter { it != snapshot }` makes the wait race-free: a state
                            // transition that already happened between rendering and the
                            // subscribe call still triggers an immediate wake-up (StateFlow
                            // emits the current value on subscription, which the predicate
                            // catches when it differs from our snapshot). Without this guard
                            // a `drop(1)`-based variant could leave the IDLE branch stuck
                            // displaying SAFE while the countdown is already running.
                            merge(
                                EmergencyManager.uiState.filter { it != state }.map { Unit },
                                colorFlow.filter { it != renderedColor }.map { Unit },
                            ).first()
                        }
                        EmergencyStatus.COUNTDOWN -> {
                            val secs = state.countdownRemaining()
                            emitter.updateView(buildView(
                                context, config, COLOR_COUNTDOWN,
                                context.getString(R.string.sos_countdown, secs),
                                context.getString(R.string.sos_tap_cancel),
                            ))
                            lastRenderMs = System.currentTimeMillis()
                            kotlinx.coroutines.delay(1_000L)
                        }
                        EmergencyStatus.ALERTING -> {
                            emitter.updateView(buildView(
                                context, config, COLOR_ALERTING,
                                context.getString(R.string.sos_alerting),
                                clickable = false,
                            ))
                            lastRenderMs = System.currentTimeMillis()
                            EmergencyManager.uiState.first { it.status != EmergencyStatus.ALERTING }
                        }
                    }
                }
            } catch (_: CancellationException) {
                // normal
            } catch (e: Exception) {
                Timber.e(e, "SOSDataType error: ${e.message}")
            }
        }

        emitter.setCancellable {
            configJob.cancel()
            viewJob.cancel()
            scope.cancel()
            scopeJob.cancel()
        }
    }
}
