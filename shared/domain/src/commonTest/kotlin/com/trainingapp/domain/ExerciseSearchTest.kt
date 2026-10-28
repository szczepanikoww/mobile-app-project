package com.trainingapp.domain

import com.trainingapp.contract.exercise.Equipment
import com.trainingapp.contract.exercise.ExerciseStatus
import com.trainingapp.contract.exercise.MuscleGroup
import com.trainingapp.domain.exercise.DuplicateFinder
import com.trainingapp.domain.exercise.ExerciseFilter
import com.trainingapp.domain.exercise.ExerciseScope
import com.trainingapp.domain.exercise.ExerciseSearch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ExerciseSearchTest {

    private val bench = exercise("bench", "Barbell Bench Press", "Wyciskanie sztangi leżąc", aliases = listOf("bench", "wyciskanie na ławce płaskiej"), featured = true)
    private val dbBench = exercise("db-bench", "Dumbbell Bench Press", "Wyciskanie hantli leżąc", equipment = Equipment.DUMBBELL)
    private val guillotine = exercise("guillotine", "Barbell Guillotine Bench Press")
    private val row = exercise("row", "Bent Over Barbell Row", "Wiosłowanie sztangą w opadzie", muscle = MuscleGroup.MIDDLE_BACK)
    private val hidden = exercise("hidden", "Bench Press Hidden", owner = "t1", status = ExerciseStatus.HIDDEN)
    private val own = exercise("own", "Hip thrust na maszynie", muscle = MuscleGroup.GLUTES, equipment = Equipment.MACHINE, owner = "t1")
    private val all = listOf(guillotine, dbBench, row, bench, hidden, own)

    private fun search(query: String = "", filter: ExerciseFilter = ExerciseFilter(query)) =
        ExerciseSearch.search(all, filter, "pl").map { it.id }

    @Test
    fun polishQueriesMatchWithAndWithoutDiacritics() {
        assertEquals(listOf("bench", "db-bench"), search("wyciskanie lezac"))
        assertEquals(listOf("bench", "db-bench"), search("Wyciskanie leżąc"))
        assertEquals(listOf("bench", "db-bench"), search("wyciskanie"))
    }

    @Test
    fun englishNamesAndAliasesMatchWithFeaturedFirst() {
        assertEquals(listOf("bench", "guillotine", "db-bench"), search("bench"))
        assertEquals(listOf("bench"), search("ławce płaskiej"))
    }

    @Test
    fun muscleAndEquipmentWordsMatchAfterNames() {
        assertEquals(listOf("own"), search("pośladki"))
        assertEquals(listOf("row"), search("plecy"))
    }

    @Test
    fun hiddenExercisesNeverAppear() {
        assertEquals(false, "hidden" in search("bench press hidden"))
        assertEquals(false, "hidden" in search(filter = ExerciseFilter(scope = ExerciseScope.MINE)))
    }

    @Test
    fun filtersNarrowByMuscleEquipmentAndScope() {
        assertEquals(listOf("db-bench"), search(filter = ExerciseFilter(equipment = setOf(Equipment.DUMBBELL))))
        assertEquals(listOf("row"), search(filter = ExerciseFilter(muscles = setOf(MuscleGroup.MIDDLE_BACK))))
        assertEquals(listOf("own"), search(filter = ExerciseFilter(scope = ExerciseScope.MINE)))
    }

    @Test
    fun noQueryListsFeaturedFirstThenByDisplayName() {
        assertEquals(listOf("bench", "guillotine", "own", "row", "db-bench"), search())
    }

    @Test
    fun duplicateFinderFlagsSimilarNamesOnly() {
        val hipThrust = exercise("hip", "Barbell Hip Thrust", "Hip thrust ze sztangą", muscle = MuscleGroup.GLUTES)
        val library = all + hipThrust
        assertEquals("hip", DuplicateFinder.findSimilar("Hip thrust na maszynie", library, excludeId = "own")?.id)
        assertEquals("bench", DuplicateFinder.findSimilar("wyciskanie sztangi lezac", library)?.id)
        assertNull(DuplicateFinder.findSimilar("Przysiad bułgarski", library))
        assertNull(DuplicateFinder.findSimilar("Press", library))
    }
}
