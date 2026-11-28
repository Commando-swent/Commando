package com.commando.app.ui.orders

// AI assistance: OpenAI Codex.
import android.os.Bundle
import android.os.Parcel
import androidx.lifecycle.SavedStateHandle
import com.commando.app.model.OrderDecision
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PendingNegotiationTest {
  private val user = NegotiationFixture.trip.ownerId
  private val order = NegotiationFixture.order.id
  private val handle = SavedStateHandle()

  @Test
  fun counterofferRoundTripsThroughAnAndroidBundleWithoutLosingInstantPrecision() {
    val original =
        PendingNegotiation(
            "operation",
            3,
            NegotiationFixture.terms.copy(at = NegotiationFixture.terms.at.plusNanos(123)),
        )
    original.save(handle, user, order)
    val bundle =
        Bundle().apply {
          putStringArrayList(PendingNegotiation.KEY, handle[PendingNegotiation.KEY])
        }
    val parcel = Parcel.obtain()
    try {
      parcel.writeBundle(bundle)
      parcel.setDataPosition(0)
      val restored = requireNotNull(parcel.readBundle(javaClass.classLoader))
      val recreated =
          SavedStateHandle(
              mapOf(PendingNegotiation.KEY to restored.getStringArrayList(PendingNegotiation.KEY))
          )
      assertEquals(original, PendingNegotiation.restore(recreated, user, order))
    } finally {
      parcel.recycle()
    }
  }

  @Test
  fun decisionRoundTripPreservesIdAndRevision() {
    val original = PendingNegotiation("operation", 2, decision = OrderDecision.REJECT)
    original.save(handle, user, order)
    assertEquals(original, PendingNegotiation.restore(handle, user, order))
  }

  @Test
  fun anotherAccountCannotRestorePrivateTerms() {
    PendingNegotiation("operation", 1, NegotiationFixture.terms).save(handle, user, order)
    assertNull(PendingNegotiation.restore(handle, "other", order))
    assertNull(handle.get<Any>(PendingNegotiation.KEY))
  }

  @Test
  fun anotherOrderCannotRestoreThisOperation() {
    PendingNegotiation("operation", 1, NegotiationFixture.terms).save(handle, user, order)
    assertNull(PendingNegotiation.restore(handle, user, "other"))
  }

  @Test
  fun malformedSavedStateIsDroppedInsteadOfBeingSubmitted() {
    handle[PendingNegotiation.KEY] =
        arrayListOf(user, order, "operation", "-1", "", "", "", "ACCEPT")
    assertNull(PendingNegotiation.restore(handle, user, order))
    assertNull(handle.get<Any>(PendingNegotiation.KEY))
  }

  @Test
  fun missingSavedStateRestoresNothing() {
    assertNull(PendingNegotiation.restore(handle, user, order))
  }

  @Test
  fun damagedOperationPayloadsCannotBeReplayed() {
    val operation = PendingNegotiation("operation", 1, NegotiationFixture.terms)
    operation.save(handle, user, order)
    val valid = requireNotNull(handle.get<ArrayList<String>>(PendingNegotiation.KEY))
    val invalid =
        listOf(
            arrayListOf(),
            valid.toMutableList().apply { this[2] = "" },
            valid.toMutableList().apply { this[4] = "-1" },
            valid.toMutableList().apply { this[5] = "" },
            valid.toMutableList().apply { this[7] = OrderDecision.ACCEPT.name },
        )
    for (fields in invalid) {
      handle[PendingNegotiation.KEY] = ArrayList(fields)
      assertNull(PendingNegotiation.restore(handle, user, order))
      assertNull(handle.get<Any>(PendingNegotiation.KEY))
    }
    operation.save(handle, user, order)
    assertNull(PendingNegotiation.restore(handle, "", order))
  }
}
