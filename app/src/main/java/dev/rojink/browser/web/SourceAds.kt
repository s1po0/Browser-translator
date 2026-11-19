package dev.rojink.browser.web

import java.util.Locale

// Additive, reader-only list. Match whole DNS labels rather than substrings such as "ad" in reader/CDN names.
private val sourceAdNetworks = setOf(
    "ad-maven.com", "adform.net", "adrecover.com", "adskeeper.com", "adsterra.com", "advertising.com",
    "amazon-adsystem.com", "bidgear.com", "exoclick.com", "hilltopads.com", "juicyads.com",
    "media.net", "onclkds.com", "popovertrade.com", "popads.net", "popcash.net", "propellerads.com",
    "realsrv.com", "revcontent.com", "trafficjunky.com", "zedo.com",
)

internal fun isSourceAdHost(host: String): Boolean {
    val name = host.trimEnd('.').lowercase(Locale.ROOT)
    return isTracker(name) || sourceAdNetworks.any { name == it || name.endsWith(".$it") }
}
