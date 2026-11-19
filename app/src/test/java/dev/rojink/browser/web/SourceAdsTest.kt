package dev.rojink.browser.web

import org.junit.Assert.*
import org.junit.Test

class SourceAdsTest {
    @Test fun readerFilterMatchesWholeDnsLabelsNotPublisherOrImageHosts() {
        assertTrue(isSourceAdHost("cdn.PopAds.net."))
        assertTrue(isSourceAdHost("pagead2.googlesyndication.com"))
        assertFalse(isSourceAdHost("popads.net.reader.example"))
        assertFalse(isSourceAdHost("goodpopads.net"))
        assertFalse(isSourceAdHost("adventure-images.example"))
        assertFalse(isTracker("cdn.popads.net")) // The additional filter is not applied to regular tabs.
    }
}
