package com.ai.assistance.operit.core.tools.agent

import com.ai.assistance.operit.util.AppLogger
import com.ai.assistance.shower.IShowerService

object ShowerBinderRegistry {

    private const val TAG = "ShowerBinderRegistry"

    @Volatile
    private var service: IShowerService? = null

    fun setService(newService: IShowerService?) {
        service = newService
        val alive = newService?.asBinder()?.isBinderAlive == true
        AppLogger.d(TAG, "setService: service=$newService alive=$alive")
    }

    fun getService(): IShowerService? = service

    fun hasAliveService(): Boolean {
        val binder = service?.asBinder()
        val alive = binder?.isBinderAlive == true
        AppLogger.d(TAG, "hasAliveService: binder=$binder alive=$alive")
        return alive
    }
}
