package com.mediasaver.platform.instagram

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InstagramSessionManagerTest {

    @Test
    fun `new manager starts without a session`() {
        val manager = InstagramSessionManager()

        assertNull(manager.getCookies())
        assertFalse(manager.isLoggedIn())
    }

    @Test
    fun `cookies are available only in the instance that received them`() {
        val manager = InstagramSessionManager()
        val cookies = "sessionid=test-session; csrftoken=test-csrf"

        manager.saveCookies(cookies)

        assertEquals(cookies, manager.getCookies())
        assertTrue(manager.isLoggedIn())
        val freshManager = InstagramSessionManager()
        assertNull(freshManager.getCookies())
        assertFalse(freshManager.isLoggedIn())
    }

    @Test
    fun `clearing a session removes cookies and login state`() {
        val manager = InstagramSessionManager()
        manager.saveCookies("sessionid=test-session")

        manager.clearSession()
        manager.clearSession()

        assertNull(manager.getCookies())
        assertFalse(manager.isLoggedIn())
    }

    @Test
    fun `blank cookies do not mark the manager logged in`() {
        val manager = InstagramSessionManager()
        manager.saveCookies("   ")

        assertFalse(manager.isLoggedIn())
    }
}
