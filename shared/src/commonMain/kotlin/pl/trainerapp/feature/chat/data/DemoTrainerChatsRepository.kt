package pl.trainerapp.feature.chat.data

import kotlinx.coroutines.delay
import pl.trainerapp.feature.chat.ClientChatRepository
import pl.trainerapp.feature.chat.TrainerChatsRepository
import pl.trainerapp.feature.chat.model.ChatMessage
import pl.trainerapp.feature.chat.model.ChatRole
import pl.trainerapp.feature.chat.model.ChatThread
import pl.trainerapp.feature.chat.model.ConversationsSnapshot
import pl.trainerapp.feature.chat.model.MediaRef
import kotlinx.datetime.TimeZone
import kotlin.time.Instant

/** Synthetic conversations. Used by previews, tests and the explicit demo data source. */
interface ChatFixtures {
    val zone: TimeZone
    val now: Instant
    val trainerName: String
    val clients: Map<String, String>
    val threads: Map<String, List<ChatMessage>>
    val unread: Map<String, Int>
}

/** Deterministic synthetic data. Relationship ids match the home fixtures, so "Napisz" opens the matching thread. */
object TrainerChatsFixtures : ChatFixtures {
    override val zone: TimeZone = TimeZone.of("Europe/Warsaw")

    /** 09:15 in Warsaw. */
    override val now: Instant = Instant.parse("2026-10-28T08:15:00Z")

    override val trainerName = "Michał"

    override val clients = linkedMapOf(
        "c1" to "Kasia Nowak",
        "c2" to "Tomek Wójcik",
        "c3" to "Magda Brzeska",
        "c5" to "Marek Lis",
        "c6" to "Ania Kruk",
        "c7" to "Piotr Szymański",
        "c8" to "Kamil Nowicki",
    )

    /** The technique video behind the trainer home's "Wymaga uwagi" item (media id "m1"). */
    val aniaDeadliftVideo = MediaRef(
        mediaId = "m1",
        exerciseName = "Martwy ciąg",
        setNumber = 3,
        setCount = 4,
        weightGrams = 110_000,
        reps = 5,
        durationMs = 24_000,
        sizeBytes = 18L * 1024 * 1024,
    )

    private fun msg(id: String, fromTrainer: Boolean, text: String, at: String, attachment: MediaRef? = null) =
        ChatMessage(id, if (fromTrainer) ChatRole.Trainer else ChatRole.Client, text, Instant.parse(at), attachment)

    override val threads: Map<String, List<ChatMessage>> = mapOf(
        "c1" to listOf(
            msg("c1-1", false, "Cześć! Czy dziś o 9 aktualne?", "2026-10-27T17:02:00Z"),
            msg("c1-2", true, "Cześć Kasiu, tak, widzimy się w Siłowni Centrum.", "2026-10-27T17:10:00Z"),
            msg("c1-3", false, "Super, do zobaczenia!", "2026-10-27T17:12:00Z"),
        ),
        "c2" to listOf(
            msg("c2-1", true, "Tomek, pamiętaj o rozgrzewce przed nogami.", "2026-10-26T19:00:00Z"),
            msg("c2-2", false, "Jasne, dzięki za przypomnienie.", "2026-10-26T19:20:00Z"),
        ),
        "c3" to listOf(
            msg("c3-1", false, "Czy mogę przesunąć trening na 13:00?", "2026-10-27T12:00:00Z"),
            msg("c3-2", true, "Sprawdzę grafik i potwierdzę dziś rano.", "2026-10-27T12:30:00Z"),
        ),
        "c5" to listOf(
            msg("c5-1", true, "Marek, przypominam o rozliczeniu pakietu.", "2026-10-25T09:00:00Z"),
            msg("c5-2", false, "Przelew poszedł wczoraj.", "2026-10-27T15:40:00Z"),
        ),
        "c6" to listOf(
            msg("c6-1", false, "Wysłałam wideo martwego ciągu, zerkniesz?", "2026-10-28T06:50:00Z", aniaDeadliftVideo),
        ),
        "c7" to listOf(
            msg("c7-1", false, "Dzień dobry, mam pytanie o dietę.", "2026-10-28T07:20:00Z"),
            msg("c7-2", false, "Czy białko po treningu musi być w ciągu godziny?", "2026-10-28T07:22:00Z"),
        ),
        "c8" to listOf(
            msg("c8-1", true, "Kamil, plan na piątek wysłany.", "2026-10-24T10:00:00Z"),
            msg("c8-2", false, "Dzięki, zobaczę wieczorem.", "2026-10-24T11:30:00Z"),
        ),
    )

    override val unread: Map<String, Int> = mapOf("c6" to 1, "c7" to 2)
}

/** The trainer's view of [DemoChatStore]. Not connected to any backend. */
class DemoTrainerChatsRepository(
    private val store: DemoChatStore = DemoChatStore(),
    private val latencyMillis: Long = 400,
) : TrainerChatsRepository {

    override suspend fun conversations(): ConversationsSnapshot {
        delay(latencyMillis)
        val now = store.now
        return ConversationsSnapshot(store.conversations(ChatRole.Trainer), generatedAt = now, isOffline = false, lastUpdated = now)
    }

    override suspend fun thread(clientId: String): ChatThread {
        delay(latencyMillis)
        return store.open(clientId, ChatRole.Trainer)
    }

    override suspend fun send(clientId: String, text: String): ChatMessage {
        delay(latencyMillis)
        return store.send(clientId, ChatRole.Trainer, text)
    }

    override suspend fun replyToVideo(clientId: String, mediaId: String, text: String): ChatMessage {
        delay(latencyMillis)
        return store.send(clientId, ChatRole.Trainer, text, quotedMediaId = mediaId)
    }
}

/** The client's view of the same [DemoChatStore]: one conversation with their trainer. */
class DemoClientChatRepository(
    private val store: DemoChatStore,
    override val relationshipId: String,
    private val latencyMillis: Long = 400,
) : ClientChatRepository {

    override suspend fun thread(): ChatThread {
        delay(latencyMillis)
        return store.open(relationshipId, ChatRole.Client)
    }

    override suspend fun send(text: String): ChatMessage {
        delay(latencyMillis)
        return store.send(relationshipId, ChatRole.Client, text)
    }
}
