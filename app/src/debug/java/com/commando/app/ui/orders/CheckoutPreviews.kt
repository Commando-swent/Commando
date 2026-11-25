package com.commando.app.ui.orders

// AI assistance: Claude Code (checkout previews, #419). Debug-only: run a state on an emulator from
// Android Studio while checkout is not yet reachable from Order Details.
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.commando.app.model.Location
import com.commando.app.model.Order
import com.commando.app.model.OrderItem
import com.commando.app.model.OrderStatus
import com.commando.app.model.Payment
import com.commando.app.model.PaymentMethod
import com.commando.app.model.PaymentStatus
import com.commando.app.model.Trip
import com.commando.app.model.TripStatus
import com.commando.app.model.totalCents
import com.commando.app.ui.theme.SampleAppTheme
import java.time.Instant

@Preview(name = "Checkout · Choose method", widthDp = 412, heightDp = 917)
@Composable
private fun CheckoutChoosePreview() = CheckoutPreview(null)

@Preview(name = "Checkout · Pending", widthDp = 412, heightDp = 917)
@Composable
private fun CheckoutPendingPreview() = CheckoutPreview(PaymentStatus.PENDING)

@Preview(name = "Checkout · Uncertain", widthDp = 412, heightDp = 917)
@Composable
private fun CheckoutUncertainPreview() = CheckoutPreview(PaymentStatus.UNCERTAIN)

@Preview(name = "Checkout · Unsuccessful", widthDp = 412, heightDp = 917)
@Composable
private fun CheckoutFailedPreview() = CheckoutPreview(PaymentStatus.FAILED)

@Preview(name = "Checkout · Confirmed", widthDp = 412, heightDp = 917)
@Composable
private fun CheckoutConfirmedPreview() = CheckoutPreview(PaymentStatus.CONFIRMED)

@Composable
private fun CheckoutPreview(status: PaymentStatus?) {
  val time = Instant.parse("2026-10-13T16:30:00Z")
  val trip =
      Trip(
          "trip",
          "commando",
          Location("Migros EPFL", 46.52, 6.57),
          time,
          Location("Rolex Learning Center", 46.52, 6.57),
          TripStatus.PUBLISHED,
          time,
          time,
      )
  val order =
      Order(
          "order",
          trip.id,
          "requester",
          listOf(OrderItem("milk", "Milk", 230, 3)),
          150,
          OrderStatus.ACCEPTED,
          time,
      )
  val payment = status?.let {
    Payment(order.id, order.id, order.requesterId, order.totalCents, PaymentMethod.TWINT, it)
  }
  SampleAppTheme { CheckoutScreen(CheckoutUiState.Content(order, trip, payment), {}, {}, {}, {}) }
}
