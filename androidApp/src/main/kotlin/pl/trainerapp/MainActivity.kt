package pl.trainerapp

import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.provider.CalendarContract
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import com.trainingapp.app.App
import com.trainingapp.feature.workoutlogging.rest.AndroidRestTimerNotifier
import pl.trainer.domain.ClientSession

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
        setContent { App(openLiveWorkoutRequest = openWorkoutRequests, addToCalendar = ::addToCalendar) }
    }

    /**
     * "Dodaj do kalendarza" (G5, D6): the system calendar's insert screen, pre-filled. Only the time, the trainer and
     * the place are shared; no private notes, health data or chat content.
     */
    private fun addToCalendar(session: ClientSession) {
        val intent = Intent(Intent.ACTION_INSERT)
            .setData(CalendarContract.Events.CONTENT_URI)
            .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, session.session.start.toEpochMilliseconds())
            .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, session.session.end.toEpochMilliseconds())
            .putExtra(CalendarContract.Events.TITLE, getString(R.string.calendar_event_title, session.trainer.name))
            .putExtra(CalendarContract.Events.EVENT_LOCATION, session.session.location)
            .putExtra(CalendarContract.Events.EVENT_TIMEZONE, session.session.timeZone)
        try {
            startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            // No calendar app: nothing to open. The session stays in the app's own calendar.
        }
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
