package com.kuaimaosu.manager.domain.usecase

import com.kuaimaosu.manager.data.AppSettingsRepository
import com.kuaimaosu.manager.data.startup.ApplicationInitializationRepository
import com.kuaimaosu.manager.data.startup.StartupRepository
import com.kuaimaosu.manager.util.BootTrace

class InitializeApplicationUseCase(
    private val settingsRepository: AppSettingsRepository,
    private val startupRepository: StartupRepository,
    private val initializationRepository: ApplicationInitializationRepository,
) {
    suspend operator fun invoke() {
        BootTrace.mark("Init: settingsRepository.preload enter")
        runCatching {
            settingsRepository.preload()
            BootTrace.mark("Init: settingsRepository.preload done")
            BootTrace.mark("Init: initializationRepository.initialize enter")
            initializationRepository.initialize()
            BootTrace.mark("Init: initializationRepository.initialize done")
        }.onSuccess {
            BootTrace.mark("Init: markReady")
            startupRepository.markReady()
            BootTrace.mark("Init: markReady done")
        }.onFailure { error ->
            BootTrace.markThrowable("Init: FAILED", error)
            startupRepository.markFailed(error)
        }
    }
}
