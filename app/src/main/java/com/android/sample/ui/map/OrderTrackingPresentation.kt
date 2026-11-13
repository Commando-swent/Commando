package com.android.sample.ui.map

// AI assistance: OpenAI Codex.
import androidx.annotation.StringRes
import com.android.sample.R
import com.android.sample.model.CommandoPosition
import com.android.sample.model.Location
import java.time.Duration
import java.time.Instant
import kotlin.math.ceil

/** Presentation shared by the status card, marker and camera; stale fixes fail closed. */
internal data class OrderTrackingPresentation(
    @param:StringRes val message: Int,
    val fix: CommandoPosition? = null,
    val ageSeconds: Int? = null,
    val accuracyMeters: Int? = null,
    val canRecenter: Boolean = false,
    val canRetry: Boolean = false,
    val loading: Boolean = false,
)

/** Derives display values from the original capture time, never from the last UI refresh. */
internal fun orderTrackingPresentation(
    state: OrderTrackingState,
    now: Instant,
): OrderTrackingPresentation =
    when (state) {
      OrderTrackingState.Loading ->
          OrderTrackingPresentation(R.string.order_tracking_loading, loading = true)
      OrderTrackingState.Unavailable ->
          OrderTrackingPresentation(R.string.order_tracking_error, canRetry = true)
      is OrderTrackingState.Position -> {
        val fix = state.fix?.takeIf { it.isFresh(now) }
        if (fix == null)
            OrderTrackingPresentation(R.string.order_tracking_unavailable, canRetry = true)
        else {
          val onMap =
              CAMPUS_MAP_PROJECTION.project(Location("", fix.latitude, fix.longitude)) != null
          OrderTrackingPresentation(
              message =
                  if (onMap) R.string.order_tracking_live else R.string.order_tracking_off_map,
              fix = fix,
              ageSeconds = Duration.between(fix.capturedAt, now).seconds.toInt(),
              accuracyMeters = ceil(fix.accuracyMeters).toInt(),
              canRecenter = onMap,
          )
        }
      }
    }
