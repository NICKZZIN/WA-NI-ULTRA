package com.wmods.wppenhacer.model

import java.text.Normalizer
import java.util.Locale

/** Data model representing a searchable feature in the app. */
class SearchableFeature @JvmOverloads constructor(
    val key: String,
    val title: String,
    val summary: String?,
    val category: Category,
    val fragmentType: FragmentType,
    val parentKey: String? = null,
    searchTags: List<String>? = null
) {
    val searchTags: List<String> = searchTags ?: emptyList()

    enum class Category(val displayName: String) {
        GENERAL("Geral / General"),
        GENERAL_HOME("Geral / General"),
        GENERAL_HOMESCREEN("Tela Inicial / Home"),
        GENERAL_CONVERSATION("Conversa / Conversation"),
        PRIVACY("Privacidade / Privacy"),
        MEDIA("Mídia / Media"),
        CUSTOMIZATION("Personalização / Customization"),
        RECORDINGS("Gravações / Recordings"),
        HOME_ACTIONS("Início / Home")
    }

    enum class FragmentType(val position: Int) {
        HOME(0),
        GENERAL(1),
        PRIVACY(2),
        MEDIA(3),
        CUSTOMIZATION(4),
        RECORDINGS(5),
        ACTIVITY(99)
    }

    fun matches(query: String?): Boolean {
        if (query.isNullOrBlank()) return false

        val normalizedQuery = normalize(query)
        if (normalize(title).contains(normalizedQuery)) return true
        if (summary != null && normalize(summary).contains(normalizedQuery)) return true
        if (normalize(key).contains(normalizedQuery)) return true
        if (searchTags.any { normalize(it).contains(normalizedQuery) }) return true
        return normalize(category.displayName).contains(normalizedQuery)
    }

    override fun toString(): String =
        "SearchableFeature{key='$key', title='$title', category=$category}"

    companion object {
        private val DIACRITICS_REGEX = Regex("\\p{InCombiningDiacriticalMarks}+")

        fun normalize(text: String): String {
            val decomposed = Normalizer.normalize(text.lowercase(Locale.ROOT).trim(), Normalizer.Form.NFD)
            return DIACRITICS_REGEX.replace(decomposed, "")
        }
    }
}
