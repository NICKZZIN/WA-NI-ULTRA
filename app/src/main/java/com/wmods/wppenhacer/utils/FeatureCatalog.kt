package com.wmods.wppenhacer.utils

import android.content.Context
import android.content.res.XmlResourceParser
import com.wmods.wppenhacer.R
import com.wmods.wppenhacer.model.SearchableFeature
import org.xmlpull.v1.XmlPullParser
import java.util.Locale

/**
 * Dynamic catalog of searchable features.
 *
 * Automatically indexes every preference defined in the preference XML screens at runtime,
 * so any newly added preference appears in search by its title, summary, or key without
 * needing manual ID registration.
 */
object FeatureCatalog {
    private const val ANDROID_NS = "http://schemas.android.com/apk/res/android"
    private const val APP_NS = "http://schemas.android.com/apk/res-auto"

    @Volatile
    private var cachedFeatures: List<SearchableFeature>? = null

    @Volatile
    private var cachedLocale: Locale? = null

    private data class XmlSource(
        val xmlRes: Int,
        val category: SearchableFeature.Category,
        val fragmentType: SearchableFeature.FragmentType,
        val parentKey: String?
    )

    private val xmlSources = listOf(
        XmlSource(
            R.xml.preference_general_home,
            SearchableFeature.Category.GENERAL_HOME,
            SearchableFeature.FragmentType.GENERAL,
            "general_home"
        ),
        XmlSource(
            R.xml.preference_general_homescreen,
            SearchableFeature.Category.GENERAL_HOMESCREEN,
            SearchableFeature.FragmentType.GENERAL,
            "homescreen"
        ),
        XmlSource(
            R.xml.preference_general_conversation,
            SearchableFeature.Category.GENERAL_CONVERSATION,
            SearchableFeature.FragmentType.GENERAL,
            "conversation"
        ),
        XmlSource(
            R.xml.fragment_general,
            SearchableFeature.Category.GENERAL,
            SearchableFeature.FragmentType.GENERAL,
            null
        ),
        XmlSource(
            R.xml.fragment_privacy,
            SearchableFeature.Category.PRIVACY,
            SearchableFeature.FragmentType.PRIVACY,
            null
        ),
        XmlSource(
            R.xml.fragment_media,
            SearchableFeature.Category.MEDIA,
            SearchableFeature.FragmentType.MEDIA,
            null
        ),
        XmlSource(
            R.xml.fragment_customization,
            SearchableFeature.Category.CUSTOMIZATION,
            SearchableFeature.FragmentType.CUSTOMIZATION,
            null
        )
    )

    /** Extra helpful search synonyms for popular options (completely optional). */
    private val extraSynonyms = mapOf(
        "capture_device" to listOf("pc", "web", "desktop", "computador", "celular", "phone", "linked"),
        "bypass_screenshot_restriction" to listOf("print", "screenshot", "gravar tela", "flag_secure"),
        "parallel_downloading" to listOf("rapido", "velocidade", "fast", "multithread"),
        "viewonce" to listOf("visualizacao unica", "view once"),
        "antirevoke" to listOf("apagada", "deletada", "deleted")
    )

    @JvmStatic
    fun getAllFeatures(context: Context): List<SearchableFeature> {
        val currentLocale = context.resources.configuration.locales[0] ?: Locale.getDefault()
        val existing = cachedFeatures
        if (existing != null && cachedLocale == currentLocale) {
            return existing
        }
        return synchronized(this) {
            val recheck = cachedFeatures
            if (recheck != null && cachedLocale == currentLocale) {
                recheck
            } else {
                buildDynamicCatalog(context).also {
                    cachedFeatures = it
                    cachedLocale = currentLocale
                }
            }
        }
    }

    @JvmStatic
    fun invalidate() {
        cachedFeatures = null
        cachedLocale = null
    }

    @JvmStatic
    fun search(context: Context, query: String?): List<SearchableFeature> {
        if (query.isNullOrBlank()) return emptyList()
        return getAllFeatures(context).filter { it.matches(query) }
    }

    private fun buildDynamicCatalog(context: Context): List<SearchableFeature> {
        val catalog = ArrayList<SearchableFeature>()
        val seenKeys = HashSet<String>()

        for (source in xmlSources) {
            parseXmlScreen(context, source, catalog, seenKeys)
        }

        // Also index top-level app actions
        addAppActions(context, catalog)
        return catalog
    }

    private fun parseXmlScreen(
        context: Context,
        source: XmlSource,
        out: MutableList<SearchableFeature>,
        seenKeys: MutableSet<String>
    ) {
        var currentSectionTitle = ""
        runCatching {
            context.resources.getXml(source.xmlRes).use { parser ->
                var eventType = parser.eventType
                while (eventType != XmlPullParser.END_DOCUMENT) {
                    if (eventType == XmlPullParser.START_TAG) {
                        val tag = parser.name.orEmpty()
                        if (tag.endsWith("PreferenceCategory")) {
                            currentSectionTitle = readAttrText(context, parser, "title").orEmpty()
                        } else if (!tag.endsWith("PreferenceScreen")) {
                            val key = readAttrText(context, parser, "key")
                            val title = readAttrText(context, parser, "title")
                            if (!key.isNullOrBlank() && !title.isNullOrBlank() && seenKeys.add(key)) {
                                val summary = readAttrText(context, parser, "summary")
                                    ?.takeIf { it != "%s" }
                                    ?: readAttrText(context, parser, "summaryOn")
                                val tags = buildList {
                                    if (currentSectionTitle.isNotBlank()) add(currentSectionTitle)
                                    add(key.replace('_', ' '))
                                    extraSynonyms[key]?.let(::addAll)
                                }
                                out.add(
                                    SearchableFeature(
                                        key = key,
                                        title = title,
                                        summary = summary,
                                        category = source.category,
                                        fragmentType = source.fragmentType,
                                        parentKey = source.parentKey,
                                        searchTags = tags
                                    )
                                )
                            }
                        }
                    }
                    eventType = parser.next()
                }
            }
        }
    }

    private fun readAttrText(context: Context, parser: XmlResourceParser, attrName: String): String? {
        for (ns in arrayOf(APP_NS, ANDROID_NS)) {
            val resId = parser.getAttributeResourceValue(ns, attrName, 0)
            if (resId != 0) {
                return runCatching { context.getString(resId) }.getOrNull()
            }
            val raw = parser.getAttributeValue(ns, attrName)
            if (!raw.isNullOrEmpty() && !raw.startsWith("@")) {
                return raw
            }
        }
        return null
    }

    private fun addAppActions(context: Context, catalog: MutableList<SearchableFeature>) {
        catalog.add(
            SearchableFeature(
                "sticker_bot_open",
                context.getString(R.string.sticker_bot_title),
                context.getString(R.string.sticker_bot_summary),
                SearchableFeature.Category.HOME_ACTIONS,
                SearchableFeature.FragmentType.ACTIVITY,
                null,
                listOf("bot", "figurinha", "sticker", "som", "audio", "terminal", "wa.me")
            )
        )
        catalog.add(
            SearchableFeature(
                "app_settings_open",
                context.getString(R.string.app_settings_title),
                context.getString(R.string.backup_settings),
                SearchableFeature.Category.HOME_ACTIONS,
                SearchableFeature.FragmentType.ACTIVITY,
                null,
                listOf("export", "import", "backup", "reset", "padrao", "configuracoes")
            )
        )
    }
}
