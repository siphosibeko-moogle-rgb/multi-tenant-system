package com.example.inventory.mobile

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

/**
 * Application entry point.
 *
 * Step 1 deliberately contains no networking, no screens and no storage — the
 * only thing this milestone's skeleton has to prove is that the project
 * assembles and that the API client generated from `docs/openapi.yaml` compiles
 * as part of that build.
 */
@HiltAndroidApp
class InventoryApplication : Application()
