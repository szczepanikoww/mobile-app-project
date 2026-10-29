package com.trainingapp.data

import app.cash.sqldelight.db.SqlDriver
import com.trainingapp.contract.media.CreateUploadSessionRequest
import com.trainingapp.contract.media.MediaKind
import com.trainingapp.contract.media.MediaStatusDto
import com.trainingapp.contract.media.RemoteMediaState
import com.trainingapp.data.db.createTrainingDatabase
import com.trainingapp.data.media.FakeMediaRemoteDataSource
import com.trainingapp.data.media.LocalMediaRepository
import com.trainingapp.data.media.MediaRemoteDataSource
import com.trainingapp.data.media.UploadResult
import com.trainingapp.domain.media.MediaState
import com.trainingapp.domain.media.PickedVideo
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MediaRepositoryTest {

    private val network = TestNetwork()
    private val video = PickedVideo(localPath = "/data/videos/hip.mp4", sizeBytes = 4_000_000, durationMs = 25_000, thumbnailPath = null)

    /** Background uploads run on the test scheduler; see ExerciseRepositoryTest for why this is not backgroundScope. */
    private fun mediaTest(
        remote: MediaRemoteDataSource = FakeMediaRemoteDataSource(network),
        driver: SqlDriver = memoryDriver(),
        block: suspend TestScope.(LocalMediaRepository) -> Unit,
    ) = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = CoroutineScope(dispatcher)
        try {
            block(LocalMediaRepository(createTrainingDatabase(driver), remote, network, trainer, TestIds("media"), TestClock(), scope, dispatcher))
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun importedVideoUploadsThenProcessesThenIsReady() = mediaTest { repository ->
        val asset = repository.import(video, MediaKind.EXERCISE_DEMO)
        assertEquals(MediaState.QUEUED, asset.state)
        assertNull(asset.playbackUrl)
        advanceUntilIdle()
        val ready = repository.observe(asset.id).first()!!
        assertEquals(MediaState.READY, ready.state)
        assertEquals("file:///data/videos/hip.mp4", ready.playbackUrl)
    }

    @Test
    fun offlineVideoWaitsAndUploadsWhenBackOnline() = mediaTest { repository ->
        network.isOnline.value = false
        advanceUntilIdle()
        val asset = repository.import(video, MediaKind.EXERCISE_DEMO)
        advanceUntilIdle()
        assertEquals(MediaState.QUEUED, repository.observe(asset.id).first()!!.state)

        network.isOnline.value = true
        advanceUntilIdle()
        assertEquals(MediaState.READY, repository.observe(asset.id).first()!!.state)
    }

    @Test
    fun progressIsVisibleAndNothingIsReadyBeforeTheServerConfirms() {
        val gate = CompletableDeferred<Unit>()
        val seen = mutableListOf<MediaState>()
        val slowRemote = object : MediaRemoteDataSource {
            override suspend fun upload(request: CreateUploadSessionRequest, localPath: String, onProgress: suspend (Float) -> Unit): UploadResult {
                onProgress(0.5f)
                gate.await()
                onProgress(1f)
                return UploadResult.Uploaded
            }
            override suspend fun status(mediaId: String) = MediaStatusDto(mediaId, RemoteMediaState.PROCESSING)
        }
        mediaTest(remote = slowRemote) { repository ->
            val asset = repository.import(video, MediaKind.EXERCISE_DEMO)
            advanceUntilIdle()
            val uploading = repository.observe(asset.id).first()!!
            assertEquals(MediaState.UPLOADING, uploading.state)
            assertEquals(0.5f, uploading.progress)
            seen += uploading.state

            gate.complete(Unit)
            advanceUntilIdle()
            val processing = repository.observe(asset.id).first()!!
            assertEquals(MediaState.PROCESSING, processing.state)
            assertNull(processing.playbackUrl)
        }
        assertTrue(MediaState.READY !in seen)
    }

    @Test
    fun rejectedUploadFailsAndCanBeRetried() {
        val remote = FakeMediaRemoteDataSource(network).apply { rejectNext = true }
        mediaTest(remote = remote) { repository ->
            val asset = repository.import(video, MediaKind.EXERCISE_DEMO)
            advanceUntilIdle()
            assertEquals(MediaState.FAILED, repository.observe(asset.id).first()!!.state)

            repository.retry(asset.id)
            advanceUntilIdle()
            assertEquals(MediaState.READY, repository.observe(asset.id).first()!!.state)
        }
    }

    @Test
    fun uploadInterruptedByProcessDeathResumesAfterRestart() {
        val file = Files.createTempFile("media", ".db").toFile().apply { deleteOnExit() }
        var mediaId = ""
        // First process: the upload starts and the connection drops halfway.
        val dropping = object : MediaRemoteDataSource {
            override suspend fun upload(request: CreateUploadSessionRequest, localPath: String, onProgress: suspend (Float) -> Unit): UploadResult {
                onProgress(0.4f)
                return UploadResult.Interrupted
            }
            override suspend fun status(mediaId: String): MediaStatusDto? = null
        }
        val first = fileDriver(file)
        mediaTest(remote = dropping, driver = first) { repository ->
            mediaId = repository.import(video, MediaKind.EXERCISE_DEMO).id
            advanceUntilIdle()
            val queued = repository.observe(mediaId).first()!!
            assertEquals(MediaState.QUEUED, queued.state)
            assertEquals(0.4f, queued.progress)
        }
        first.close()

        // Second process: the queued upload is sent when the repository starts online.
        val second = fileDriver(file)
        mediaTest(driver = second) { repository ->
            advanceUntilIdle()
            assertEquals(MediaState.READY, repository.observe(mediaId).first()!!.state)
        }
        second.close()
    }
}
