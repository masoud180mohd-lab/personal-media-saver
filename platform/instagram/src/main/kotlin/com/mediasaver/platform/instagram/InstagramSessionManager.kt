package com.mediasaver.platform.instagram

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Holds an Instagram session only in this process's memory.
 * Cookies are never restored from or written to persistent storage.
 */
@Singleton
class InstagramSessionManager @Inject constructor() {

    @Volatile
    private var ephemeralSession: String? = null

    fun saveCookies(cookieString: String) {
        ephemeralSession = cookieString
    }

    fun getCookies(): String? = ephemeralSession

    fun isLoggedIn(): Boolean = !ephemeralSession.isNullOrBlank()

    fun clearSession() {
        ephemeralSession = null
    }
}
