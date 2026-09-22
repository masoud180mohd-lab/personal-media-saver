package com.mediasaver.core.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SecurityUtilsTest {

    @Test
    fun `validates allowed extensions correctly`() {
        assertTrue(SecurityUtils.isValidExtension("mp4"))
        assertTrue(SecurityUtils.isValidExtension(".mp4"))
        assertTrue(SecurityUtils.isValidExtension("MP4"))
        assertTrue(SecurityUtils.isValidExtension("webm"))
        assertTrue(SecurityUtils.isValidExtension("jpg"))
        assertTrue(SecurityUtils.isValidExtension("jpeg"))
        assertTrue(SecurityUtils.isValidExtension("png"))
        assertTrue(SecurityUtils.isValidExtension("webp"))

        assertFalse(SecurityUtils.isValidExtension("exe"))
        assertFalse(SecurityUtils.isValidExtension(".sh"))
        assertFalse(SecurityUtils.isValidExtension("apk"))
        assertFalse(SecurityUtils.isValidExtension("bat"))
        assertFalse(SecurityUtils.isValidExtension("js"))
        assertFalse(SecurityUtils.isValidExtension("html"))
        assertFalse(SecurityUtils.isValidExtension(null))
        assertFalse(SecurityUtils.isValidExtension(""))
    }

    @Test
    fun `normalizes extension with safe fallback`() {
        assertEquals("mp4", SecurityUtils.normalizeExtension(".mp4"))
        assertEquals("webm", SecurityUtils.normalizeExtension("WEBM"))
        assertEquals("jpg", SecurityUtils.normalizeExtension(".JPG"))
        assertEquals("mp4", SecurityUtils.normalizeExtension("exe"))
        assertEquals("mp4", SecurityUtils.normalizeExtension(".php"))
        assertEquals("mp4", SecurityUtils.normalizeExtension(null))
        assertEquals("jpeg", SecurityUtils.normalizeExtension("exe", defaultExtension = "jpeg"))
    }

    @Test
    fun `detects dangerous schemes`() {
        assertTrue(SecurityUtils.isDangerousScheme("javascript"))
        assertTrue(SecurityUtils.isDangerousScheme("javascript:"))
        assertTrue(SecurityUtils.isDangerousScheme("JAVASCRIPT"))
        assertTrue(SecurityUtils.isDangerousScheme("file"))
        assertTrue(SecurityUtils.isDangerousScheme("content"))
        assertTrue(SecurityUtils.isDangerousScheme("intent"))
        assertTrue(SecurityUtils.isDangerousScheme("data"))
        assertTrue(SecurityUtils.isDangerousScheme("blob"))
        assertTrue(SecurityUtils.isDangerousScheme("ftp"))

        assertFalse(SecurityUtils.isDangerousScheme("https"))
        assertFalse(SecurityUtils.isDangerousScheme("http"))
        assertFalse(SecurityUtils.isDangerousScheme(null))
    }

    @Test
    fun `validates media MIME types and rejects dangerous types`() {
        assertTrue(SecurityUtils.isAllowedMimeType("video/mp4"))
        assertTrue(SecurityUtils.isAllowedMimeType("video/mp4; charset=UTF-8"))
        assertTrue(SecurityUtils.isAllowedMimeType("video/webm"))
        assertTrue(SecurityUtils.isAllowedMimeType("audio/mp4"))
        assertTrue(SecurityUtils.isAllowedMimeType("audio/webm"))
        assertTrue(SecurityUtils.isAllowedMimeType("image/jpeg"))
        assertTrue(SecurityUtils.isAllowedMimeType("image/png"))
        assertTrue(SecurityUtils.isAllowedMimeType("image/webp"))
        assertTrue(SecurityUtils.isAllowedMimeType("application/octet-stream"))

        assertFalse(SecurityUtils.isAllowedMimeType("text/html"))
        assertFalse(SecurityUtils.isAllowedMimeType("application/javascript"))
        assertFalse(SecurityUtils.isAllowedMimeType("application/json"))
        assertFalse(SecurityUtils.isAllowedMimeType("application/x-msdownload"))
        assertFalse(SecurityUtils.isAllowedMimeType("application/x-sh"))
        assertFalse(SecurityUtils.isAllowedMimeType("text/javascript"))
    }

    @Test
    fun `identifies local and private IP addresses for SSRF defense`() {
        // Loopback
        assertTrue(SecurityUtils.isLocalOrPrivateIp("localhost"))
        assertTrue(SecurityUtils.isLocalOrPrivateIp("127.0.0.1"))
        assertTrue(SecurityUtils.isLocalOrPrivateIp("127.0.1.1"))
        assertTrue(SecurityUtils.isLocalOrPrivateIp("::1"))
        assertTrue(SecurityUtils.isLocalOrPrivateIp("[::1]"))

        // Private IPv4 ranges
        assertTrue(SecurityUtils.isLocalOrPrivateIp("10.0.0.1"))
        assertTrue(SecurityUtils.isLocalOrPrivateIp("10.254.254.254"))
        assertTrue(SecurityUtils.isLocalOrPrivateIp("172.16.0.1"))
        assertTrue(SecurityUtils.isLocalOrPrivateIp("172.31.255.255"))
        assertTrue(SecurityUtils.isLocalOrPrivateIp("192.168.1.1"))
        assertTrue(SecurityUtils.isLocalOrPrivateIp("192.168.0.100"))

        // Link local & any local
        assertTrue(SecurityUtils.isLocalOrPrivateIp("169.254.1.1"))
        assertTrue(SecurityUtils.isLocalOrPrivateIp("0.0.0.0"))

        // Carrier grade NAT & multicast
        assertTrue(SecurityUtils.isLocalOrPrivateIp("100.64.0.1"))
        assertTrue(SecurityUtils.isLocalOrPrivateIp("224.0.0.1"))

        // Public IPs (should be false)
        assertFalse(SecurityUtils.isLocalOrPrivateIp("8.8.8.8"))
        assertFalse(SecurityUtils.isLocalOrPrivateIp("1.1.1.1"))
    }
}
