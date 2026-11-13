package com.android.sample.ui.navigation

// AI assistance: OpenAI Codex.
import androidx.test.platform.app.InstrumentationRegistry
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreSettings
import com.google.firebase.firestore.MemoryCacheSettings
import java.util.UUID

/**
 * Isolated named client; all SDK calls target the explicitly supplied emulator, never production.
 */
internal data class OrderJourneyFirebaseClient(
    val app: FirebaseApp,
    val auth: FirebaseAuth,
    val firestore: FirebaseFirestore,
)

/** Shares the persistent-order journeys' emulator configuration and disables disk-cache reuse. */
internal fun orderJourneyClient(host: String): OrderJourneyFirebaseClient {
  val app =
      FirebaseApp.initializeApp(
          InstrumentationRegistry.getInstrumentation().targetContext,
          FirebaseOptions.Builder()
              .setProjectId(ORDER_JOURNEY_PROJECT)
              .setApplicationId("1:123456789:android:orders-emulator")
              .setApiKey("fake-api-key")
              .build(),
          "orders-${UUID.randomUUID()}",
      )
  val auth = FirebaseAuth.getInstance(app).apply { useEmulator(host, ORDER_JOURNEY_AUTH_PORT) }
  val firestore =
      FirebaseFirestore.getInstance(app).apply {
        firestoreSettings =
            FirebaseFirestoreSettings.Builder()
                .setLocalCacheSettings(MemoryCacheSettings.newBuilder().build())
                .build()
        useEmulator(host, ORDER_JOURNEY_FIRESTORE_PORT)
      }
  return OrderJourneyFirebaseClient(app, auth, firestore)
}

internal const val ORDER_JOURNEY_PROJECT = "demo-commando"
// Keep aligned with firebase.json, also used by the existing CI connectedCheck invocation.
internal const val ORDER_JOURNEY_AUTH_PORT = 9099
internal const val ORDER_JOURNEY_FIRESTORE_PORT = 8080

internal const val ORDER_JOURNEY_WAIT_MILLIS = 15_000L
