package com.ppxb.miwaku

import android.app.Application
import com.ppxb.miwaku.core.data.AppGraph

class MiwakuApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        AppGraph.init(this)
    }
}
