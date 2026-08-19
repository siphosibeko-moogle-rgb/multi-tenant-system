package com.example.inventory.mobile.net

import com.example.inventory.api.models.InsufficientStockProblem
import com.example.inventory.api.models.Problem
import com.squareup.moshi.Moshi
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import retrofit2.Response

/**
 * An error, phrased for the person holding the phone.
 *
 * @param message what to show. A sentence a shop owner can act on — never a raw
 *                JSON body and never "Error 409", which tells them nothing about
 *                what to do next.
 * @param detail  optional second line, used for the specifics of an oversell.
 */
data class ApiError(val message: String, val detail: String? = null) {

    companion object {

        /**
         * Network-level failures, which have no HTTP status at all.
         *
         * "Killing the backend produces a readable error in the app, not a
         * crash" is an M3 acceptance criterion, and this is where that is
         * decided — an unreachable server surfaces here, not as an exception
         * escaping into a coroutine.
         */
        fun fromNetworkFailure(e: Throwable): ApiError = when (e) {
            is UnknownHostException -> ApiError(
                "Can't reach the server. Check the connection and try again."
            )
            is SocketTimeoutException -> ApiError(
                "The server took too long to answer. Try again."
            )
            is IOException -> ApiError(
                "Can't reach the server. Check the connection and try again."
            )
            else -> ApiError("Something went wrong. Try again.")
        }
    }
}

/**
 * Turns an unsuccessful response into a readable [ApiError], reading the RFC
 * 9457 problem body the backend sends for every error.
 *
 * The mapping is by status, because the wording a person needs depends on what
 * they can do about it, not on which handler produced it. `detail` from the
 * problem body is used when the server has said something specific and useful —
 * "A business with that slug already exists" is worth showing verbatim.
 */
fun <T> Response<T>.toApiError(moshi: Moshi = ProblemParsing.moshi): ApiError {
    val raw = errorBody()?.string().orEmpty()
    val problem = ProblemParsing.parse(raw, moshi)

    // The oversell case carries numbers, and showing them is the difference
    // between an error and an instruction. M2 proved the backend populates them
    // and M3 promoted the schema so the client gets them typed.
    // Still nullable, and deliberately so: a 409 from an endpoint that is not
    // moving stock is an ordinary Problem carrying none of these fields, and
    // parsing one yields null. What the tightened contract removed is the
    // per-FIELD checking — within an InsufficientStockProblem the three numbers
    // are now required, so a partial one is a malformed response, not a case to
    // handle.
    val insufficient = ProblemParsing.parseInsufficientStock(raw, moshi)
    if (code() == 409 && insufficient != null) {
        return ApiError(
            message = "Not enough stock to complete this sale.",
            detail = "Asked for ${insufficient.requested.stripTrailingZeros().toPlainString()}, " +
                "but only ${insufficient.available.stripTrailingZeros().toPlainString()} " +
                "in stock.",
        )
    }

    val serverDetail = problem?.detail?.takeIf { it.isNotBlank() }

    return when (code()) {
        400, 422 -> ApiError(
            "Some details need fixing.",
            serverDetail ?: problem?.errors?.firstOrNull()?.let { "${it.field} ${it.message}" },
        )
        401 -> ApiError("Your email or password was not recognised.")
        403 -> ApiError("Your account does not have permission to do that.")
        404 -> ApiError("That item no longer exists.")
        409 -> ApiError(serverDetail ?: "That conflicts with something already saved.")
        412 -> ApiError("Someone else changed this while you were editing. Reload and try again.")
        429 -> ApiError("Too many attempts. Wait a moment and try again.")
        in 500..599 -> ApiError("The server had a problem. Try again in a moment.")
        else -> ApiError(serverDetail ?: "Something went wrong. Try again.")
    }
}

/**
 * Parsing kept separate so it can be unit-tested without a Retrofit response,
 * and so a malformed body can never take the app down while it is trying to
 * report an error.
 */
object ProblemParsing {

    /**
     * The GENERATED client's Moshi, not a fresh one.
     *
     * Problem carries `type` as a URI, InsufficientStockProblem carries
     * `productId` as a UUID and `requested`/`available` as BigDecimal. A plain
     * Moshi has adapters for none of those, so parsing throws, the catch below
     * returns null, and every problem body silently degrades to the generic
     * message for its status code.
     *
     * That failure is invisible in the obvious places: the app still shows *an*
     * error, just the wrong one — an oversell would read "That conflicts with
     * something already saved" instead of naming the quantities. Caught by
     * ApiErrorTest, which asserts the numbers appear.
     */
    val moshi: Moshi by lazy { com.example.inventory.api.infrastructure.Serializer.moshi }

    fun parse(body: String, moshi: Moshi = this.moshi): Problem? = try {
        if (body.isBlank()) null else moshi.adapter(Problem::class.java).fromJson(body)
    } catch (e: Exception) {
        null
    }

    fun parseInsufficientStock(body: String, moshi: Moshi = this.moshi): InsufficientStockProblem? =
        try {
            if (body.isBlank()) {
                null
            } else {
                moshi.adapter(InsufficientStockProblem::class.java).fromJson(body)
            }
        } catch (e: Exception) {
            null
        }
}
