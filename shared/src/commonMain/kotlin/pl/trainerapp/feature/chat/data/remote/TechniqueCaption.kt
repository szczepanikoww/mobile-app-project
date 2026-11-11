package pl.trainerapp.feature.chat.data.remote

import pl.trainerapp.feature.chat.model.MediaRef

/**
 * The server's message has no field for "which exercise and set is this video of" until the workout sync links
 * `setLogId`. Until then the client writes a readable first line, for example `Martwy ciąg · seria 3/4 · 110 kg × 5`,
 * and the readers turn it back into a [MediaRef]. Any text after the first line is the client's own note.
 */
object TechniqueCaption {
    data class Parsed(
        val exerciseName: String,
        val setNumber: Int,
        val setCount: Int,
        val weightGrams: Int?,
        val reps: Int?,
        /** What the client wrote besides the caption. */
        val note: String,
    )

    private val line = Regex("""^(.+) · seria (\d+)/(\d+)(?: · (\d+(?:\.\d{1,3})?) kg)?(?: × (\d+))?$""")

    fun format(exerciseName: String, setNumber: Int, setCount: Int, weightGrams: Int?, reps: Int?): String = buildString {
        append(exerciseName.replace('\n', ' ').trim()).append(" · seria ").append(setNumber).append('/').append(setCount)
        if (weightGrams != null && weightGrams > 0) append(" · ").append(kilograms(weightGrams)).append(" kg")
        if (reps != null && reps > 0) append(" × ").append(reps)
    }

    fun parse(text: String?): Parsed? {
        if (text.isNullOrBlank()) return null
        val first = text.substringBefore('\n').trim()
        val m = line.matchEntire(first) ?: return null
        val (name, number, count, weight, reps) = m.destructured
        return Parsed(
            exerciseName = name,
            setNumber = number.toInt(),
            setCount = count.toInt(),
            weightGrams = weight.takeIf { it.isNotEmpty() }?.let(::grams),
            reps = reps.takeIf { it.isNotEmpty() }?.toInt(),
            note = text.substringAfter('\n', "").trim(),
        )
    }

    fun Parsed.toMediaRef(mediaId: String) = MediaRef(mediaId, exerciseName, setNumber, setCount, weightGrams, reps)

    private fun kilograms(grams: Int): String {
        val whole = grams / 1000
        val rest = grams % 1000
        return if (rest == 0) "$whole" else "$whole.${rest.toString().padStart(3, '0').trimEnd('0')}"
    }

    private fun grams(kg: String): Int {
        val whole = kg.substringBefore('.').toInt()
        val fraction = kg.substringAfter('.', "").padEnd(3, '0').take(3).toInt()
        return whole * 1000 + fraction
    }
}
