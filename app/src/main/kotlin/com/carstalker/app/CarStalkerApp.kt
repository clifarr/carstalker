package com.carstalker.app

import android.app.Application
import com.carstalker.app.di.AppGraph

class CarStalkerApp : Application() {

    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
    }
}
