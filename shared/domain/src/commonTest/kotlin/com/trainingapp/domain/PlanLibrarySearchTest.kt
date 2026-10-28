package com.trainingapp.domain

import com.trainingapp.domain.plan.PlanLibraryItem
import com.trainingapp.domain.plan.PlanLibrarySearch
import com.trainingapp.domain.plan.PlanListFilter
import kotlin.test.Test
import kotlin.test.assertEquals

class PlanLibrarySearchTest {
    private fun item(name: String, templateId: String?, tags: List<String> = emptyList()) =
        PlanLibraryItem(templateId, if (templateId == null) "d-$name" else null, name, 1, 2, templateId?.let { 1 }, 0, tags = tags)

    private val items = listOf(
        item("Hipertrofia Góra A", "t1", tags = listOf("Siła")),
        item("Dół B – szkic", null),
        item("Mobilność", "t2"),
    )

    @Test
    fun filtersDraftsAndPublished() {
        assertEquals(listOf("Dół B – szkic"), PlanLibrarySearch.search(items, "", PlanListFilter.DRAFTS).map { it.name })
        assertEquals(2, PlanLibrarySearch.search(items, "", PlanListFilter.PUBLISHED).size)
    }

    @Test
    fun searchesNamesAndTagsWithoutAccents() {
        assertEquals(listOf("Hipertrofia Góra A"), PlanLibrarySearch.search(items, "gora", PlanListFilter.ALL).map { it.name })
        assertEquals(listOf("Hipertrofia Góra A"), PlanLibrarySearch.search(items, "sila", PlanListFilter.ALL).map { it.name })
        assertEquals(listOf("Dół B – szkic"), PlanLibrarySearch.search(items, "dol", PlanListFilter.ALL).map { it.name })
    }
}
