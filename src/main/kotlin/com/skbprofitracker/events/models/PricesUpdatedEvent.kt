package com.skbprofitracker.events.models

/**
 * Fired when price data was successfully updated.
 *
 * @param bazaarUpdated true if bazaar prices were updated in this cycle
 * @param auctionUpdated true if auction prices were updated in this cycle
 */
data class PricesUpdatedEvent(
    val bazaarUpdated: Boolean,
    val auctionUpdated: Boolean
)

