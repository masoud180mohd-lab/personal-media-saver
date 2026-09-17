package com.mediasaver.core.domain.usecase

import com.mediasaver.core.domain.repository.SettingsRepository
import com.mediasaver.core.model.AppSettings
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

/**
 * Use case to observe application settings.
 */
class GetSettingsUseCase @Inject constructor(
    private val settingsRepository: SettingsRepository
) {
    operator fun invoke(): Flow<AppSettings> {
        return settingsRepository.settings
    }
}
