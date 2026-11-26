package com.trainingapp.domain.exercise

/**
 * The trainer's own note and video on an exercise, the library's included ("Od trenera",
 * docs/plans/trainer-redesign.md). The video is shown before the library images.
 */
data class TrainerExerciseNote(val exerciseId: String, val note: String = "", val demoMediaId: String? = null) {
    val isEmpty: Boolean get() = note.isBlank() && demoMediaId == null
}

/** Read and saved online; the demo keeps them in memory. Failures throw, so the screen can keep the typed note. */
interface TrainerExerciseNotes {
    suspend fun load(exerciseId: String): TrainerExerciseNote

    /** An empty note and no video remove it. */
    suspend fun save(note: TrainerExerciseNote): TrainerExerciseNote
}

/** Without a server: kept in memory for this run (previews, demo). */
class InMemoryTrainerExerciseNotes : TrainerExerciseNotes {
    private val notes = mutableMapOf<String, TrainerExerciseNote>()

    override suspend fun load(exerciseId: String) = notes[exerciseId] ?: TrainerExerciseNote(exerciseId)

    override suspend fun save(note: TrainerExerciseNote): TrainerExerciseNote {
        val cleaned = note.copy(note = note.note.trim())
        if (cleaned.isEmpty) notes.remove(note.exerciseId) else notes[note.exerciseId] = cleaned
        return cleaned
    }
}
