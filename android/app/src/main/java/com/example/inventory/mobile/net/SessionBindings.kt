package com.example.inventory.mobile.net

import com.example.inventory.mobile.auth.SessionManager
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * Binds [SessionManager] as the network layer's [SessionExpiryHandler].
 *
 * The indirection exists to break a genuine cycle: SessionManager needs the
 * generated AuthenticationApi, which needs Retrofit, which needs the OkHttp
 * client, whose authenticator has to be able to tell SessionManager that the
 * session ended. Hilt resolves it because NetworkModule asks for a
 * Provider<SessionExpiryHandler> rather than the instance, deferring the lookup
 * until a refresh actually fails.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class SessionBindings {

    @Binds
    abstract fun sessionExpiryHandler(sessionManager: SessionManager): SessionExpiryHandler
}
