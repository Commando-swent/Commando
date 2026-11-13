package com.android.sample.ui.navigation

// AI assistance: OpenAI Codex.
import androidx.test.platform.app.InstrumentationRegistry
import com.android.sample.data.repository.productSearchTerms
import com.android.sample.model.Product
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreSettings
import com.google.firebase.firestore.MemoryCacheSettings
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import org.json.JSONObject
import org.junit.Assert.assertTrue

/**
 * Isolated named client; all SDK calls target the explicitly supplied emulator, never production.
 */
internal data class OrderJourneyFirebaseClient(
    val app: FirebaseApp,
    val auth: FirebaseAuth,
    val firestore: FirebaseFirestore,
)

/** Shares the persistent-order journeys' emulator configuration and disables disk-cache reuse. */
/** [name] lets a test reopen the same app, whose signed-in user Firebase restores from disk. */
internal fun orderJourneyClient(
    host: String,
    name: String = "orders-${UUID.randomUUID()}",
): OrderJourneyFirebaseClient {
  val app =
      FirebaseApp.initializeApp(
          InstrumentationRegistry.getInstrumentation().targetContext,
          FirebaseOptions.Builder()
              .setProjectId(ORDER_JOURNEY_PROJECT)
              .setApplicationId("1:123456789:android:orders-emulator")
              .setApiKey("fake-api-key")
              .build(),
          name,
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
private const val CURRENCY_SCALE = 2

/** Admin access is restricted to importer fixtures on the explicitly opted-in demo emulator. */
internal fun catalogueFixture(host: String, storeId: String, id: String, product: Product?) {
  val url =
      URL(
          "http://$host:$ORDER_JOURNEY_FIRESTORE_PORT/v1/projects/$ORDER_JOURNEY_PROJECT/databases/(default)/documents/stores/$storeId/products/$id"
      )
  val connection = url.openConnection() as HttpURLConnection
  try {
    connection.requestMethod = if (product == null) "DELETE" else "PATCH"
    connection.setRequestProperty("Authorization", "Bearer owner")
    connection.connectTimeout = ORDER_JOURNEY_WAIT_MILLIS.toInt()
    connection.readTimeout = ORDER_JOURNEY_WAIT_MILLIS.toInt()
    if (product != null) {
      connection.doOutput = true
      connection.setRequestProperty("Content-Type", "application/json")
      val fields =
          JSONObject()
              .put("id", JSONObject().put("stringValue", product.id))
              .put("name", JSONObject().put("stringValue", product.name))
              .put(
                  "price",
                  JSONObject()
                      .put(
                          "doubleValue",
                          product.priceCents
                              .toBigDecimal()
                              .movePointLeft(CURRENCY_SCALE)
                              .toDouble(),
                      ),
              )
              .put("removed_at", JSONObject().put("nullValue", JSONObject.NULL))
              .put(
                  "search_prefixes",
                  JSONObject()
                      .put(
                          "mapValue",
                          JSONObject()
                              .put(
                                  "fields",
                                  JSONObject().apply {
                                    requireNotNull(productSearchTerms(product.name)).forEach {
                                      put(it, JSONObject().put("booleanValue", true))
                                    }
                                  },
                              ),
                      ),
              )
      connection.outputStream.use {
        it.write(JSONObject().put("fields", fields).toString().toByteArray())
      }
    }
    assertTrue(
        "Catalogue fixture request failed: ${connection.responseCode}",
        connection.responseCode in 200..299,
    )
  } finally {
    connection.disconnect()
  }
}
