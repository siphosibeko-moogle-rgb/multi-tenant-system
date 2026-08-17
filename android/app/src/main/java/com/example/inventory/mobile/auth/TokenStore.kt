package com.example.inventory.mobile.auth

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Where the access and refresh tokens live.
 *
 * <p>An interface with one real implementation, for a reason that is not
 * ceremony: [EncryptedTokenStore] needs the Android keystore, so it cannot run
 * in a JVM unit test. `TokenAuthenticatorTest` substitutes an in-memory store
 * and exercises the real authenticator against a real MockWebServer — the part
 * worth testing — without dragging in an instrumented test to do it.
 */
interface TokenStore {

    fun accessToken(): String?

    fun refreshToken(): String?

    fun save(accessToken: String, refreshToken: String)

    /** Wipes both tokens. Called on logout and on a refresh that fails. */
    fun clear()
}

/**
 * Tokens in [EncryptedSharedPreferences], encrypted with a key held in the
 * Android keystore.
 *
 * <h2>Why not plain SharedPreferences</h2>
 *
 * A refresh token is a 30-day credential for one tenant's data. Plain
 * SharedPreferences is a world-readable-to-root XML file that survives in
 * backups and in any `adb pull` on a debuggable build — storing it there means
 * the device's disk is the security boundary. The keystore-backed key means the
 * ciphertext is useless off the device.
 *
 * <h2>These values never reach a log</h2>
 *
 * Nothing in this class logs, and nothing returns a token in [toString]. The
 * OkHttp logging interceptor is configured to redact the `Authorization` header
 * (see `NetworkModule`), because BODY-level logging of `/auth/login` would
 * otherwise put a refresh token in logcat — where a crash reporter would
 * cheerfully collect it.
 */
class EncryptedTokenStore(context: Context) : TokenStore {

    private val prefs: SharedPreferences by lazy {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        EncryptedSharedPreferences.create(
            context,
            "auth_tokens",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    override fun accessToken(): String? = prefs.getString(KEY_ACCESS, null)

    override fun refreshToken(): String? = prefs.getString(KEY_REFRESH, null)

    override fun save(accessToken: String, refreshToken: String) {
        // commit(), not apply(). The tokens must be on disk before the caller
        // proceeds — an apply() that has not flushed when the process is killed
        // mid-login leaves the user logged out with no way to tell why.
        prefs.edit()
            .putString(KEY_ACCESS, accessToken)
            .putString(KEY_REFRESH, refreshToken)
            .commit()
    }

    override fun clear() {
        prefs.edit().clear().commit()
    }

    private companion object {
        const val KEY_ACCESS = "access_token"
        const val KEY_REFRESH = "refresh_token"
    }
}

/** In-memory store for unit tests. Never used by the app. */
class InMemoryTokenStore(
    private var access: String? = null,
    private var refresh: String? = null,
) : TokenStore {

    override fun accessToken(): String? = access

    override fun refreshToken(): String? = refresh

    override fun save(accessToken: String, refreshToken: String) {
        access = accessToken
        refresh = refreshToken
    }

    override fun clear() {
        access = null
        refresh = null
    }
}
