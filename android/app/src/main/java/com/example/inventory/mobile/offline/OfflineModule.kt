package com.example.inventory.mobile.offline

import android.content.Context
import com.example.inventory.api.apis.InventoryApi
import com.example.inventory.api.apis.SalesApi
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import javax.inject.Singleton

/** Wiring for the offline outbox. */
@Module
@InstallIn(SingletonComponent::class)
object OfflineModule {

    /**
     * The queue file lives in `filesDir`, which is private to the app and
     * survives process death and app updates — the two things a queued sale has
     * to outlive.
     *
     * Deliberately NOT `cacheDir`: Android may delete that under storage
     * pressure, and it would take a shift's unsent sales with it without any
     * error the app or the user would ever see.
     */
    @Provides
    @Singleton
    fun outbox(@ApplicationContext context: Context): Outbox =
        Outbox(File(context.filesDir, "outbox/pending.log"))

    @Provides
    @Singleton
    fun outboxReplayer(
        outbox: Outbox,
        sales: SalesApi,
        inventory: InventoryApi,
    ): OutboxReplayer = OutboxReplayer(outbox, sales, inventory)
}
