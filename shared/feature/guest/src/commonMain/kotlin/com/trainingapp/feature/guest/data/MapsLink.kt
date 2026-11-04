package com.trainingapp.feature.guest.data

/** A plain web link that the maps app or the browser opens; no maps SDK or key (D7). */
fun mapsUrl(address: String): String = "https://www.google.com/maps/search/?api=1&query=" + percentEncode(address)

/** RFC 3986 percent-encoding of UTF-8 text; unreserved characters stay as they are. */
fun percentEncode(text: String): String = buildString {
    text.encodeToByteArray().forEach { byte ->
        val code = byte.toInt() and 0xFF
        val char = code.toChar()
        if ((code < 0x80 && char.isLetterOrDigit()) || char in "-_.~") {
            append(char)
        } else {
            append('%').append(code.toString(16).uppercase().padStart(2, '0'))
        }
    }
}
