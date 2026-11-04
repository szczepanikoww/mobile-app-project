package com.trainingapp.feature.guest.welcome

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.EventAvailable
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.trainingapp.designsystem.component.AppTextButton
import com.trainingapp.designsystem.component.PrimaryButton
import com.trainingapp.designsystem.component.TonalButton
import com.trainingapp.designsystem.theme.AppTheme
import com.trainingapp.designsystem.theme.Sizes
import com.trainingapp.designsystem.theme.Spacing
import com.trainingapp.feature.guest.component.PagerDots
import com.trainingapp.feature.guest.resources.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

private data class Slide(val icon: ImageVector, val title: StringResource, val body: StringResource)

private val Slides = listOf(
    Slide(Icons.Outlined.EventAvailable, Res.string.welcome_slide1_title, Res.string.welcome_slide1_body),
    Slide(Icons.Outlined.Videocam, Res.string.welcome_slide2_title, Res.string.welcome_slide2_body),
    Slide(Icons.Outlined.Forum, Res.string.welcome_slide3_title, Res.string.welcome_slide3_body),
)

private const val AutoAdvanceMs = 4_500L

/**
 * The first screen: three short slides and three ways forward. No header (it is the first screen) and no product
 * name, because the name is not final (D15).
 *
 * [autoAdvance] is false with reduced motion or an active screen reader; the slides also stop on the first touch.
 */
@Composable
fun WelcomeScreen(
    onRegister: () -> Unit,
    onLogIn: () -> Unit,
    onBrowse: () -> Unit,
    autoAdvance: Boolean = true,
) {
    val pager = rememberPagerState { Slides.size }
    val scope = rememberCoroutineScope()
    var touched by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(autoAdvance, touched) {
        if (!autoAdvance || touched) return@LaunchedEffect
        while (true) {
            delay(AutoAdvanceMs)
            if (touched) break
            pager.animateScrollToPage((pager.currentPage + 1) % Slides.size)
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(pass = PointerEventPass.Initial)
                    touched = true
                }
            },
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            Modifier
                .widthIn(max = Sizes.contentWidth)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.gutter, vertical = Spacing.xxl),
            verticalArrangement = Arrangement.spacedBy(Spacing.xxl),
        ) {
            HorizontalPager(state = pager, modifier = Modifier.fillMaxWidth()) { page ->
                SlideContent(Slides[page])
            }
            PagerDots(
                count = Slides.size,
                current = pager.currentPage,
                onSelect = { index ->
                    touched = true
                    scope.launch { pager.animateScrollToPage(index) }
                },
                modifier = Modifier.fillMaxWidth(),
            )
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.m)) {
                PrimaryButton(stringResource(Res.string.create_account), onClick = onRegister, modifier = Modifier.fillMaxWidth(),
                    icon = Icons.AutoMirrored.Outlined.ArrowForward)
                TonalButton(stringResource(Res.string.log_in), onClick = onLogIn, modifier = Modifier.fillMaxWidth())
                AppTextButton(stringResource(Res.string.browse_without_account), onClick = onBrowse, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun SlideContent(slide: Slide) {
    Column(
        Modifier.fillMaxWidth().padding(top = Spacing.xxxl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Spacing.l),
    ) {
        Surface(color = AppTheme.colors.softAccent, shape = CircleShape) {
            Icon(slide.icon, contentDescription = null, tint = AppTheme.colors.brandStrong, modifier = Modifier.padding(Spacing.xl).size(40.dp))
        }
        Text(stringResource(slide.title), style = AppTheme.type.screenTitle, color = AppTheme.colors.text, textAlign = TextAlign.Center,
            modifier = Modifier.semantics { heading() })
        Text(stringResource(slide.body), style = AppTheme.type.body, color = AppTheme.colors.textSecondary, textAlign = TextAlign.Center)
    }
}
