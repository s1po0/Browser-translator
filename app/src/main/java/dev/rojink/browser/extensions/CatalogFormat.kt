package dev.rojink.browser.extensions

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNames
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.protobuf.ProtoBuf
import kotlinx.serialization.protobuf.ProtoNumber
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.IOException
import java.net.URI
import java.util.Locale

internal const val KEIYOUSHI_INDEX = "https://github.com/keiyoushi/extensions/raw/repo/index.pb"
internal const val KEIYOUSHI_SIGNER = "9add655a78e96c4ec7a53ef89dccb557cb5d767489fac5e785d671a5a75d4da2"
internal const val MAX_STORE_URL = 4096

internal enum class StoreProblem { ADDRESS, FORMAT, SIGNER, DUPLICATE, LIMIT, APK }
internal class StoreException(val problem: StoreProblem, cause: Throwable? = null) : IOException(problem.name, cause)

@Serializable
internal data class SavedExtensionStore(val indexUrl: String, val name: String, val signer: String)

internal data class ExtensionCatalog(val store: SavedExtensionStore, val entries: List<CatalogExtension>)

internal data class CatalogSource(val name: String, val language: String, val website: HttpUrl?)
internal data class CatalogExtension(
    val name: String,
    val packageName: String,
    val versionCode: Long,
    val version: String,
    val warning: CatalogWarning,
    val apk: HttpUrl?,
    val sources: List<CatalogSource>,
) {
    val languages = sources.map { it.language }.filter(String::isNotEmpty).distinct().sorted()
}

internal fun List<CatalogExtension>.search(query: String, includeWarnings: Boolean): List<CatalogExtension> {
    val terms = query.trim().split(Regex("\\s+")).filter(String::isNotEmpty)
    return filter { entry ->
        (includeWarnings || entry.warning == CatalogWarning.SAFE) && terms.all { term ->
            entry.name.contains(term, ignoreCase = true) ||
                entry.languages.any { it.equals(term, ignoreCase = true) } ||
                entry.sources.any { it.name.contains(term, ignoreCase = true) }
        }
    }
}

internal fun secureStoreUrl(value: String): HttpUrl? {
    val raw = value.trim()
    if (raw.length !in 1..MAX_STORE_URL || !raw.startsWith("https://", ignoreCase = true) ||
        raw.any { it.isISOControl() || it == '\\' }) return null
    val authority = raw.substring(8).takeWhile { it !in "/?#" }
    if (authority.isEmpty() || '@' in authority) return null
    return raw.toHttpUrlOrNull()?.takeIf { it.isHttps && it.fragment == null }
}

internal fun parseStoreInput(value: String): HttpUrl? {
    val raw = value.trim()
    secureStoreUrl(raw)?.let { return it }
    if (raw.length > MAX_STORE_URL * 3) return null
    val link = try { URI(raw) } catch (_: java.net.URISyntaxException) { return null }
    val addStore = (link.scheme == "mihon" && link.rawAuthority == "extension-store") ||
        (link.scheme == "tachiyomi" && link.rawAuthority == "add-repo")
    if (!addStore || link.path !in listOf("", "/") || link.fragment != null) return null
    // Use HttpUrl just to decode the query once, preserving nested percent-encoded URLs.
    val parsed = "https://store.invalid/?${link.rawQuery ?: return null}".toHttpUrlOrNull() ?: return null
    return parsed.queryParameterValues("url").singleOrNull()?.let(::secureStoreUrl)
}

internal fun HttpUrl.isKeiyoushiIndex(): Boolean {
    if (port != 443 || pathSegments.last() !in listOf("index.pb", "index.json", "index.min.json", "repo.json")) return false
    val path = pathSegments.dropLast(1).joinToString("/")
    return when (host) {
        "github.com" -> path == "keiyoushi/extensions/raw/repo" || path == "keiyoushi/extensions/raw/refs/heads/repo"
        "raw.githubusercontent.com" -> path == "keiyoushi/extensions/repo" || path == "keiyoushi/extensions/refs/heads/repo"
        else -> false
    }
}

internal fun HttpUrl.sibling(filename: String): HttpUrl = newBuilder()
    .setPathSegment(pathSize - 1, filename).build()

internal fun fingerprint(raw: String): String? = raw.trim().lowercase(Locale.ROOT)
    .takeIf { it.length == 64 && it.all { char -> char in '0'..'9' || char in 'a'..'f' } }

// Wire fields from Mihon/Tachiyomix index.proto. Unused fields (including Keiyoushi's JAR URL) are ignored.
@OptIn(ExperimentalSerializationApi::class)
@Serializable
internal data class ModernIndex(
    @ProtoNumber(1) val name: String = "",
    @ProtoNumber(3) val signingKey: String = "",
    @ProtoNumber(101) val extensionList: ModernList? = null,
    @ProtoNumber(102) val extensionListUrl: String? = null,
)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
internal data class ModernList(@ProtoNumber(1) val extensions: List<ModernExtension> = emptyList())

@OptIn(ExperimentalSerializationApi::class)
@Serializable
internal data class ModernExtension(
    @ProtoNumber(1) val name: String = "",
    @ProtoNumber(2) val packageName: String = "",
    @ProtoNumber(3) val resources: Resources? = null,
    @ProtoNumber(5) val versionCode: Long = 0,
    @ProtoNumber(6) val versionName: String = "",
    @ProtoNumber(7) val contentWarning: CatalogWarning = CatalogWarning.UNSPECIFIED,
    @ProtoNumber(8) val sources: List<Source> = emptyList(),
) {
    @Serializable
    data class Resources(@ProtoNumber(1) val apkUrl: String = "")

    @Serializable
    data class Source(
        @ProtoNumber(2) val name: String = "",
        @ProtoNumber(3) val language: String = "",
        @ProtoNumber(4) val homeUrl: String = "",
    )

    fun toEntry(): CatalogExtension? = catalogEntry(name, packageName, versionCode, versionName, contentWarning,
        resources?.apkUrl?.let(::secureStoreUrl), sources.mapNotNull { catalogSource(it.name, it.language, it.homeUrl) })
}

@OptIn(ExperimentalSerializationApi::class)
@Serializable
internal enum class CatalogWarning {
    @JsonNames("CONTENT_WARNING_UNSPECIFIED") UNSPECIFIED,
    @JsonNames("CONTENT_WARNING_SAFE") SAFE,
    @JsonNames("CONTENT_WARNING_MIXED") MIXED,
    @JsonNames("CONTENT_WARNING_NSFW") NSFW,
}

@Serializable
internal data class LegacyRepo(@SerialName("index_v2") val indexV2: String? = null, val meta: Meta? = null) {
    @Serializable
    data class Meta(val name: String = "", val signingKeyFingerprint: String = "")
}

@Serializable
private data class LegacyExtension(
    val name: String = "", val pkg: String = "", val apk: String = "", val lang: String = "",
    val code: Long = 0, val version: String = "", val nsfw: Int = -1, val sources: List<Source>? = null,
) {
    @Serializable
    data class Source(val name: String = "", val lang: String = "", val baseUrl: String = "")

    fun toEntry(repoUrl: HttpUrl): CatalogExtension? {
        val label = name.removePrefix("Tachiyomi: ")
        val apkUrl = apk.takeIf(legacyApkName::matches)?.let {
            repoUrl.newBuilder().removePathSegment(repoUrl.pathSize - 1).addPathSegment("apk").addPathSegment(it).build()
        }
        val warning = when (nsfw) { 0 -> CatalogWarning.SAFE; 1 -> CatalogWarning.NSFW; else -> CatalogWarning.UNSPECIFIED }
        val sources = sources.orEmpty().mapNotNull { catalogSource(it.name, it.lang, it.baseUrl) }
            .ifEmpty { listOfNotNull(catalogSource(label, lang, "")) }
        return catalogEntry(label, pkg, code, version, warning, apkUrl, sources)
    }
}

internal val catalogJson = Json { ignoreUnknownKeys = true; coerceInputValues = true }
internal fun catalogLabel(text: String, limit: Int = 160): String = text
    .filterNot { it.isISOControl() || it in '\u202a'..'\u202e' || it in '\u2066'..'\u2069' }
    .trim().take(limit)

private val packageNamePattern = Regex("[a-zA-Z][a-zA-Z0-9_]*(?:\\.[a-zA-Z][a-zA-Z0-9_]*)+")
private val legacyApkName = Regex("[A-Za-z0-9._-]{1,235}\\.apk")
private fun catalogSource(name: String, language: String, home: String): CatalogSource? {
    val label = catalogLabel(name).ifBlank { return null }
    return CatalogSource(label, catalogLabel(language, 32).lowercase(Locale.ROOT), secureStoreUrl(home))
}

private fun catalogEntry(name: String, pkg: String, code: Long, version: String, warning: CatalogWarning,
    apk: HttpUrl?, sources: List<CatalogSource>): CatalogExtension? {
    val label = catalogLabel(name).ifBlank { return null }
    if (code < 1 || pkg.length > 255 || !packageNamePattern.matches(pkg)) return null
    return CatalogExtension(label, pkg, code, catalogLabel(version, 64).ifBlank { code.toString() }, warning, apk, sources)
}

internal fun ModernList.entries(): List<CatalogExtension> = validatedEntries(extensions, ModernExtension::toEntry)
internal fun legacyEntries(bytes: ByteArray, repoUrl: HttpUrl): List<CatalogExtension> = parseFormat {
    val array = jsonRoot(bytes) as? JsonArray ?: throw StoreException(StoreProblem.FORMAT)
    validatedEntries(catalogJson.decodeFromJsonElement<List<LegacyExtension>>(array)) { it.toEntry(repoUrl) }
}

private fun <T> validatedEntries(values: List<T>, entry: (T) -> CatalogExtension?): List<CatalogExtension> {
    val entries = values.mapNotNull(entry)
    if (values.isNotEmpty() && entries.isEmpty()) throw StoreException(StoreProblem.FORMAT)
    return entries.groupBy { it.packageName }.values.map { samePackage -> samePackage.maxBy { it.versionCode } }
        .sortedBy { it.name.lowercase(Locale.ROOT) }
}

internal sealed interface IndexDocument {
    data class Modern(val index: ModernIndex) : IndexDocument
    data class Legacy(val repo: LegacyRepo) : IndexDocument
}

@OptIn(ExperimentalSerializationApi::class)
internal fun readIndex(bytes: ByteArray): IndexDocument = parseFormat {
    when (val root = jsonRoot(bytes)) {
        null -> IndexDocument.Modern(ProtoBuf.decodeFromByteArray<ModernIndex>(bytes))
        is JsonObject -> if ("index_v2" in root || "meta" in root) {
            IndexDocument.Legacy(catalogJson.decodeFromJsonElement<LegacyRepo>(root))
        } else IndexDocument.Modern(catalogJson.decodeFromJsonElement<ModernIndex>(root))
        else -> throw StoreException(StoreProblem.FORMAT)
    }
}

@OptIn(ExperimentalSerializationApi::class)
internal fun readSplitList(bytes: ByteArray): ModernList = parseFormat {
    when (val root = jsonRoot(bytes)) {
        null -> ProtoBuf.decodeFromByteArray<ModernList>(bytes)
        is JsonObject -> {
            require("extensions" in root)
            catalogJson.decodeFromJsonElement<ModernList>(root)
        }
        else -> throw StoreException(StoreProblem.FORMAT)
    }
}

private fun jsonRoot(bytes: ByteArray): JsonElement? {
    var start = if (bytes.size >= 3 && bytes[0] == 0xef.toByte() && bytes[1] == 0xbb.toByte() && bytes[2] == 0xbf.toByte()) 3 else 0
    while (start < bytes.size && bytes[start].toInt() in listOf(9, 10, 13, 32)) start++
    if (start == bytes.size || bytes[start].toInt() !in listOf('{'.code, '['.code)) return null
    return try {
        catalogJson.parseToJsonElement(bytes.decodeToString(startIndex = start, throwOnInvalidSequence = true))
    } catch (e: Exception) {
        // Protobuf also starts with 0x0a (its field-1 tag), which can look like JSON whitespace.
        if (bytes[0] == 0x0a.toByte()) null else throw e
    }
}

private inline fun <T> parseFormat(block: () -> T): T = try {
    block()
} catch (e: IllegalArgumentException) {
    throw StoreException(StoreProblem.FORMAT, e)
} catch (e: java.nio.charset.CharacterCodingException) {
    throw StoreException(StoreProblem.FORMAT, e)
}
