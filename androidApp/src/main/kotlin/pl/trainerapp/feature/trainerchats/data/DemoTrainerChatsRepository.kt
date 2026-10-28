package pl.trainerapp.feature.trainerchats.data

import kotlinx.coroutines.delay
import pl.trainerapp.feature.trainerchats.TrainerChatsRepository
import pl.trainerapp.feature.trainerchats.model.ChatMessage
import pl.trainerapp.feature.trainerchats.model.ChatThread
import pl.trainerapp.feature.trainerchats.model.Conversation
import pl.trainerapp.feature.trainerchats.model.ConversationsSnapshot
import java.time.Instant
import java.time.ZoneId

/** Deterministic synthetic data. Used by previews, tests and the explicit demo data source. */
object TrainerChatsFixtures {
    val zone: ZoneId = ZoneId.of("Europe/Warsaw")

    /** 09:15 in Warsaw. */
    val now: Instant = Instant.parse("2026-10-28T08:15:00Z")

    /** Same client ids as the home fixtures, so "Napisz" on a session opens the matching thread. */
    val clients = linkedMapOf(
        "c1" to "Kasia Nowak",
        "c2" to "Tomek Wójcik",
        "c3" to "Magda Brzeska",
        "c5" to "Marek Lis",
        "c6" to "Ania Kruk",
        "c7" to "Piotr Szymański",
        "c8" to "Kamil Nowicki",
    )

    private fun msg(id: String, fromTrainer: Boolean, text: String, at: String) =
        ChatMessage(id, fromTrainer, text, Instant.parse(at))

    val threads: Map<String, List<ChatMessage>> = mapOf(
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
            msg("c6-1", false, "Wysłałam wideo martwego ciągu, zerkniesz?", "2026-10-28T06:50:00Z"),
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

    val unread: Map<String, Int> = mapOf("c6" to 1, "c7" to 2)
}

/** Demo data source: not connected to any backend. State is kept in memory so sent messages stay. */
class DemoTrainerChatsRepository(private val latencyMillis: Long = 400) : TrainerChatsRepository {

    private val threads = TrainerChatsFixtures.threads.mapValues { it.value.toMutableList() }.toMutableMap()
    private val unread = TrainerChatsFixtures.unread.toMutableMap()
    private var clock: Instant = TrainerChatsFixtures.now
    private var counter = 0

    override suspend fun conversations(): ConversationsSnapshot {
        delay(latencyMillis)
        val list = TrainerChatsFixtures.clients.mapNotNull { (id, name) ->
            val last = threads[id]?.lastOrNull() ?: return@mapNotNull null
            Conversation(id, name, last.text, last.at, TrainerChatsFixtures.zone, last.fromTrainer, unread[id] ?: 0)
        }.sortedByDescending { it.lastAt }
        return ConversationsSnapshot(list, generatedAt = clock, isOffline = false, lastUpdated = clock)
    }

    override suspend fun thread(clientId: String): ChatThread {
        delay(latencyMillis)
        unread.remove(clientId)
        return ChatThread(
            clientId = clientId,
            clientName = TrainerChatsFixtures.clients[clientId] ?: clientId,
            messages = threads[clientId].orEmpty().toList(),
            generatedAt = clock,
            zone = TrainerChatsFixtures.zone,
        )
    }

    override suspend fun send(clientId: String, text: String): ChatMessage {
        delay(latencyMillis)
        clock = clock.plusSeconds(60)
        val message = ChatMessage("sent-${counter++}", fromTrainer = true, text = text, at = clock)
        threads.getOrPut(clientId) { mutableListOf() } += message
        return message
    }
}
