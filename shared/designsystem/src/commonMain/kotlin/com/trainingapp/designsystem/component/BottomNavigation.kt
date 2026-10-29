package com.trainingapp.designsystem.component

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import pl.trainer.ui.SharedBottomBar
import pl.trainer.ui.SharedNavItem

data class NavItem(val label: String, val icon: ImageVector, val selected: Boolean, val onClick: () -> Unit)

@Composable
fun AppBottomNavigation(items: List<NavItem>, createLabel: String, onCreate: () -> Unit, modifier: Modifier = Modifier) {
    androidx.compose.foundation.layout.Box(modifier) {
        SharedBottomBar(items.map { SharedNavItem(it.label, it.icon, it.selected, it.onClick) }, createLabel, onCreate)
    }
}
