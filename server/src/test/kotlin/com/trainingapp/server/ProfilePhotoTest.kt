package com.trainingapp.server

import com.trainingapp.server.identity.PictureDownloader
import com.trainingapp.server.identity.ProfilePhotoService
import io.ktor.client.request.get
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.readRawBytes
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ProfilePhotoTest : ApiTest() {
    private val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 1, 2, 3, 4)
    private val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 9)

    @Test
    fun `a user uploads a photo, trainers are visible to everyone, clients only to themselves and their trainers`() = api { client ->
        val trainer = fixtures.trainer("photo-trainer")
        val clientId = fixtures.client("photo-client")
        fixtures.client("photo-stranger")

        val upload = client.put("/v1/me/photo") { auth("photo-client"); contentType(ContentType.Image.JPEG); setBody(jpeg) }
        assertEquals(HttpStatusCode.NoContent, upload.status)
        client.put("/v1/me/photo") { auth("photo-trainer"); contentType(ContentType.Image.PNG); setBody(png) }

        val own = client.get("/v1/users/$clientId/photo") { auth("photo-client") }
        assertEquals(HttpStatusCode.OK, own.status)
        assertContentEquals(jpeg, own.readRawBytes())
        assertContentEquals(jpeg, client.get("/v1/me/photo") { auth("photo-client") }.readRawBytes())
        assertEquals(HttpStatusCode.NotFound, client.get("/v1/me/photo") { auth("photo-stranger") }.status)
        // A trainer's photo is their public face; a client's photo is not shown to strangers.
        assertEquals(HttpStatusCode.OK, client.get("/v1/users/${trainer.id}/photo") { auth("photo-stranger") }.status)
        assertEquals(HttpStatusCode.NotFound, client.get("/v1/users/$clientId/photo") { auth("photo-stranger") }.status)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/v1/users/$clientId/photo").status)
    }

    @Test
    fun `only images are accepted`() = api { client ->
        fixtures.client("photo-text")
        val bad = client.put("/v1/me/photo") { auth("photo-text"); contentType(ContentType.Image.JPEG); setBody("not an image".encodeToByteArray()) }
        assertEquals(HttpStatusCode.UnprocessableEntity, bad.status)
    }

    @Test
    fun `the Google picture is copied once and never replaces an upload`() = runBlocking {
        val google = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 7, 7, 7)
        val fetched = mutableListOf<String>()
        val photos = ProfilePhotoService(db, PictureDownloader { url -> fetched += url; "image/jpeg" to google })
        val id = fixtures.client("google-user")

        // Not a Google picture host: never fetched.
        photos.copyGooglePicture(id, "https://example.com/me.jpg")
        assertEquals(emptyList(), fetched)

        photos.copyGooglePicture(id, "https://lh3.googleusercontent.com/a/abc=s96-c")
        assertEquals(listOf("https://lh3.googleusercontent.com/a/abc=s256-c"), fetched)
        assertContentEquals(google, photos.photo(id, id).bytes)

        photos.upload(id, jpeg)
        ProfilePhotoService(db, PictureDownloader { "image/jpeg" to google }).copyGooglePicture(id, "https://lh3.googleusercontent.com/a/abc=s96-c")
        assertContentEquals(jpeg, photos.photo(id, id).bytes)
        assertNull(ProfilePhotoService.googlePictureUrl("http://lh3.googleusercontent.com/a/abc"))
    }

    @Test
    fun `the demo accounts get their photos, also in an existing database, without replacing an upload`() = runBlocking {
        val seed = com.trainingapp.server.dev.DemoSeed(services)
        seed.seedIfEmpty()
        assertEquals(1, count("SELECT count(*) FROM identity.profile_photo p JOIN identity.app_user u ON u.id = p.user_id WHERE u.auth_subject = 'demo-client'"))
        assertEquals(1, count("SELECT count(*) FROM identity.profile_photo p JOIN identity.app_user u ON u.id = p.user_id WHERE u.auth_subject = 'demo-trainer'"))
        val clientId = services.identity.resolve(com.trainingapp.server.http.TokenIdentity("demo-client", null))!!.id
        services.photos.upload(clientId, jpeg)
        seed.seedPhotos()
        assertContentEquals(jpeg, services.photos.photo(clientId, clientId).bytes)
    }
}
