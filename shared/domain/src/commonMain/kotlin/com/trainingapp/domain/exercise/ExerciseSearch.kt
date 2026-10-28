package com.trainingapp.domain.exercise

import com.trainingapp.contract.exercise.Equipment
import com.trainingapp.contract.exercise.MuscleGroup
import com.trainingapp.contract.text.SearchText

enum class ExerciseScope { ALL, MINE }

data class ExerciseFilter(
    val query: String = "",
    val muscles: Set<MuscleGroup> = emptySet(),
    val equipment: Set<Equipment> = emptySet(),
    val scope: ExerciseScope = ExerciseScope.ALL,
) {
    val hasQuery: Boolean get() = query.isNotBlank()
}

/**
 * Local, accent-insensitive search over Polish and English names, aliases, muscle and equipment terms.
 * Hidden exercises never appear. Name matches rank above muscle/equipment matches; featured exercises
 * rank first within a tier.
 */
object ExerciseSearch {

    fun search(exercises: List<Exercise>, filter: ExerciseFilter, languageTag: String): List<Exercise> {
        val queryTokens = SearchText.tokens(filter.query)
        return exercises.asSequence()
            .filter { !it.isHidden }
            .filter { matchesFilters(it, filter) }
            .mapNotNull { exercise ->
                val tier = if (queryTokens.isEmpty()) 0 else matchTier(exercise, queryTokens) ?: return@mapNotNull null
                Triple(exercise, tier, SearchText.normalize(exercise.name(languageTag)))
            }
            .sortedWith(
                compareBy<Triple<Exercise, Int, String>> { it.second }
                    .thenBy { if (it.first.featured) 0 else 1 }
                    .thenBy { it.third },
            )
            .map { it.first }
            .toList()
    }

    /** Muscle, equipment and scope filters without a query; also used for the A–Z and "Moje" sections. */
    fun matchesFilters(exercise: Exercise, filter: ExerciseFilter): Boolean {
        if (filter.scope == ExerciseScope.MINE && !exercise.isCustom) return false
        if (filter.muscles.isNotEmpty() && exercise.primaryMuscles.none { it in filter.muscles }) return false
        if (filter.equipment.isNotEmpty() && exercise.equipment !in filter.equipment) return false
        return true
    }

    /** Lower is better; `null` means no match. */
    private fun matchTier(exercise: Exercise, queryTokens: List<String>): Int? {
        val query = queryTokens.joinToString(" ")
        val names = (exercise.names.values + exercise.aliases).map(SearchText::normalize)
        return when {
            names.any { it == query } -> 0
            names.any { it.startsWith(query) } -> 1
            names.any { allTokensPrefixWords(queryTokens, it) } -> 2
            names.any { it.contains(query) } -> 3
            allTokensPrefixWords(queryTokens, vocabularyFor(exercise)) -> 4
            else -> null
        }
    }

    private fun allTokensPrefixWords(queryTokens: List<String>, candidate: String): Boolean {
        val words = candidate.split(' ')
        return queryTokens.all { token -> words.any { it.startsWith(token) } }
    }

    private fun vocabularyFor(exercise: Exercise): String =
        (exercise.primaryMuscles.flatMap { SearchVocabulary.muscle(it) } + SearchVocabulary.equipment(exercise.equipment))
            .joinToString(" ") { SearchText.normalize(it) }
}

/**
 * Search terms for muscles and equipment in the supported languages. These are search keywords, not UI copy;
 * the displayed labels come from string resources.
 */
object SearchVocabulary {
    fun muscle(muscle: MuscleGroup): List<String> = when (muscle) {
        MuscleGroup.ABDOMINALS -> listOf("brzuch", "mięśnie brzucha", "abs", "abdominals", "core")
        MuscleGroup.ABDUCTORS -> listOf("odwodziciele", "abductors")
        MuscleGroup.ADDUCTORS -> listOf("przywodziciele", "adductors")
        MuscleGroup.BICEPS -> listOf("biceps", "dwugłowy ramienia")
        MuscleGroup.CALVES -> listOf("łydki", "calves")
        MuscleGroup.CHEST -> listOf("klatka piersiowa", "klata", "chest", "pecs")
        MuscleGroup.FOREARMS -> listOf("przedramiona", "forearms")
        MuscleGroup.GLUTES -> listOf("pośladki", "glutes")
        MuscleGroup.HAMSTRINGS -> listOf("dwugłowe uda", "tył uda", "hamstrings")
        MuscleGroup.LATS -> listOf("najszersze grzbietu", "plecy", "lats", "back")
        MuscleGroup.LOWER_BACK -> listOf("dolny odcinek pleców", "prostowniki", "plecy", "lower back")
        MuscleGroup.MIDDLE_BACK -> listOf("środek pleców", "plecy", "middle back", "back")
        MuscleGroup.NECK -> listOf("szyja", "neck")
        MuscleGroup.QUADRICEPS -> listOf("czworogłowe uda", "przód uda", "nogi", "quadriceps", "quads", "legs")
        MuscleGroup.SHOULDERS -> listOf("barki", "ramiona", "shoulders", "delts")
        MuscleGroup.TRAPS -> listOf("czworoboczny", "kaptury", "traps")
        MuscleGroup.TRICEPS -> listOf("triceps", "trójgłowy ramienia")
    }

    fun equipment(equipment: Equipment): List<String> = when (equipment) {
        Equipment.BARBELL -> listOf("sztanga", "barbell")
        Equipment.DUMBBELL -> listOf("hantle", "hantel", "dumbbell")
        Equipment.CABLE -> listOf("wyciąg", "linka", "cable")
        Equipment.MACHINE -> listOf("maszyna", "machine")
        Equipment.KETTLEBELL -> listOf("kettlebell", "odważnik")
        Equipment.BANDS -> listOf("guma", "taśma", "bands")
        Equipment.BODY_ONLY -> listOf("masa ciała", "bez sprzętu", "bodyweight")
        Equipment.MEDICINE_BALL -> listOf("piłka lekarska", "medicine ball")
        Equipment.EXERCISE_BALL -> listOf("piłka gimnastyczna", "fitball", "exercise ball")
        Equipment.FOAM_ROLL -> listOf("roller", "foam roller")
        Equipment.EZ_BAR -> listOf("gryf łamany", "ez bar")
        Equipment.OTHER -> emptyList()
    }
}

/**
 * Soft duplicate check for the exercise form. It never blocks saving.
 * Two names are similar when they normalize to the same text, or when at least two thirds of the
 * shorter name's words (and at least two words) also occur in the other name.
 */
object DuplicateFinder {

    fun findSimilar(name: String, exercises: List<Exercise>, excludeId: String? = null): Exercise? {
        val tokens = SearchText.tokens(name).toSet()
        if (tokens.isEmpty()) return null
        val normalized = tokens.joinToString(" ")
        return exercises.asSequence()
            .filter { it.id != excludeId && !it.isHidden }
            .mapNotNull { exercise ->
                val best = (exercise.names.values + exercise.aliases).maxOfOrNull { similarity(normalized, tokens, it) } ?: 0.0
                if (best > 0) exercise to best else null
            }
            .sortedWith(compareByDescending<Pair<Exercise, Double>> { it.second }.thenBy { if (it.first.featured) 0 else 1 })
            .firstOrNull()?.first
    }

    private fun similarity(normalized: String, tokens: Set<String>, candidate: String): Double {
        val candidateTokens = SearchText.tokens(candidate).toSet()
        if (candidateTokens.isEmpty()) return 0.0
        if (candidateTokens.joinToString(" ") == normalized || candidateTokens == tokens) return 2.0
        val common = tokens.intersect(candidateTokens).size
        val shorter = minOf(tokens.size, candidateTokens.size)
        val overlap = common.toDouble() / shorter
        return if (common >= minOf(2, shorter) && shorter >= 2 && overlap >= 2.0 / 3.0) overlap else 0.0
    }
}
