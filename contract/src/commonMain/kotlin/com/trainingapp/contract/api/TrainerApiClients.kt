package com.trainingapp.contract.api

import kotlinx.serialization.Serializable

/** A public gym a trainer can work at (profile editor). */
@Serializable
data class GymDto(val id: String, val name: String, val city: String, val street: String)
