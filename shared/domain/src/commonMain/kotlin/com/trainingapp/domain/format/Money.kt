package com.trainingapp.domain.format

/**
 * Moved to `:contract` so the cancellation fee calculation can use it (client-shell-sessions-wallet plan, §5).
 * This alias keeps existing imports working for one release; new code imports [com.trainingapp.contract.money.Money].
 */
typealias Money = com.trainingapp.contract.money.Money
