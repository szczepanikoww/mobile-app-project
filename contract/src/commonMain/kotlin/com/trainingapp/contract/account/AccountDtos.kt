package com.trainingapp.contract.account

import kotlinx.serialization.Serializable

/** A role is held when its profile row exists (G4). The active role is chosen in the app only. */
@Serializable
enum class RoleDto { TRAINER, CLIENT }

/** `GET /v1/me`. A token without an account gets `404 NO_ACCOUNT`; the app then sends [CreateAccountRequest]. */
@Serializable
data class MeDto(
    val userId: String,
    val displayName: String,
    val roles: List<RoleDto>,
    val locale: String,
    val timeZone: String,
)

/**
 * `POST /v1/accounts` after the identity provider created the user. Idempotent: the IdP subject is unique, so a retry
 * returns the existing account. The e-mail comes from the verified token, never from this body.
 */
@Serializable
data class CreateAccountRequest(
    val operationId: String,
    val name: String,
    val startingRole: RoleDto,
    val locale: String = "pl",
    val timeZone: String = "Europe/Warsaw",
    val acceptedTermsVersion: String,
    val acceptedPrivacyVersion: String,
)

/** `PATCH /v1/me`. `locale` is `pl` or `en`. */
@Serializable
data class UpdateMeRequest(val locale: String? = null, val timeZone: String? = null)

@Serializable
enum class PushPlatform { ANDROID, IOS }

/** `PUT /v1/me/push-tokens/{token}`. */
@Serializable
data class PushTokenRequest(val platform: PushPlatform)
