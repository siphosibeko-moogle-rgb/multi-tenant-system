package com.example.inventory.mobile.net

import android.content.Context
import com.example.inventory.api.apis.AuthenticationApi
import com.example.inventory.api.apis.CatalogApi
import com.example.inventory.api.apis.ForecastingApi
import com.example.inventory.api.apis.SalesApi
import com.example.inventory.mobile.BuildConfig
import com.example.inventory.mobile.auth.AuthInterceptor
import com.example.inventory.mobile.auth.EncryptedTokenStore
import com.example.inventory.mobile.auth.TokenAuthenticator
import com.example.inventory.mobile.auth.TokenStore
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Provider
import javax.inject.Singleton
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import retrofit2.converter.scalars.ScalarsConverterFactory

/**
 * The network stack.
 *
 * The base URL comes from `BuildConfig.BASE_URL`, which is
 * `http://10.0.2.2:8080/api/v1/` for debug builds — the emulator's alias for the
 * host's loopback, i.e. the backend running on the developer's laptop. Cleartext
 * is permitted for that host alone, in the debug-only network security config.
 */
@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    @Provides
    @Singleton
    fun moshi(): Moshi = Moshi.Builder()
        .add(KotlinJsonAdapterFactory())
        // The generated infrastructure adapters — OffsetDateTime, BigDecimal,
        // UUID — are registered by the generated Serializer; this instance only
        // needs Kotlin support for the app's own small payloads.
        .build()

    @Provides
    @Singleton
    fun tokenStore(@ApplicationContext context: Context): TokenStore =
        EncryptedTokenStore(context)

    /**
     * A client with no authenticator, used only to call `/auth/refresh`.
     *
     * Separate on purpose: refreshing through the authenticated client would let
     * a 401 on the refresh re-enter the authenticator, which is a loop.
     */
    @Provides
    @Singleton
    @RefreshClient
    fun refreshClient(): OkHttpClient = OkHttpClient.Builder()
        .addInterceptor(loggingInterceptor())
        .build()

    @Provides
    @Singleton
    fun okHttpClient(
        tokens: TokenStore,
        @RefreshClient refreshClient: OkHttpClient,
        moshi: Moshi,
        // Provider, not the instance: SessionManager needs the API, the API
        // needs this client, and this client needs SessionManager. Deferring the
        // lookup to the moment a refresh actually fails breaks the cycle.
        sessionExpiry: Provider<SessionExpiryHandler>,
    ): OkHttpClient = OkHttpClient.Builder()
        .addInterceptor(AuthInterceptor(tokens))
        .addInterceptor(loggingInterceptor())
        .authenticator(
            TokenAuthenticator(
                tokens = tokens,
                baseUrl = BuildConfig.BASE_URL,
                refreshClient = refreshClient,
                moshi = moshi,
                onSessionExpired = { sessionExpiry.get().onSessionExpired() },
            )
        )
        .build()

    /**
     * BASIC logging on debug builds. Never BODY, and never anything on release.
     *
     * BASIC prints the method, URL, status and timing — enough to see that a
     * request happened and what it returned. BODY would additionally print the
     * `/auth/login` and `/auth/refresh` response bodies, which contain a 30-day
     * refresh token, straight into logcat, where `adb logcat` or a crash
     * reporter would collect it. That is the one place a token most easily
     * escapes, so the level is the defence and the redaction below is the
     * backstop.
     *
     * `redactHeader("Authorization")` covers the request side: BASIC does not
     * print headers today, but a future debugging session that bumps this to
     * HEADERS or BODY should not silently start leaking the access token too.
     */
    private fun loggingInterceptor(): HttpLoggingInterceptor =
        HttpLoggingInterceptor().apply {
            level = if (BuildConfig.DEBUG) {
                HttpLoggingInterceptor.Level.BASIC
            } else {
                HttpLoggingInterceptor.Level.NONE
            }
            redactHeader("Authorization")
        }

    @Provides
    @Singleton
    fun retrofit(client: OkHttpClient, moshi: Moshi): Retrofit = Retrofit.Builder()
        .baseUrl(BuildConfig.BASE_URL)
        .client(client)
        // Scalars first: some endpoints return plain values, and Moshi would
        // otherwise insist on quoting them.
        .addConverterFactory(ScalarsConverterFactory.create())
        .addConverterFactory(MoshiConverterFactory.create(serializerMoshi()))
        .build()

    /**
     * The generated client ships its own configured Moshi, with the adapters for
     * UUID, OffsetDateTime and BigDecimal that the models need. Using the app's
     * plain instance here would fail to deserialize almost every response.
     */
    private fun serializerMoshi(): Moshi = com.example.inventory.api.infrastructure.Serializer.moshi

    @Provides
    @Singleton
    fun authenticationApi(retrofit: Retrofit): AuthenticationApi =
        retrofit.create(AuthenticationApi::class.java)

    @Provides
    @Singleton
    fun catalogApi(retrofit: Retrofit): CatalogApi = retrofit.create(CatalogApi::class.java)

    @Provides
    @Singleton
    fun salesApi(retrofit: Retrofit): SalesApi = retrofit.create(SalesApi::class.java)

    /**
     * M7's forecasting endpoints: forecasts, one product's forecast, recompute,
     * reorder recommendations and dismiss.
     *
     * <p>Goes through the same authenticated [retrofit] as the others, so it
     * inherits the bearer-token interceptor and [TokenAuthenticator]'s refresh —
     * a forecasting call on an expired token refreshes and retries rather than
     * dropping the user to the login screen mid-scroll.
     */
    @Provides
    @Singleton
    fun inventoryApi(retrofit: Retrofit): com.example.inventory.api.apis.InventoryApi =
        retrofit.create(com.example.inventory.api.apis.InventoryApi::class.java)

    @Provides
    @Singleton
    fun forecastingApi(retrofit: Retrofit): ForecastingApi =
        retrofit.create(ForecastingApi::class.java)

    /** `GET /me` and the user directory. */
    @Provides
    @Singleton
    fun usersApi(retrofit: Retrofit): com.example.inventory.api.apis.UsersApi =
        retrofit.create(com.example.inventory.api.apis.UsersApi::class.java)

    /** Purchase orders, for the Create-order shortcut off a recommendation. */
    @Provides
    @Singleton
    fun purchasingApi(retrofit: Retrofit): com.example.inventory.api.apis.PurchasingApi =
        retrofit.create(com.example.inventory.api.apis.PurchasingApi::class.java)
}

/** Marks the un-authenticated client used for token refresh. */
@javax.inject.Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class RefreshClient

/** Indirection that lets the network layer signal logout without a Hilt cycle. */
interface SessionExpiryHandler {
    fun onSessionExpired()
}
