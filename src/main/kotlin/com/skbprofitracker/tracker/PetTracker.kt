package com.skbprofitracker.tracker

import com.skbprofitracker.events.EventBus
import com.skbprofitracker.events.models.PetLevelUpEvent
import com.skbprofitracker.utils.CommonUtils
import com.skbprofitracker.utils.ItemUtils

/**
 * Records the value a pet gains by reaching its max level.
 *
 * A pet that drops is counted at its level 1 price by the normal pickup path, so only the
 * upgrade premium is booked here; together the two add up to the price of the maxed pet
 * without counting anything twice.
 *
 * The level is read from chat (`Your X leveled up to level 100!`), which is the only signal
 * the client gets for a pet that was already in the inventory, and which fires the moment
 * the level is reached rather than when the pet menu is opened.
 */
object PetTracker {
    fun init() {
        EventBus.subscribe(PetLevelUpEvent::class) { onPetLevelUp(it) }
    }

    private fun onPetLevelUp(event: PetLevelUpEvent) {
        CommonUtils.runWithCatching("Failed to record a maxed pet") {
            val activity = ActivityBucket.bucketForPickup() ?: return@runWithCatching
            val key = ItemUtils.getMaxedPetId(event.petDisplayName, event.level)
            BucketLedger.add(activity, key, 1)
        }
    }
}
