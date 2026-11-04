package pl.trainerapp.feature.trainernotifications

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import pl.trainerapp.core.designsystem.AppTheme
import pl.trainerapp.feature.trainernotifications.data.TrainerNotificationsFixtures as Fx
import pl.trainerapp.feature.trainernotifications.model.NotificationDetail
import pl.trainerapp.feature.trainernotifications.model.NotificationsSnapshot

private val listState = NotificationListUiState(
    status = NotificationsLoadStatus.Content,
    snapshot = NotificationsSnapshot(Fx.items, Fx.now, isOffline = false, lastUpdated = Fx.now),
)

private val detailState = NotificationDetailUiState(
    status = NotificationsLoadStatus.Content,
    detail = NotificationDetail(Fx.items[1].copy(read = true), Fx.now),
)

@Composable
private fun ListFrame(state: NotificationListUiState) {
    AppTheme { TrainerNotificationListScreen(state, onAction = {}, onBack = {}) }
}

@Composable
private fun DetailFrame(state: NotificationDetailUiState) {
    AppTheme { TrainerNotificationDetailScreen(state, onAction = {}, onBack = {}) }
}

@Preview(showBackground = true, widthDp = 360, heightDp = 780, name = "Lista")
@Composable
private fun ListPreview() = ListFrame(listState)

@Preview(showBackground = true, widthDp = 360, heightDp = 780, fontScale = 1.5f, name = "Lista 1.5x")
@Composable
private fun ListLargeFontPreview() = ListFrame(listState)

@Preview(showBackground = true, widthDp = 360, heightDp = 780, name = "Lista: ładowanie")
@Composable
private fun ListLoadingPreview() = ListFrame(NotificationListUiState())

@Preview(showBackground = true, widthDp = 360, heightDp = 780, name = "Lista: pusta")
@Composable
private fun ListEmptyPreview() = ListFrame(
    listState.copy(snapshot = listState.snapshot?.copy(items = emptyList())),
)

@Preview(showBackground = true, widthDp = 360, heightDp = 780, name = "Szczegóły")
@Composable
private fun DetailPreview() = DetailFrame(detailState)

@Preview(showBackground = true, widthDp = 360, heightDp = 780, name = "Szczegóły: bez akcji")
@Composable
private fun DetailNoActionPreview() = DetailFrame(
    detailState.copy(detail = NotificationDetail(Fx.items.last(), Fx.now)),
)

@Preview(showBackground = true, widthDp = 360, heightDp = 780, name = "Szczegóły: błąd")
@Composable
private fun DetailErrorPreview() = DetailFrame(NotificationDetailUiState(status = NotificationsLoadStatus.Error))
