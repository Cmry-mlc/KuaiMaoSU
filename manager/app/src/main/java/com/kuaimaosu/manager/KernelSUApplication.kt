package com.kuaimaosu.manager

import android.app.Application
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import com.kuaimaosu.manager.di.appModules
import com.kuaimaosu.manager.domain.usecase.InitializeApplicationUseCase
import com.kuaimaosu.manager.util.BootTrace
import com.kuaimaosu.manager.util.CrashHandler
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger
import org.koin.core.context.startKoin

class KernelSUApplication : Application() {
    override fun onCreate() {
        BootTrace.install(this)
        BootTrace.mark("Application.onCreate enter")
        super.onCreate()
        CrashHandler.install(this)
        BootTrace.mark("CrashHandler installed")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val processName = getProcessName()
            BootTrace.mark("processName=$processName")
            if (processName.endsWith("MagicaService")) {
                // avoid loading unnecessary thing when starting MagicaService
                BootTrace.mark("MagicaService process, skip init")
                return
            }
        }

        BootTrace.mark("startKoin enter")
        val koin = startKoin {
            androidLogger()
            androidContext(this@KernelSUApplication)
            modules(appModules)
        }.koin
        BootTrace.mark("startKoin done, run InitializeApplicationUseCase")
        runBlocking(Dispatchers.IO) {
            koin.get<InitializeApplicationUseCase>()()
        }
        BootTrace.mark("InitializeApplicationUseCase done")
    }
}
