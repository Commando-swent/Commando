package com.commando.app.ui.orders

// AI assistance: OpenAI Codex.
import androidx.lifecycle.SavedStateHandle
import com.commando.app.model.OrderDecision
import com.commando.app.model.OrderTerms
import com.commando.app.model.isValidChatText
import java.time.Instant

/** Only Bundle-safe primitives; scoped to the original account and order, never auto-submitted. */
internal data class PendingNegotiation(
    val id: String,
    val revision: Int,
    val terms: OrderTerms? = null,
    val decision: OrderDecision? = null,
) {
  fun save(handle: SavedStateHandle, userId: String, orderId: String) {
    handle[KEY] =
        arrayListOf(
            userId,
            orderId,
            id,
            revision.toString(),
            terms?.feeCents?.toString().orEmpty(),
            terms?.location.orEmpty(),
            terms?.at?.toString().orEmpty(),
            decision?.name.orEmpty(),
        )
  }

  companion object {
    const val KEY = "pending_order_negotiation"

    fun restore(handle: SavedStateHandle, userId: String, orderId: String): PendingNegotiation? {
      val fields = handle.get<ArrayList<String>>(KEY) ?: return null
      val restored = runCatching {
        require(
            fields.size == 8 && userId.isNotBlank() && fields[0] == userId && fields[1] == orderId
        )
        val revision = fields[3].toInt()
        require(fields[2].isNotBlank() && revision >= 0)
        if (fields[7].isNotEmpty()) {
          require(fields.subList(4, 7).all { it.isEmpty() })
          PendingNegotiation(fields[2], revision, decision = OrderDecision.valueOf(fields[7]))
        } else {
          val terms = OrderTerms(fields[4].toInt(), fields[5], Instant.parse(fields[6]))
          require(terms.feeCents >= 0 && terms.location.isValidChatText())
          PendingNegotiation(fields[2], revision, terms)
        }
      }
          .getOrNull()
      if (restored == null) handle.remove<ArrayList<String>>(KEY)
      return restored
    }
  }
}
