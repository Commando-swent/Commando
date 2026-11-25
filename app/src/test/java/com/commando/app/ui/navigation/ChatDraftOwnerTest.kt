package com.commando.app.ui.navigation

// AI assistance: OpenAI Codex.
import android.os.Bundle
import androidx.navigation.NavBackStackEntry
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Draft ownership must not leak between orders or unrelated destinations. */
@RunWith(RobolectricTestRunner::class)
class ChatDraftOwnerTest {
  private val orderId = "selected-order"
  private val chat = mockk<NavBackStackEntry>()

  private fun previous(route: String, arguments: Bundle?): NavBackStackEntry =
      mockk<NavBackStackEntry>(relaxed = true).also {
        every { it.destination.route } returns route
        every { it.arguments } returns arguments
      }

  private fun arguments(id: String) =
      Bundle().apply { putString(CommandoScreens.ORDER_ID_ARGUMENT, id) }

  @Test
  fun matchingDetailsRetainTheDraftAcrossChatEntries() {
    val details = previous(CommandoScreens.OrderDetails.route, arguments(orderId))
    assertSame(details, chatDraftOwner(chat, details, orderId))
  }

  @Test
  fun anotherOrdersDetailsCannotOwnThisDraft() {
    val details = previous(CommandoScreens.OrderDetails.route, arguments("another-order"))
    assertSame(chat, chatDraftOwner(chat, details, orderId))
  }

  @Test
  fun unrelatedDestinationCannotOwnTheDraftEvenWithTheSameOrderArgument() {
    val unrelated = previous(CommandoScreens.OrderChat.route, arguments(orderId))
    assertSame(chat, chatDraftOwner(chat, unrelated, orderId))
  }

  @Test
  fun directEntryKeepsItsOwnDraft() {
    assertSame(chat, chatDraftOwner(chat, null, orderId))
  }

  @Test
  fun detailsWithoutArgumentsCannotOwnTheDraft() {
    val details = previous(CommandoScreens.OrderDetails.route, null)
    assertSame(chat, chatDraftOwner(chat, details, orderId))
  }
}
