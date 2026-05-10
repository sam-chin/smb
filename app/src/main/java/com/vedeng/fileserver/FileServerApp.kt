package com.vedeng.fileserver

import android.app.Application
import com.vedeng.fileserver.proxy.CacheManager
import com.vedeng.fileserver.proxy.ProxyServer

class FileServerApp : Application() {

    lateinit var cacheManager: CacheManager
        private set

    lateinit var proxyServer: ProxyServer
        private set

    companion object {
        lateinit var instance: FileServerApp
            private set
    }

    override fun onCreate() {
        super.onCreate()
        instance = this

        cacheManager = CacheManager.getInstance(this)
        proxyServer = ProxyServer.getInstance(this, cacheManager)
        proxyServer.start()
    }

    override fun onTerminate() {
        super.onTerminate()
        proxyServer.stop()
        cacheManager.destroy()
    }
}
