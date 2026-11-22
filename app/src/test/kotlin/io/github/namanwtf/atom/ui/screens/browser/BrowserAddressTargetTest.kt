package io.github.namanwtf.atom.ui.screens.browser

import android.app.Application
import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class BrowserAddressTargetTest {
    @Test
    fun blankInputDoesNothing() {
        assertNull(resolveBrowserAddress(" \n\t "))
    }

    @Test
    fun explicitUrlsKeepTheirSchemeAndParameters() {
        for (url in listOf("https://example.com/search?q=a+b#result", "http://localhost:8080", "file:///sdcard/page.html")) {
            assertEquals(url, resolveBrowserAddress(" $url "))
        }
    }

    @Test
    fun domainAndLocalAddressesOpenDirectly() {
        for (address in listOf("github.com", "example.com/chinese?q=a+b#section", "127.0.0.1:8080/test", "localhost:3000")) {
            assertEquals("https://$address", resolveBrowserAddress(address))
        }
    }

    @Test
    fun searchTermsAreEncodedAsOneGoogleQuery() {
        for (query in listOf("weather", "Kotlin coroutines", "C++ & Compose #1", "site:github.com Atom")) {
            val target = Uri.parse(resolveBrowserAddress(" $query "))
            assertEquals("https", target.scheme)
            assertEquals("www.google.com", target.host)
            assertEquals("/search", target.path)
            assertEquals(setOf("q"), target.queryParameterNames)
            assertEquals(query, target.getQueryParameter("q"))
        }
    }
}
