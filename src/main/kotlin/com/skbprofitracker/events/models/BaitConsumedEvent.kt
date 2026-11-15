package com.skbprofitracker.events.models

data class BaitConsumedEvent(
    val baitName: String,
    val baitDisplayName: String,
    val baitId: String
)
