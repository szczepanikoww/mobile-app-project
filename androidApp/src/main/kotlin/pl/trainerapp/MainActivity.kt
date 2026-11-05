package pl.trainerapp

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import com.trainingapp.app.App
import com.trainingapp.feature.workoutlogging.rest.AndroidRestTimerNotifier

class MainActivity : ComponentActivity() {
    /** Incremented when the rest-timer notification is tapped; the app then opens the live workout. */
    private var openWorkoutRequests by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
        )
        if (savedInstanceState == null) handleIntent(intent)
        // The whole UI lives in :shared and :shared:app, so Android and desktop render the same shells.
        setContent { App(openLiveWorkoutRequest = openWorkoutRequests) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.getBooleanExtra(AndroidRestTimerNotifier.EXTRA_OPEN_WORKOUT, false) == true) {
            intent.removeExtra(AndroidRestTimerNotifier.EXTRA_OPEN_WORKOUT)
            openWorkoutRequests++
        }
    }
}
