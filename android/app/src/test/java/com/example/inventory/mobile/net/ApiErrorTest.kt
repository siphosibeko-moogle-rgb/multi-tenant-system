package com.example.inventory.mobile.net

import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response

/**
 * RFC 9457 problem bodies, turned into something a shop owner can read.
 *
 * The requirement is negative as much as positive: not a raw JSON dump, and not
 * "Error 409". Several assertions below check what the message must NOT contain,
 * because a mapper that passes the body through unchanged would satisfy any
 * test that only checked the message was non-empty.
 */
class ApiErrorTest {

    private fun errorResponse(code: Int, body: String): Response<Unit> =
        Response.error(code, body.toResponseBody("application/problem+json".toMediaType()))

    @Test
    fun `an oversell 409 shows the real numbers from the problem body`() {
        // Exactly what the backend sends — M2's InsufficientStockHttpTest asserts
        // these three fields are present and correct, and M3 promoted the schema
        // so the client gets them typed rather than parsing by hand.
        val error = errorResponse(
            409,
            """
            {"type":"https://api.example.com/problems/insufficient-stock",
             "title":"Insufficient stock","status":409,
             "detail":"The requested quantity is not available",
             "productId":"3f2504e0-4f89-11d3-9a0c-0305e82c3301",
             "requested":5,"available":3}
            """.trimIndent(),
        ).toApiError()

        assertEquals("Not enough stock to complete this sale.", error.message)
        assertNotNull("the numbers are the actionable part", error.detail)
        assertTrue(
            "must state what was asked for: was <${error.detail}>",
            error.detail!!.contains("5"),
        )
        assertTrue(
            "and what is actually there: was <${error.detail}>",
            error.detail.contains("3"),
        )
    }

    @Test
    fun `no message is a raw JSON dump`() {
        val bodies = mapOf(
            401 to """{"title":"Authentication failed","status":401,"detail":"Invalid email or password"}""",
            403 to """{"title":"Forbidden","status":403,"detail":"Your role does not permit this action"}""",
            404 to """{"title":"Not found","status":404,"detail":"No such product"}""",
            412 to """{"title":"Precondition failed","status":412,"detail":"modified by someone else"}""",
            429 to """{"title":"Too many requests","status":429,"detail":"Too many login attempts"}""",
            500 to """{"title":"Internal server error","status":500,"detail":"The request could not be processed"}""",
        )

        bodies.forEach { (code, body) ->
            val error = errorResponse(code, body).toApiError()

            assertFalse(
                "$code: message must not contain JSON punctuation — was <${error.message}>",
                error.message.contains("{") || error.message.contains("\""),
            )
            assertFalse(
                "$code: message must not be a bare status code — was <${error.message}>",
                error.message.matches(Regex(""".*\bError \d{3}\b.*""")),
            )
            assertTrue(
                "$code: message should read as a sentence — was <${error.message}>",
                error.message.length > 10 && error.message.first().isUpperCase(),
            )
        }
    }

    @Test
    fun `a 500 does not leak the server's internal wording`() {
        // The backend deliberately sends a fixed, vague detail on 500 so that SQL
        // and table names cannot escape. The client should not undo that by
        // preferring the server's text here.
        val error = errorResponse(
            500,
            """{"title":"Internal server error","status":500,"detail":"could not extract ResultSet"}""",
        ).toApiError()

        assertFalse(
            "a database phrase must not reach the shop floor — was <${error.message}>",
            error.message.contains("ResultSet"),
        )
        assertEquals("The server had a problem. Try again in a moment.", error.message)
    }

    @Test
    fun `a 409 that is not an oversell still reads sensibly`() {
        val error = errorResponse(
            409,
            """{"title":"Conflict","status":409,"detail":"A business with that slug already exists"}""",
        ).toApiError()

        // Here the server's wording IS the useful part, so it is shown verbatim.
        assertEquals("A business with that slug already exists", error.message)
    }

    @Test
    fun `a malformed or empty body still produces a message rather than throwing`() {
        // An error path that throws while reporting an error is the worst
        // possible failure: the user sees a crash instead of the problem.
        listOf("", "not json at all", "{\"unclosed\":", "<html>502 Bad Gateway</html>")
            .forEach { body ->
                val error = errorResponse(502, body).toApiError()
                assertTrue(
                    "body <$body> should still yield a message",
                    error.message.isNotBlank(),
                )
                assertFalse(error.message.contains("<html>"))
            }
    }

    @Test
    fun `network failures read as connection problems, not as server errors`() {
        // "Killing the backend produces a readable error in the app, not a
        // crash" is an M3 acceptance criterion, and this is the mapping behind
        // it.
        assertTrue(
            ApiError.fromNetworkFailure(UnknownHostException("10.0.2.2"))
                .message.contains("reach the server"),
        )
        assertTrue(
            ApiError.fromNetworkFailure(SocketTimeoutException()).message.contains("too long"),
        )
        assertTrue(
            ApiError.fromNetworkFailure(IOException("connection refused"))
                .message.contains("reach the server"),
        )
        // And the host name must not be shown to a shop owner.
        assertFalse(
            ApiError.fromNetworkFailure(UnknownHostException("10.0.2.2"))
                .message.contains("10.0.2.2"),
        )
    }
}
