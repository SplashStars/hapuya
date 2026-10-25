package com.hapuya.android

import org.junit.Assert.assertTrue
import org.junit.Test

class BuildConfigTest {
    @Test
    fun backendUrlUsesHttps() {
        assertTrue(BuildConfig.BACKEND_URL.startsWith("https://"))
    }
}
