package pl.trainerapp

import android.graphics.Color
import android.os.Bundle
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import pl.trainer.presentation.Destination
import pl.trainer.presentation.TrainerViewModel
import pl.trainer.data.DemoTrainerRepository
import pl.trainer.ui.TrainerApp
import pl.trainerapp.shell.TrainerDestination
import com.trainingapp.app.App
import com.trainingapp.app.WorkoutCreateActions
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import pl.trainerapp.core.designsystem.AppTheme
import pl.trainerapp.feature.trainerchats.data.DemoTrainerChatsRepository
import pl.trainerapp.feature.trainerfinance.data.DemoTrainerFinanceRepository
import pl.trainerapp.feature.trainerhome.data.DemoTrainerHomeRepository
import pl.trainerapp.feature.trainernotifications.data.DemoTrainerNotificationsRepository
import pl.trainerapp.feature.trainerprofile.data.DemoTrainerProfileRepository
import pl.trainerapp.shell.TrainerRepositories
import pl.trainerapp.shell.TrainerShell

class MainActivity : ComponentActivity() {
    // Explicit demo data sources: no backend exists yet.
    private val repositories = TrainerRepositories(
        home = DemoTrainerHomeRepository(),
        finance = DemoTrainerFinanceRepository(),
        chats = DemoTrainerChatsRepository(),
        notifications = DemoTrainerNotificationsRepository(),
        profile = DemoTrainerProfileRepository(),
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
        )
        setContent {
            App { actions ->
              AppTheme {
                val trainer: TrainerViewModel = viewModel { TrainerViewModel(DemoTrainerRepository()) }
                TrainerShell(repositories, trainerFeature = { destination, createRequest, onMain ->
                    LaunchedEffect(destination) {
                        trainer.destination(if (destination == TrainerDestination.Calendar) Destination.Calendar else Destination.Clients)
                    }
                    TrainerApp(trainer, showNavigation = false, createRequest = createRequest, onMainRouteChanged = onMain,
                        extraCreateActions = { dismiss -> WorkoutCreateActions(actions, dismiss) })
                })
              }
            }
        }
    }
}
