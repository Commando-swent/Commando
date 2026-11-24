package com.commando.app.ui.chat

// AI assistance: OpenAI Codex. Preview fixtures never enter production repository wiring.
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.commando.app.model.*
import com.commando.app.ui.theme.SampleAppTheme
import java.time.Instant

/** Reproducible preview data, also used by the renderer's JVM screenshot checks. */
internal fun chatPreviewState(
    status: OrderStatus = OrderStatus.ACCEPTED,
    empty: Boolean = false,
): OrderChatUiState {
  val time = Instant.parse("2030-01-01T16:30:00Z")
  val trip =
      Trip(
          "preview-trip",
          "owner",
          TripLocations.stores.first(),
          time,
          TripLocations.handoffPoints.first(),
          TripStatus.IN_PROGRESS,
          time,
          time,
      )
  val order =
      Order(
          "order % 雪",
          trip.id,
          "requester",
          listOf(OrderItem("milk", "Milk", 220, 2)),
          150,
          status,
          time,
      )
  val messages =
      if (empty) emptyList()
      else
          listOf(
              ChatMessage("incoming", "owner", "I’m on my way to the handoff.", time),
              ChatMessage("outgoing", "requester", "Great, see you there!", time.plusSeconds(60)),
          )
  return OrderChatUiState(
      ChatContext(order, trip, "requester"),
      OrderChat(status, messages),
      loading = false,
  )
}

@Preview(name = "Chat · Conversation", widthDp = 412, heightDp = 917)
@Composable
private fun ChatConversationPreview() = ChatPreview(chatPreviewState())

@Preview(name = "Chat · Empty", widthDp = 412, heightDp = 917)
@Composable
private fun ChatEmptyPreview() = ChatPreview(chatPreviewState(empty = true))

@Preview(name = "Chat · Read only", widthDp = 360, heightDp = 640)
@Composable
private fun ChatClosedPreview() = ChatPreview(chatPreviewState(OrderStatus.DELIVERED))

@Composable
private fun ChatPreview(state: OrderChatUiState) {
  SampleAppTheme { OrderChatScreen(state, {}, {}, {}, {}) }
}
