package com.android.sample.data.repository

// AI assistance: OpenAI Codex.
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.FirebaseFirestoreException.Code
import java.io.IOException
import java.net.UnknownHostException
import org.junit.Assert.assertEquals
import org.junit.Test

class OrderFirestoreFailureTest {
  @Test
  fun everyFirestoreCodePreservesTheExistingOrderErrorContract() {
    val expected =
        mapOf(
            Code.UNAUTHENTICATED to OrderError.NotAuthenticated,
            Code.PERMISSION_DENIED to OrderError.PermissionDenied,
            Code.NOT_FOUND to OrderError.NotFound,
            Code.INVALID_ARGUMENT to OrderError.InvalidData,
            Code.OUT_OF_RANGE to OrderError.InvalidData,
            Code.DATA_LOSS to OrderError.InvalidData,
        )
    // OK is not an exception; a Firestore CANCELLED status is not coroutine cancellation.
    for (code in Code.entries - Code.OK) {
      assertEquals(
          code.name,
          expected[code] ?: OrderError.Unknown,
          FirebaseFirestoreException("test", code).toOrderError(),
      )
    }
  }

  @Test
  fun networkAndWrappedErrorsKeepOrderSpecificUnknownInsteadOfProductOrTripErrors() {
    for (exception in
        listOf(
            IOException("offline"),
            UnknownHostException("offline"),
            IllegalStateException("unexpected"),
            RuntimeException(FirebaseFirestoreException("wrapped", Code.PERMISSION_DENIED)),
        )) {
      assertEquals(exception.toString(), OrderError.Unknown, exception.toOrderError())
    }
  }
}
