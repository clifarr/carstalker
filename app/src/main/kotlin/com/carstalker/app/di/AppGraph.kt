package com.carstalker.app.di

import android.content.Context
import com.carstalker.app.data.LogRepository
import com.carstalker.app.data.SettingsRepository
import com.carstalker.app.service.ObdSession
import com.carstalker.app.transport.ble.BleScanner
import com.carstalker.app.transport.classic.ClassicScanner

/** Manual dependency graph; small enough that a DI framework would add noise. */
class AppGraph(context: Context) {
    val appContext: Context = context.applicationContext
    val settings = SettingsRepository(appContext)
    val logs = LogRepository(appContext)
    val session = ObdSession(settings, logs, appContext)
    val bleScanner = BleScanner(appContext)
    val classicScanner = ClassicScanner(appContext)
}
