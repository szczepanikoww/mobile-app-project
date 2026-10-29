package com.trainingapp.feature.workoutbuilder.common

import com.trainingapp.domain.plan.alphabetLabel
import com.trainingapp.feature.workoutbuilder.resources.Res
import com.trainingapp.feature.workoutbuilder.resources.editor_day_default
import org.jetbrains.compose.resources.getString

/** "Dzień A", "Dzień B", … in the app language. A blank day name is shown and published as this. */
fun interface DefaultDayNames {
    suspend fun names(count: Int): List<String>
}

class ResourceDefaultDayNames : DefaultDayNames {
    override suspend fun names(count: Int): List<String> =
        List(count) { getString(Res.string.editor_day_default, alphabetLabel(it)) }
}
