package pl.trainerapp.core.designsystem

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import pl.trainer.ui.EmptyContent
import pl.trainer.ui.Layout

/*
 * Building blocks that give the home, finance and other main-shell screens the same look as the calendar and
 * clients views: flat white tiles with a fine outline, the shared spacing scale and the shared empty/error states.
 */

/** The shell's top bar: white surface, 64 dp, title on the left and icon actions on the right, as in the calendar. */
@Composable
fun ScreenTopBar(
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit = {},
    title: @Composable () -> Unit,
) {
    Surface(modifier = modifier, color = MaterialTheme.colorScheme.surface) {
        Row(
            Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.statusBars)
                .heightIn(min = Layout.headerHeight)
                .padding(start = Layout.gutter, end = Layout.small, top = Layout.small, bottom = Layout.small),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.weight(1f)) { title() }
            actions()
        }
    }
}

/** Flat white tile with the shared outline, as used by session and client rows. */
@Composable
fun OutlinedTile(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    color: Color = MaterialTheme.colorScheme.surface,
    content: @Composable ColumnScope.() -> Unit,
) {
    val border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    val inner: @Composable () -> Unit = {
        Column(Modifier.padding(Layout.padding), verticalArrangement = Arrangement.spacedBy(Layout.small), content = content)
    }
    if (onClick != null) {
        Surface(onClick = onClick, modifier = modifier, shape = Layout.tile, color = color, border = border, content = inner)
    } else {
        Surface(modifier = modifier, shape = Layout.tile, color = color, border = border, content = inner)
    }
}

/** Screen-level section heading on the shared type scale. */
@Composable
fun ScreenSectionTitle(title: String, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    Row(modifier.fillMaxWidth().heightIn(min = Layout.target), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f).semantics { heading() })
        action?.invoke()
    }
}

/** Icon in a tinted circle, decorative only. */
@Composable
fun IconCircle(icon: ImageVector, container: Color, tint: Color, size: Int = 40) {
    Box(
        Modifier.size(size.dp).background(container, CircleShape).clearAndSetSemantics { },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size((size / 2).dp))
    }
}

/** Centered progress, as in the calendar and clients views. */
@Composable
fun ScreenLoading(text: String, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize().padding(vertical = Layout.large), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Layout.padding)) {
            CircularProgressIndicator()
            Text(text, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/** Recoverable load failure, as in the calendar and clients views. */
@Composable
fun ScreenError(title: String, body: String, retryLabel: String, onRetry: () -> Unit) {
    EmptyContent(Icons.Outlined.CloudOff, title, body, retryLabel, onRetry)
}
