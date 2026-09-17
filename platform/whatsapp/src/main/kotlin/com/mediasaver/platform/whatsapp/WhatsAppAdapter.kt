package com.mediasaver.platform.whatsapp

import com.mediasaver.core.domain.repository.StatusRepository
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Backwards-compatible alias class for [WhatsAppStatusAdapter].
 */
@Singleton
class WhatsAppAdapter @Inject constructor(
    statusRepository: StatusRepository
) : WhatsAppStatusAdapter(statusRepository)
