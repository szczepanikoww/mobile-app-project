package com.trainingapp.designsystem.component

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import pl.trainer.ui.SharedBottomBar
import pl.trainer.ui.SharedNavItem

data class NavItem(val label: String, val icon: ImageVector, val selected: Boolean, val onClick: () -> Unit)

/**
 * The role-aware bottom bar. The guest shell has no create action, so [onCreate] is optional. The trainer's centre
 * action is "+" (the default [createIcon]); the client's starts or resumes the workout (D3).
 */
@Composable
fun AppBottomNavigation(
    items: List<NavItem>,
    createLabel: String? = null,
    onCreate: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    createIcon: ImageVector? = null,
) {
    androidx.compose.foundation.layout.Box(modifier) {
        SharedBottomBar(items.map { SharedNavItem(it.label, it.icon, it.selected, it.onClick) }, createLabel, onCreate, createIcon = createIcon)
    }
}
