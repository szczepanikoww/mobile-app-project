package com.trainingapp.feature.guest

import com.trainingapp.domain.common.NetworkMonitor
import kotlinx.coroutines.flow.MutableStateFlow

class FakeNetwork(online: Boolean = true) : NetworkMonitor {
    override val isOnline = MutableStateFlow(online)
}
