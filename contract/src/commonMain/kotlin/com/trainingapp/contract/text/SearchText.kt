package com.trainingapp.contract.text

/**
 * Normalizes text for accent-insensitive search and duplicate detection:
 * lower case, Polish and common Latin diacritics folded, punctuation turned into spaces.
 * "Wyciskanie sztangi leżąc" and "wyciskanie  SZTANGI lezac" normalize to the same string.
 */
object SearchText {

    fun normalize(text: String): String {
        val out = StringBuilder(text.length)
        var lastWasSpace = true
        for (raw in text) {
            val c = fold(raw.lowercaseChar())
            if (c.isLetterOrDigit()) {
                out.append(c)
                lastWasSpace = false
            } else if (!lastWasSpace) {
                out.append(' ')
                lastWasSpace = true
            }
        }
        return out.toString().trimEnd()
    }

    fun tokens(text: String): List<String> = normalize(text).split(' ').filter { it.isNotEmpty() }

    private fun fold(c: Char): Char = when (c) {
        'ą', 'à', 'á', 'â', 'ä', 'ã', 'å' -> 'a'
        'ć', 'č', 'ç' -> 'c'
        'ę', 'è', 'é', 'ê', 'ë', 'ě' -> 'e'
        'ł' -> 'l'
        'ń', 'ñ', 'ň' -> 'n'
        'ó', 'ò', 'ô', 'ö', 'õ' -> 'o'
        'ś', 'š' -> 's'
        'ź', 'ż', 'ž' -> 'z'
        'ì', 'í', 'î', 'ï' -> 'i'
        'ù', 'ú', 'û', 'ü', 'ů' -> 'u'
        'ý', 'ÿ' -> 'y'
        'ř' -> 'r'
        'ť' -> 't'
        'ď' -> 'd'
        '–', '—' -> '-'
        else -> c
    }
}
