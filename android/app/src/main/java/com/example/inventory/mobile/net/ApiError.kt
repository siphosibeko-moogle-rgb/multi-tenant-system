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
            detail = shortfall(
                requested = insufficient.requested.stripTrailingZeros().toPlainString(),
                available = insufficient.available.stripTrailingZeros().toPlainString(),
            ),
        )
    }

    // The typed parse failed but this may still be an oversell.
    //
    // It is a malformed response by the contract's lights — the three numbers
    // are required — so it should not happen. It DID happen, and the way it
    // failed is the reason this branch exists: a backend that sent
    // `available: null` made the whole model unconstructable, so the client
    // threw away `requested` as well, which it had received perfectly, and fell
    // through to echoing the server's own `detail`. The cashier was shown "The
    // requested quantity is not available" — true, useless, and exactly the
    // information they already had.
    //
    // Refusing to render a partial answer is the wrong instinct on an error
    // path. Showing what we do know beats showing nothing, and losing a number
    // we were handed is a worse failure than the malformed field that caused
    // it. Salvaging is per-field and deliberately lenient.
    if (code() == 409 && ProblemParsing.isInsufficientStock(problem)) {
        val salvaged = ProblemParsing.salvageNumbers(raw, moshi)
        if (salvaged.requested != null || salvaged.available != null) {
            return ApiError(
                message = "Not enough stock to complete this sale.",
                detail = shortfall(salvaged.requested, salvaged.available),
            )
        }
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
 * The second line of an oversell, from whichever numbers survived parsing.
 *
 * One template for the complete and the salvaged case so the two cannot drift
 * into saying different things about the same refusal.
 */
private fun shortfall(requested: String?, available: String?): String = when {
    requested != null && available != null ->
        "You asked for $requested but only $available are available."
    requested != null -> "You asked for $requested, which is more than is in stock."
    available != null -> "Only $available are available."
    // Not reachable from the call sites, which both check first. Present so the
    // function is total rather than relying on that remaining true.
    else -> "There is not enough stock."
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

    /** Whether a parsed problem claims to be an oversell, by its stable type slug. */
    fun isInsufficientStock(problem: Problem?): Boolean =
        problem?.type?.toString()?.endsWith("/insufficient-stock") == true

    /** Whichever oversell numbers could be read, independently of each other. */
    data class SalvagedNumbers(val requested: String?, val available: String?)

    /**
     * Reads `requested` and `available` out of a body the typed model rejected.
     *
     * Deliberately untyped and per-field: the point is to survive whatever made
     * the strict parse fail, so one unusable field cannot cost the other. Parsed
     * as a generic map rather than with a hand-rolled regex, because a regex
     * over JSON would find the right answer for the wrong reasons and keep
     * finding it after the shape changed.
     */
    fun salvageNumbers(body: String, moshi: Moshi = this.moshi): SalvagedNumbers = try {
        @Suppress("UNCHECKED_CAST")
        val map = moshi.adapter(Map::class.java).fromJson(body) as? Map<String, Any?>
        SalvagedNumbers(number(map?.get("requested")), number(map?.get("available")))
    } catch (e: Exception) {
        SalvagedNumbers(null, null)
    }

    /** Renders a JSON number as a person would write it: 0.000 becomes 0. */
    private fun number(value: Any?): String? = when (value) {
        null -> null
        is java.math.BigDecimal -> value.stripTrailingZeros().toPlainString()
        is Number -> java.math.BigDecimal(value.toString()).stripTrailingZeros().toPlainString()
        is String -> value.toBigDecimalOrNull()?.stripTrailingZeros()?.toPlainString()
        else -> null
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
