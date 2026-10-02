package com.enderthor.kSafe.datatype

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.enderthor.kSafe.R
import com.enderthor.kSafe.activity.FieldTapReceiver
import com.enderthor.kSafe.data.FUEL_BOTTLE_DRAWABLE
import com.enderthor.kSafe.data.FUEL_GEL_DRAWABLE
import com.enderthor.kSafe.data.KSafeConfig
import com.enderthor.kSafe.extension.KSafeExtension
import com.enderthor.kSafe.extension.managers.CarbStatus
import com.enderthor.kSafe.extension.managers.ConfigurationManager
import com.enderthor.kSafe.extension.managers.HydrationStatus
import com.enderthor.kSafe.extension.util.SweatConfidence
import com.enderthor.kSafe.extension.util.safeTake
import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.extension.DataTypeImpl
import io.hammerhead.karooext.internal.ViewEmitter
import io.hammerhead.karooext.models.ShowCustomStreamState
import io.hammerhead.karooext.models.UpdateGraphicConfig
import io.hammerhead.karooext.models.ViewConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Fuel Panel — hydration and carbs side by side in ONE field, built for one of three
 * full-width rows on a Karoo page (~478×216 px on a Karoo 2). Each half is a big tap target:
 * its background is that tracker's status colour (the Hydration / Carb Status bands), its
 * big line the deficit and its small line what a tap logs — Drink 1 on the left, Carb 1 on
 * the right. Render rules live in [fuelPanelHalf].
 *
 * A tap on a half IS a Drink 1 / Carb 1 tap: it sends the same broadcast as those fields, so
 * it runs the same log ⇄ undo toggle (KSafeExtension.handleHydrationLogTap /
 * handleCarbLogTap) and drives the same slot-1 Hydration/CarbLogState flash — mirrored on the
 * Drink 1 / Carb 1 fields too if they are also on screen.
 */
class FuelPanelDataType(
    datatype: String,
    private val context: Context,
    // Not stored: like CombinedFuelLogDataType, this field only needs DataStore + its tap
    // PendingIntents. Kept for constructor symmetry with the other KSafe data types.
    @Suppress("UNUSED_PARAMETER") karooSystem: KarooSystemService,
) : DataTypeImpl("ksafe", datatype) {

    private val configManager = ConfigurationManager(context)

    // Own requestCodes (140 / 141, clear of the 101-131 other fields use) around the SAME
    // Intent as the Drink 1 / Carb 1 fields, so either PendingIntent delivers the same tap.
    // Cached: (action, requestCode, package) never change — see CarbLogDataType.
    @Volatile private var cachedHydPi: PendingIntent? = null
    @Volatile private var cachedCarbPi: PendingIntent? = null

    private fun hydPendingIntent(context: Context): PendingIntent =
        cachedHydPi ?: tapIntent(context, 140, FieldTapReceiver.ACTION_HYDRATION_LOG_1).also { cachedHydPi = it }

    private fun carbPendingIntent(context: Context): PendingIntent =
        cachedCarbPi ?: tapIntent(context, 141, FieldTapReceiver.ACTION_CARB_LOG_1).also { cachedCarbPi = it }

    private fun tapIntent(context: Context, requestCode: Int, action: String): PendingIntent =
        PendingIntent.getBroadcast(
            context, requestCode,
            Intent(action).setPackage(context.packageName),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

    /** "💧 Sip 70ml": the slot's emoji icon, label and per-tap amount. A gel / bottle drawable
     *  icon is drawn by [iconRes] instead, so the sentinel never reaches the text. */
    private fun slotHint(icon: String, label: String, fallback: String, amount: Int, unit: String): String {
        val name = label.safeTake(7).ifBlank { fallback }
        val emoji = if (icon.isBlank() || icon == FUEL_GEL_DRAWABLE || icon == FUEL_BOTTLE_DRAWABLE) "" else "$icon "
        return "$emoji$name $amount$unit"
    }

    // Always the white drawables: both halves are painted, never the AUTO day-mode white field.
    private fun iconRes(icon: String): Int = when (icon) {
        FUEL_GEL_DRAWABLE -> R.drawable.ic_fuel_gel
        FUEL_BOTTLE_DRAWABLE -> R.drawable.ic_fuel_bottle
        else -> 0
    }

    private fun frameFor(
        preview: Boolean,
        c: KSafeConfig,
        hyd: HydrationStatus?,
        carb: CarbStatus?,
        hydLog: HydrationLogState,
        carbLog: CarbLogState,
    ): Frame {
        val offText = context.getString(R.string.fueling_field_off)
        val tapUndoText = context.getString(R.string.field_state_tap_undo)
        return Frame(
            hyd = fuelPanelHalf(
                preview = preview,
                enabled = c.isActive && c.hydrationTrackerEnabled,
                flash = when (hydLog) {
                    is HydrationLogState.LOGGED -> PanelFlash.Logged(hydLog.ml)
                    is HydrationLogState.UNDONE -> PanelFlash.Undone(hydLog.ml)
                    else -> null
                },
                deficit = hyd?.deficitMl,
                threshold = hyd?.deficitThresholdMl ?: 0,
                unit = "ml",
                approx = hyd?.estimateConfidence == SweatConfidence.LOW,
                slotHint = slotHint(c.drink1Icon, c.drink1Label, "Drink 1", c.drink1Ml, "ml"),
                slotIconRes = iconRes(c.drink1Icon),
                offText = offText,
                tapUndoText = tapUndoText,
            ),
            carb = fuelPanelHalf(
                preview = preview,
                enabled = c.isActive && c.carbsTrackerEnabled,
                flash = when (carbLog) {
                    is CarbLogState.LOGGED -> PanelFlash.Logged(carbLog.grams)
                    is CarbLogState.UNDONE -> PanelFlash.Undone(carbLog.grams)
                    else -> null
                },
                deficit = carb?.deficitG,
                threshold = carb?.deficitThresholdG ?: 0,
                unit = "g",
                approx = false,
                slotHint = slotHint(c.carb1Icon, c.carb1Label, "Carb 1", c.carb1Grams, "g"),
                slotIconRes = iconRes(c.carb1Icon),
                offText = offText,
                tapUndoText = tapUndoText,
            ),
        )
    }

    private fun buildView(context: Context, viewConfig: ViewConfig, frame: Frame): RemoteViews =
        RemoteViews(context.packageName, R.layout.field_fuel_panel).apply {
            applyHalf(R.id.fuel_panel_hyd, R.id.fuel_panel_hyd_main, R.id.fuel_panel_hyd_hint, frame.hyd)
            applyHalf(R.id.fuel_panel_carb, R.id.fuel_panel_carb_main, R.id.fuel_panel_carb_hint, frame.carb)
            // One tap zone per half. Every state renders this same layout, so the host never
            // re-attaches the click handlers mid-ride (the structural-swap tap loss
            // CarbLogDataType's field_tap_wrapper note describes). They also stay attached
            // while a half is OFF — the tap handlers ignore taps for a disabled tracker.
            if (!viewConfig.preview) {
                setOnClickPendingIntent(R.id.fuel_panel_hyd, hydPendingIntent(context))
                setOnClickPendingIntent(R.id.fuel_panel_carb, carbPendingIntent(context))
            }
        }

    private fun RemoteViews.applyHalf(containerId: Int, mainId: Int, hintId: Int, half: PanelHalf) {
        setInt(containerId, "setBackgroundColor", half.bgColor)
        setTextViewText(mainId, half.main)
        setTextViewText(hintId, half.hint)
        // All four set explicitly so a flash (no icon) clears the idle icon, and back.
        setTextViewCompoundDrawables(hintId, half.iconRes, 0, 0, 0)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun startView(context: Context, config: ViewConfig, emitter: ViewEmitter) {
        val scopeJob = Job()
        val scope = CoroutineScope(Dispatchers.Default + scopeJob)

        val configJob = scope.launch {
            emitter.onNext(UpdateGraphicConfig(showHeader = false))
            emitter.onNext(ShowCustomStreamState(message = "", color = null))
            awaitCancellation()
        }

        val viewJob = scope.launch {
            try {
                // Follow each published tracker reference rather than the status fields'
                // filterNotNull().first(): either tracker may be absent (extension booting,
                // feature never enabled) and a combine waiting on BOTH would leave the whole
                // panel blank. A missing tracker is just a null status → `---` / OFF.
                // Frame + distinctUntilChanged: see CarbLogDataType — unrelated config edits
                // and status republishes that change nothing visible skip the RemoteViews IPC.
                combine(
                    KSafeExtension.hydrationTrackerFlow.flatMapLatest { it?.statusFlow ?: flowOf<HydrationStatus?>(null) },
                    KSafeExtension.carbsTrackerFlow.flatMapLatest { it?.statusFlow ?: flowOf<CarbStatus?>(null) },
                    HydrationLogState.flowForSlot(1),
                    CarbLogState.flowForSlot(1),
                    configManager.loadConfigFlow(),
                ) { hyd, carb, hydLog, carbLog, ksafeConfig ->
                    frameFor(config.preview, ksafeConfig, hyd, carb, hydLog, carbLog)
                }.distinctUntilChanged().collect { frame ->
                    emitter.updateView(buildView(context, config, frame))
                }
            } catch (_: CancellationException) {
                // normal
            } catch (e: Exception) {
                Timber.e(e, "FuelPanelDataType error: ${e.message}")
            }
        }

        emitter.setCancellable {
            configJob.cancel()
            viewJob.cancel()
            scope.cancel()
            scopeJob.cancel()
        }
    }

    /** Dedup snapshot of everything the panel renders — see [CarbLogDataType.Frame]. */
    private data class Frame(val hyd: PanelHalf, val carb: PanelHalf)
}
