package com.android.sample.data.repository

// AI assistance: Claude Code.
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.FirebaseFirestoreException.Code
import java.io.IOException
import java.net.UnknownHostException
import java.util.concurrent.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class FirestoreFailureTest {

  @Test
  fun toFirestoreFailure_classifiesEveryFirestoreCode() {
    val expected =
        mapOf(
            Code.PERMISSION_DENIED to FirestoreFailure.PERMISSION_DENIED,
            Code.UNAUTHENTICATED to FirestoreFailure.UNAUTHENTICATED,
            Code.UNAVAILABLE to FirestoreFailure.NETWORK_UNAVAILABLE,
            Code.DEADLINE_EXCEEDED to FirestoreFailure.NETWORK_UNAVAILABLE,
            Code.NOT_FOUND to FirestoreFailure.NOT_FOUND,
            Code.INVALID_ARGUMENT to FirestoreFailure.INVALID_DATA,
            Code.OUT_OF_RANGE to FirestoreFailure.INVALID_DATA,
            Code.DATA_LOSS to FirestoreFailure.INVALID_DATA,
        )

    // Firestore never throws OK; every other unlisted code, CANCELLED included, is UNKNOWN.
    (Code.entries - Code.OK).forEach { code ->
      assertEquals(
          "Unexpected category for $code",
          expected[code] ?: FirestoreFailure.UNKNOWN,
          firebaseException(code).toFirestoreFailure(),
      )
    }
  }

  @Test
  fun toFirestoreFailure_classifiesNonFirestoreExceptions() {
    val cases =
        listOf(
            IOException("offline") to FirestoreFailure.NETWORK_UNAVAILABLE,
            UnknownHostException("firestore.googleapis.com") to
                FirestoreFailure.NETWORK_UNAVAILABLE,
            IllegalStateException("unexpected") to FirestoreFailure.UNKNOWN,
            // Causes are not unwrapped: only the thrown exception is classified.
            RuntimeException(firebaseException(Code.PERMISSION_DENIED)) to FirestoreFailure.UNKNOWN,
            IllegalStateException(IOException("offline")) to FirestoreFailure.UNKNOWN,
        )

    cases.forEach { (exception, expected) ->
      assertEquals("Unexpected category for $exception", expected, exception.toFirestoreFailure())
    }
  }

  @Test
  fun runFirestoreOperation_returnsTheOperationResultOnSuccess() = runTest {
    val result =
        runFirestoreOperation(onFailure = { fail("Unexpected failure $it") }, operation = { "ok" })

    assertEquals("ok", result)
  }

  @Test
  fun runFirestoreOperation_passesTheFailureCategoryToOnFailure() = runTest {
    val result =
        runFirestoreOperation(
            onFailure = { it },
            operation = { throw firebaseException(Code.UNAUTHENTICATED) },
        )

    assertEquals(FirestoreFailure.UNAUTHENTICATED, result)
  }

  @Test
  fun runFirestoreOperation_reportsFirestoreCancelledCodeAsUnknown() = runTest {
    // A CANCELLED Firestore status is a failed request, not coroutine cancellation.
    val result =
        runFirestoreOperation(
            onFailure = { it },
            operation = { throw firebaseException(Code.CANCELLED) },
        )

    assertEquals(FirestoreFailure.UNKNOWN, result)
  }

  @Test
  fun runFirestoreOperation_rethrowsCancellationWithoutCallingOnFailure() = runTest {
    listOf(CancellationException("cancelled"), object : CancellationException("subclass") {})
        .forEach { cancellation ->
          try {
            runFirestoreOperation<Unit>(
                onFailure = { fail("Cancellation mapped to $it") },
                operation = { throw cancellation },
            )
            fail("Expected $cancellation to be rethrown")
          } catch (rethrown: CancellationException) {
            assertSame(cancellation, rethrown)
          }
        }
  }

  @Test
  fun runFirestoreOperation_letsErrorsPropagate() = runTest {
    val error = NotImplementedError("not an Exception")

    try {
      runFirestoreOperation<Unit>(
          onFailure = { fail("Error mapped to $it") },
          operation = { throw error },
      )
      fail("Expected the Error to propagate")
    } catch (propagated: NotImplementedError) {
      assertSame(error, propagated)
    }
  }

  private fun firebaseException(code: Code) = FirebaseFirestoreException(code.name, code)
}
