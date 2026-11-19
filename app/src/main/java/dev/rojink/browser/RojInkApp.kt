package dev.rojink.browser

import android.app.Application
import dev.rojink.browser.data.BrowserDatabase
import dev.rojink.browser.data.KeyVault
import dev.rojink.browser.data.SettingsStore
import dev.rojink.browser.extensions.ExtensionInstaller
import dev.rojink.browser.extensions.ExtensionStores
import dev.rojink.browser.web.Library

class RojInkApp : Application() {
    private val db by lazy { BrowserDatabase.create(this) }
    val dao by lazy { db.dao() }
    val library by lazy { Library(this, dao) }
    val settings by lazy { SettingsStore(this) }
    val keys by lazy { KeyVault(this) }
    internal val extensionStores by lazy { ExtensionStores(this) }
    internal val extensionInstaller by lazy { ExtensionInstaller(this) }
}
