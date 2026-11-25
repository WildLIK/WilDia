package com.obd2.app.core.search

import android.content.Context
import com.obd2.app.core.db.ObdLookupDb
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

enum class ConfidenceStatus(val labelRu: String) {
    CONFIRMED_BY_DOCS("Подтверждено документацией"),
    MULTIPLE_INDEPENDENT_SOURCES("Подтверждается несколькими независимыми источниками"),
    LOCAL_REFERENCE("Справочная причина (локальная база)"),
    PROBABLE_CAUSE("Вероятная причина"),
    INSUFFICIENT_INFO("Недостаточно информации"),
}

enum class SourceTier(val labelRu: String) {
    OFFICIAL_DOCS("Официальная / стандартизированная документация"),
    TECHNICAL_REFERENCE("Специализированный технический справочник"),
    COMMUNITY_FORUM("Опыт ремонта (форум / обсуждение)"),
}

enum class SourceOrigin(val labelRu: String) {
    LOCAL_DB("Локальная офлайн-база"),
    INTERNET("Получено из интернета"),
    UNVERIFIED_COMMUNITY("Неподтверждённые обсуждения"),
    SERVICE_UNAVAILABLE("Поисковый сервис недоступен / офлайн"),
}

@Serializable
data class DtcSearchRequest(
    val code: String,
    val manufacturer: String? = null,
    val model: String? = null,
    val year: String? = null,
    val engine: String? = null,
    val ecuName: String? = null,
    val dtcMode: Int = 3, // 3=stored, 7=pending, 10=permanent
    val relatedCodes: List<String> = emptyList(),
    val sensorSnapshot: Map<String, String> = emptyMap(),
    val isLegacyUnverified: Boolean = false,
    /** Privacy guard: VIN and personal identifiers are NEVER included unless explicitly consented. */
    val includeVinConsent: Boolean = false,
    val maskedVinOnly: String? = null,
)

@Serializable
data class DtcSearchSource(
    val title: String,
    val url: String,
    val tier: SourceTier = SourceTier.TECHNICAL_REFERENCE,
    val summary: String = "",
)

@Serializable
data class DtcCauseItem(
    val text: String,
    val status: ConfidenceStatus = ConfidenceStatus.PROBABLE_CAUSE,
)

@Serializable
data class DtcDiagnosticCard(
    val code: String,
    val title: String,
    val dtcMode: Int = 3,
    val isStandardCode: Boolean = true,
    val isLegacyUnverified: Boolean = false,
    val origin: SourceOrigin = SourceOrigin.LOCAL_DB,
    val standardMeaning: String,
    val manufacturerInfo: String? = null,
    val possibleCauses: List<DtcCauseItem> = emptyList(),
    val recommendedChecks: List<String> = emptyList(),
    val repairExperience: List<DtcSearchSource> = emptyList(),
    val sources: List<DtcSearchSource> = emptyList(),
    val contradictionNote: String? = null,
    /** Specialized automotive browser search links (explicitly labeled as search links, NOT verified sources). */
    val browserLinks: List<DtcSearchSource> = emptyList(),
    val fallbackSearchUrl: String,
    val fetchedAtMs: Long = System.currentTimeMillis(),
    val fromCache: Boolean = false,
    val offlineNotice: String? = null,
)

// Backwards-compatible aliases kept for existing references
@Serializable
data class DtcSearchResult(
    val code: String,
    val possibleCauses: List<String>,
    val symptoms: List<String>,
    val recommendedChecks: List<String>,
    val sources: List<DtcSearchSource>,
    val fromCache: Boolean = false,
    val fetchedAtMs: Long = System.currentTimeMillis(),
)

sealed interface DtcOnlineSearchResponse {
    data class Disabled(val reason: String) : DtcOnlineSearchResponse
    data class Blocked(val reason: String) : DtcOnlineSearchResponse
    data class NotFound(val reason: String, val card: DtcDiagnosticCard? = null) : DtcOnlineSearchResponse
    data class Found(val card: DtcDiagnosticCard) : DtcOnlineSearchResponse
}

interface DtcOnlineSearchProvider {
    suspend fun search(request: DtcSearchRequest, userOptInEnabled: Boolean): DtcOnlineSearchResponse
}

/** Optional AI summarization hook — never fabricates facts, no heavy local LLM. */
interface DtcAiAnalyzer {
    suspend fun summarize(cards: List<DtcDiagnosticCard>): String?
}

class DisabledDtcAiAnalyzer : DtcAiAnalyzer {
    override suspend fun summarize(cards: List<DtcDiagnosticCard>): String? = null
}

class DisabledDtcOnlineSearchProvider : DtcOnlineSearchProvider {
    override suspend fun search(
        request: DtcSearchRequest,
        userOptInEnabled: Boolean,
    ): DtcOnlineSearchResponse = if (!userOptInEnabled) {
        DtcOnlineSearchResponse.Disabled("Онлайн-поиск причин ошибок отключён пользователем в настройках.")
    } else {
        DtcOnlineSearchResponse.NotFound("Сервис внешнего поиска ещё не подключён; используйте локальную офлайн-базу DTC.")
    }
}

class DtcSearchCache(private val maxEntries: Int = 100) {
    private val map = LinkedHashMap<String, DtcSearchResult>()

    @Synchronized
    fun get(code: String, manufacturer: String?): DtcSearchResult? =
        map["${code.uppercase()}|${manufacturer.orEmpty()}"]?.copy(fromCache = true)

    @Synchronized
    fun put(request: DtcSearchRequest, result: DtcSearchResult) {
        val key = "${request.code.uppercase()}|${request.manufacturer.orEmpty()}"
        map[key] = result
        if (map.size > maxEntries) {
            val first = map.keys.firstOrNull()
            if (first != null) map.remove(first)
        }
    }
}

class PersistentDtcSearchCache(
    context: Context,
    private val ttlMs: Long = 7L * 24L * 3600_000L,
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val file: File by lazy { File(context.filesDir, "dtc_search_cache.json") }
    private val entries = LinkedHashMap<String, DtcDiagnosticCard>()
    private var loaded = false

    private fun ensureLoadedLocked() {
        if (loaded) return
        loaded = true
        loadFromDisk()
    }

    /** Preloads disk cache on a background thread. */
    fun preload() {
        synchronized(this) { ensureLoadedLocked() }
    }

    private fun key(req: DtcSearchRequest): String =
        "${req.code.uppercase()}|${req.manufacturer.orEmpty()}|${req.model.orEmpty()}|${req.engine.orEmpty()}"

    @Synchronized
    fun get(req: DtcSearchRequest, nowMs: Long = System.currentTimeMillis()): DtcDiagnosticCard? {
        ensureLoadedLocked()
        val card = entries[key(req)] ?: return null
        if (nowMs - card.fetchedAtMs > ttlMs) return null
        return card.copy(fromCache = true)
    }

    @Synchronized
    fun getAnyCached(code: String, manufacturer: String? = null): DtcDiagnosticCard? {
        ensureLoadedLocked()
        return entries.entries.firstOrNull { (k, c) ->
            c.code.equals(code, ignoreCase = true) &&
                (manufacturer.isNullOrBlank() || k.contains("|$manufacturer|", ignoreCase = true))
        }?.value?.copy(fromCache = true)
    }

    @Synchronized
    fun put(req: DtcSearchRequest, card: DtcDiagnosticCard) {
        ensureLoadedLocked()
        entries[key(req)] = card
        while (entries.size > 150) {
            val first = entries.keys.firstOrNull() ?: break
            entries.remove(first)
        }
        runCatching { file.writeText(json.encodeToString(entries.values.toList()), Charsets.UTF_8) }
    }

    @Synchronized
    fun allCached(): List<DtcDiagnosticCard> {
        ensureLoadedLocked()
        return entries.values.toList().sortedByDescending { it.fetchedAtMs }
    }

    @Synchronized
    private fun loadFromDisk() {
        if (!file.exists()) return
        runCatching {
            val list = json.decodeFromString<List<DtcDiagnosticCard>>(file.readText(Charsets.UTF_8))
            for (c in list) {
                entries["${c.code.uppercase()}|||"] = c
            }
        }
    }
}

@Serializable
data class RemoteDtcApiResponse(
    val standardMeaning: String? = null,
    val manufacturerInfo: String? = null,
    val causes: List<DtcCauseItem> = emptyList(),
    val checks: List<String> = emptyList(),
    val repairExperience: List<DtcSearchSource> = emptyList(),
    val sources: List<DtcSearchSource> = emptyList(),
    val contradictionNote: String? = null,
)

/**
 * Automotive DTC search provider:
 * 1) Strict brand-isolated local [ObdLookupDb] lookup (never leaks Škoda/VAG entries to other brands).
 * 2) Pluggable structured automotive JSON API (`customEndpointUrl` + optional user API key, never in APK).
 * 3) Free official NHTSA Recalls API (`api.nhtsa.gov`) when vehicle make + model + year are known.
 * 4) Specialized automotive browser search links (Ross-Tech for VAG, OBD-Codes / NHTSA / DuckDuckGo),
 *    explicitly separated from verified technical sources.
 *
 * MediaWiki/Wikipedia OpenSearch has been removed.
 */
class PublicDtcOnlineSearchProvider(
    private val lookupDb: ObdLookupDb,
    private val cache: PersistentDtcSearchCache,
    private val customEndpointUrl: String? = null,
    private val customApiKey: String? = null,
    private val nhtsaBaseUrl: String = "https://api.nhtsa.gov",
    private val allowHttpForTest: Boolean = false,
) : DtcOnlineSearchProvider {

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun search(
        request: DtcSearchRequest,
        userOptInEnabled: Boolean,
    ): DtcOnlineSearchResponse {
        if (!userOptInEnabled) {
            return DtcOnlineSearchResponse.Disabled("Онлайн-поиск причин ошибок отключён. Включите согласие на поиск.")
        }
        val code = request.code.trim().uppercase()
        if (!code.matches(Regex("[PCBU][0-9A-F]{4}"))) {
            return DtcOnlineSearchResponse.Blocked("Некорректный формат кода ошибки: $code")
        }

        cache.get(request)?.let { cached ->
            return DtcOnlineSearchResponse.Found(cached)
        }

        val isStandard = code.length >= 2 && code[1] in setOf('0', '2')
        val allLocal = runCatching { lookupDb.lookupDtc(code) }.getOrDefault(emptyList())

        // Strict brand isolation: keep ONLY GENERIC entries + entries matching request.manufacturer!
        val makerFiltered = allLocal.filter { entry ->
            entry.scope.equals("GENERIC", ignoreCase = true) ||
                (!request.manufacturer.isNullOrBlank() && isMatchingMakerScope(entry.scope, request.manufacturer))
        }
        val brandSpecific = makerFiltered.firstOrNull { !it.scope.equals("GENERIC", ignoreCase = true) }
        val genericEntry = makerFiltered.firstOrNull { it.scope.equals("GENERIC", ignoreCase = true) }
        val preferredLocal = brandSpecific ?: genericEntry

        val standardText = preferredLocal?.let { it.titleRu?.takeIf { t -> t.isNotBlank() } ?: it.titleEn }
            ?: "Стандартное определение кода $code отсутствует в локальной базе"
        val makerText = when {
            request.manufacturer.isNullOrBlank() -> null
            brandSpecific != null -> "Уточнение для ${request.manufacturer} (${brandSpecific.scope}): ${brandSpecific.titleRu ?: brandSpecific.titleEn}"
            else -> "Заводские записи для ${request.manufacturer} по коду $code в локальной базе отсутствуют"
        }

        val localCauses = makerFiltered.flatMap { it.causes }.distinct()
        val localChecks = (makerFiltered.flatMap { it.checks } + makerFiltered.mapNotNull { it.recommendationsRu }).distinct()
        val browserLinks = buildSpecializedBrowserLinks(request)
        val fallbackUrl = browserLinks.first().url

        var remoteData: RemoteDtcApiResponse? = null
        val nhtsaSources = ArrayList<DtcSearchSource>()
        var remoteAttempted = false
        var serviceNotice: String? = null

        // 1. Query pluggable structured automotive endpoint if configured
        if (!customEndpointUrl.isNullOrBlank()) {
            remoteAttempted = true
            try {
                remoteData = fetchStructuredEndpoint(request)
            } catch (e: Exception) {
                serviceNotice = "Внешний поисковый API недоступен (${e.message ?: "ошибка сети"})"
            }
        }

        // 2. Query official public NHTSA Recalls API when make + model + 4-digit year are reliably known
        val cleanYear = request.year?.let { Regex("(19|20)\\d{2}").find(it)?.value }
        if (!request.manufacturer.isNullOrBlank() && !request.model.isNullOrBlank() && cleanYear != null) {
            remoteAttempted = true
            try {
                nhtsaSources += fetchNhtsaRecalls(request.manufacturer, request.model, cleanYear, code)
            } catch (e: Exception) {
                if (serviceNotice == null) {
                    serviceNotice = "API NHTSA временно недоступен (${e.message ?: "ошибка сети"})"
                }
            }
        }

        if (!remoteAttempted) {
            serviceNotice = "Внешний автомобильный поисковый API не настроен. Показаны проверенные данные локальной базы и специализированные запросы для браузера."
        }

        val allSources = ArrayList<DtcSearchSource>()
        if (preferredLocal != null) {
            allSources += DtcSearchSource(
                title = "Локальная база OBD-II (${preferredLocal.scope})",
                url = "local://obd_data.db/dtc/$code",
                tier = SourceTier.TECHNICAL_REFERENCE,
                summary = standardText,
            )
        }
        remoteData?.sources?.let { allSources.addAll(it) }
        allSources.addAll(nhtsaSources)

        val hasVerifiedExternalDocs = (remoteData?.sources.orEmpty() + nhtsaSources)
            .any { it.url.startsWith("http") && it.tier == SourceTier.OFFICIAL_DOCS }
        val mergedCauses = ArrayList<DtcCauseItem>()
        remoteData?.causes?.forEach { mergedCauses += it }
        localCauses.forEach { c ->
            if (mergedCauses.none { it.text.equals(c, ignoreCase = true) }) {
                mergedCauses += DtcCauseItem(
                    text = c,
                    status = when {
                        hasVerifiedExternalDocs && (remoteData?.sources.orEmpty() + nhtsaSources).size >= 2 ->
                            ConfidenceStatus.MULTIPLE_INDEPENDENT_SOURCES
                        else -> ConfidenceStatus.LOCAL_REFERENCE
                    },
                )
            }
        }
        if (mergedCauses.isEmpty()) {
            mergedCauses += DtcCauseItem(
                text = "Недостаточно подтверждённых причин для ${request.manufacturer ?: "данного автомобиля"} — требуется проверка по сервисной документации.",
                status = ConfidenceStatus.INSUFFICIENT_INFO,
            )
        }

        val sensorChecks = buildSensorAwareChecks(request)
        val mergedChecks = (remoteData?.checks.orEmpty() + localChecks + sensorChecks + defaultSafeChecks(code)).distinct()
        val contradiction = remoteData?.contradictionNote ?: detectContradiction(allSources)

        val origin = when {
            remoteData != null || nhtsaSources.isNotEmpty() -> SourceOrigin.INTERNET
            serviceNotice != null && preferredLocal == null -> SourceOrigin.SERVICE_UNAVAILABLE
            else -> SourceOrigin.LOCAL_DB
        }

        val card = DtcDiagnosticCard(
            code = code,
            title = "$code — $standardText",
            dtcMode = request.dtcMode,
            isStandardCode = isStandard,
            isLegacyUnverified = request.isLegacyUnverified,
            origin = origin,
            standardMeaning = remoteData?.standardMeaning ?: standardText,
            manufacturerInfo = remoteData?.manufacturerInfo ?: makerText,
            possibleCauses = mergedCauses,
            recommendedChecks = mergedChecks,
            repairExperience = remoteData?.repairExperience.orEmpty(),
            sources = allSources,
            contradictionNote = contradiction,
            browserLinks = browserLinks,
            fallbackSearchUrl = fallbackUrl,
            fetchedAtMs = System.currentTimeMillis(),
            fromCache = false,
            offlineNotice = serviceNotice,
        )

        if (origin == SourceOrigin.INTERNET || preferredLocal != null) {
            cache.put(request, card)
            return DtcOnlineSearchResponse.Found(card)
        }

        cache.getAnyCached(code, request.manufacturer)?.let { stale ->
            return DtcOnlineSearchResponse.Found(
                stale.copy(offlineNotice = "${serviceNotice ?: "Нет сети"} — показана сохранённая копия из кэша."),
            )
        }
        return DtcOnlineSearchResponse.NotFound("Недостаточно данных по коду $code.", card)
    }

    private fun isMatchingMakerScope(scope: String, manufacturer: String): Boolean {
        val s = scope.uppercase()
        val m = manufacturer.uppercase()
        if (s == m) return true
        if (m in setOf("VAG", "VOLKSWAGEN", "VW", "AUDI", "SKODA", "ŠKODA", "SEAT") &&
            s in setOf("VAG", "VW", "AUDI", "SKODA", "SEAT")
        ) return true
        if (m.contains("NISSAN") && s.contains("NISSAN")) return true
        if (m.contains("FORD") && s.contains("FORD")) return true
        if ((m.contains("HAVAL") || m.contains("GREAT WALL")) && (s.contains("HAVAL") || s.contains("GREAT WALL") || s.contains("GWM"))) return true
        return false
    }

    private fun fetchStructuredEndpoint(request: DtcSearchRequest): RemoteDtcApiResponse? {
        val endpoint = customEndpointUrl ?: return null
        val sep = if ('?' in endpoint) '&' else '?'
        val query = buildQueryString(request)
        val urlStr = buildString {
            append("$endpoint${sep}code=${enc(request.code)}&q=${enc(query)}")
            request.manufacturer?.takeIf { it.isNotBlank() }?.let { append("&maker=${enc(it)}") }
            request.model?.takeIf { it.isNotBlank() }?.let { append("&model=${enc(it)}") }
            request.engine?.takeIf { it.isNotBlank() }?.let { append("&engine=${enc(it)}") }
            if (request.relatedCodes.isNotEmpty()) append("&related=${enc(request.relatedCodes.joinToString(","))}")
        }
        val parsed = URL(urlStr)
        if (!allowHttpForTest && !parsed.protocol.equals("https", ignoreCase = true)) {
            throw SecurityException("Требуется HTTPS-подключение")
        }
        val conn = (parsed.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 5000
            readTimeout = 5000
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "OBD2-Diagnostic-Scanner/0.5.3")
            if (!customApiKey.isNullOrBlank()) {
                setRequestProperty("Authorization", "Bearer $customApiKey")
            }
        }
        val code = conn.responseCode
        if (code !in 200..299) throw IllegalStateException("HTTP $code")
        val body = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        if (body.isBlank()) return null
        return json.decodeFromString<RemoteDtcApiResponse>(body)
    }

    private fun fetchNhtsaRecalls(
        make: String,
        model: String,
        year: String,
        dtcCode: String,
    ): List<DtcSearchSource> {
        val urlStr = "$nhtsaBaseUrl/recalls/recallsByVehicle?make=${enc(make)}&model=${enc(model)}&modelYear=${enc(year)}"
        val parsed = URL(urlStr)
        if (!allowHttpForTest && !parsed.protocol.equals("https", ignoreCase = true)) return emptyList()
        val conn = (parsed.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 4000
            readTimeout = 4000
            setRequestProperty("Accept", "application/json")
        }
        if (conn.responseCode !in 200..299) return emptyList()
        val body = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        val root = json.parseToJsonElement(body).jsonObject
        val results = root["results"]?.jsonArray ?: return emptyList()
        val out = ArrayList<DtcSearchSource>()
        for (el in results.take(5)) {
            val obj = el.jsonObject
            val comp = obj["Component"]?.jsonPrimitive?.content.orEmpty()
            val summary = obj["Summary"]?.jsonPrimitive?.content.orEmpty()
            val campaign = obj["NHTSACampaignNumber"]?.jsonPrimitive?.content.orEmpty()
            val hay = "$comp $summary".uppercase()
            if (dtcCode in hay || "EMISSION" in hay || "ENGINE" in hay || "FUEL" in hay || "SENSOR" in hay) {
                out += DtcSearchSource(
                    title = "NHTSA Recall $campaign ($comp)",
                    url = "https://www.nhtsa.gov/recalls?nhtsaId=$campaign",
                    tier = SourceTier.OFFICIAL_DOCS,
                    summary = summary.take(200),
                )
            }
        }
        return out
    }

    private fun buildSensorAwareChecks(request: DtcSearchRequest): List<String> {
        if (request.sensorSnapshot.isEmpty()) return emptyList()
        val checks = ArrayList<String>()
        val summary = request.sensorSnapshot.entries.take(4).joinToString(", ") { "${it.key}: ${it.value}" }
        checks += "Учтите текущие показания датчиков ($summary) при проверке кода ${request.code}."
        return checks
    }

    private fun detectContradiction(sources: List<DtcSearchSource>): String? {
        val text = sources.joinToString(" ") { it.summary.lowercase() }
        val rich = "богатая смесь" in text || "rich" in text
        val lean = "бедная смесь" in text || "lean" in text
        return if (rich && lean) {
            "Внимание: найденные источники содержат противоречивые трактовки (богатая vs бедная смесь) — требуется проверка топливных коррекций."
        } else null
    }

    private fun defaultSafeChecks(code: String): List<String> = listOf(
        "Проверьте визуально разъёмы, массу и жгут проводки соответствующего узла перед заменой деталей.",
        "Сравните параметры стоп-кадра (Mode 02) и сопутствующие ошибки для кода $code.",
    )

    fun buildQueryString(request: DtcSearchRequest): String {
        val parts = ArrayList<String>()
        parts += "OBD2"
        parts += request.code.trim().uppercase()
        request.manufacturer?.takeIf { it.isNotBlank() }?.let { parts += it }
        request.model?.takeIf { it.isNotBlank() }?.let { parts += it }
        request.year?.takeIf { it.isNotBlank() }?.let { parts += it }
        request.engine?.takeIf { it.isNotBlank() }?.let { parts += it }
        request.ecuName?.takeIf { it.isNotBlank() }?.let { parts += it }
        if (request.includeVinConsent && !request.maskedVinOnly.isNullOrBlank()) {
            parts += request.maskedVinOnly
        }
        return parts.joinToString(" ")
    }

    fun buildSpecializedBrowserLinks(request: DtcSearchRequest): List<DtcSearchSource> {
        val code = request.code.trim().uppercase()
        val links = ArrayList<DtcSearchSource>()
        links += DtcSearchSource(
            title = "Поиск в браузере (запрос по авто и коду)",
            url = "https://duckduckgo.com/?q=${enc(buildQueryString(request))}",
            tier = SourceTier.TECHNICAL_REFERENCE,
        )
        links += DtcSearchSource(
            title = "Справочник OBD-Codes ($code)",
            url = "https://www.obd-codes.com/${code.lowercase()}",
            tier = SourceTier.TECHNICAL_REFERENCE,
        )
        if (request.manufacturer?.uppercase() in setOf("VAG", "VOLKSWAGEN", "VW", "AUDI", "SKODA", "ŠKODA", "SEAT")) {
            links += DtcSearchSource(
                title = "Ross-Tech Wiki ($code)",
                url = "https://wiki.ross-tech.com/wiki/index.php?search=${enc(code)}",
                tier = SourceTier.TECHNICAL_REFERENCE,
            )
        }
        return links
    }

    fun buildBrowserFallbackUrl(request: DtcSearchRequest): String =
        buildSpecializedBrowserLinks(request).first().url

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")
}
