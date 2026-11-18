package com.trainingapp.server

import com.trainingapp.contract.api.ApiError
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import kotlin.test.Test
import kotlin.test.assertEquals

/** The trainer API uses the shared token verifier (docs/plans/firebase-auth.md §4). */
class TrainerAuthTest {

    @Test
    fun `a missing or rejected token is 401 with the trainer error body`() = apiTest {
        val missing = client().get("/api/v1/me")
        assertEquals(HttpStatusCode.Unauthorized, missing.status)
        assertEquals("UNAUTHENTICATED", missing.body<ApiError>().code)

        val invalid = client().get("/api/v1/me") { bearerAuth("not-a-valid-token") }
        assertEquals(HttpStatusCode.Unauthorized, invalid.status)
        assertEquals("UNAUTHENTICATED", invalid.body<ApiError>().code)
    }

    @Test
    fun `the dev token endpoint issues the same token the client API accepts`() = apiTest {
        assertEquals(HttpStatusCode.OK, signedIn("demo-trainer").get("/api/v1/me").status)
        assertEquals(HttpStatusCode.OK, client().get("/api/v1/me") { bearerAuth("dev.demo-trainer") }.status)
    }
}
