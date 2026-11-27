package com.skbprofitracker.price

import com.google.gson.JsonParser
import com.skbprofitracker.SkbMod
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Live price cache.
 *
 * Prices are never persisted: every restart starts from an empty cache and
 * fills it from the two public APIs. While a lookup misses, the tracker treats
 * the item as unpriced rather than guessing.
 */
object PriceCache {
    private const val BAZAAR_URL = "https://api.hypixel.net/skyblock/bazaar"
    private const val AUCTION_URL = "https://api.eliteskyblock.com/resources/auctions/neu"

    private const val BAZAAR_REFRESH_SECONDS = 300L
    private const val AUCTION_REFRESH_SECONDS = 120L

    private val client: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(15))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()

    private val scheduler = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "skb-profit-prices").apply { isDaemon = true }
    }

    private data class Prices(
        val bazaarInstaSell: Map<String, Double> = emptyMap(),
        val bazaarSellOffer: Map<String, Double> = emptyMap(),
        val auctionLbin: Map<String, Double> = emptyMap(),
        val revision: Long = 0L,
    )

    @Volatile
    private var prices = Prices()

    internal val revision: Long get() = prices.revision

    @Volatile
    var lastBazaarUpdateMs: Long = 0L
        private set

    @Volatile
    var lastAuctionUpdateMs: Long = 0L
        private set

    fun init() {
        // Both fetches block on HTTP, so they must not run on the client thread.
        scheduler.execute(::refreshBazaar)
        scheduler.execute(::refreshAuctions)
        scheduler.scheduleAtFixedRate(::refreshBazaar, BAZAAR_REFRESH_SECONDS, BAZAAR_REFRESH_SECONDS, TimeUnit.SECONDS)
        scheduler.scheduleAtFixedRate(
            ::refreshAuctions,
            AUCTION_REFRESH_SECONDS,
            AUCTION_REFRESH_SECONDS,
            TimeUnit.SECONDS,
        )
    }

    /**
     * Resolves a price for [key] in the given [mode].
     *
     * Order is bazaar -> auction lowest bin -> `null` (unpriced). The NPC price
     * is intentionally not a fallback: selling to an NPC is not what the
     * displayed "value" means.
     */
    fun price(key: String, mode: PriceMode): Double? {
        val snapshot = prices
        val bazaar = when (mode) {
            PriceMode.INSTA_SELL -> snapshot.bazaarInstaSell
            PriceMode.SELL_OFFER -> snapshot.bazaarSellOffer
        }
        return bazaar[key]?.takeIf { it > 0.0 } ?: snapshot.auctionLbin[key]?.takeIf { it > 0.0 }
    }

    fun hasBazaar(key: String): Boolean {
        val snapshot = prices
        return key in snapshot.bazaarInstaSell || key in snapshot.bazaarSellOffer
    }

    private fun refreshBazaar() {
        try {
            val body = fetch(BAZAAR_URL) ?: return
            val products = JsonParser.parseString(body).asJsonObject.getAsJsonObject("products") ?: return

            val instaSell = HashMap<String, Double>(products.size() * 2)
            val sellOffer = HashMap<String, Double>(products.size() * 2)

            for ((id, element) in products.entrySet()) {
                val product = element.asJsonObject

                product.getAsJsonArray("sell_summary")?.firstOrNull()?.asJsonObject
                    ?.get("pricePerUnit")?.asDouble?.let { instaSell[id] = it }

                product.getAsJsonArray("buy_summary")?.firstOrNull()?.asJsonObject
                    ?.get("pricePerUnit")?.asDouble?.let { sellOffer[id] = it }
            }

            if (instaSell.isEmpty()) return

            prices = prices.copy(bazaarInstaSell = instaSell, bazaarSellOffer = sellOffer, revision = revision + 1)
            lastBazaarUpdateMs = System.currentTimeMillis()
            SkbMod.LOGGER.info("Bazaar prices updated: ${instaSell.size} products")
        } catch (throwable: Throwable) {
            SkbMod.LOGGER.warn("Failed to refresh bazaar prices", throwable)
        }
    }

    private fun refreshAuctions() {
        try {
            val body = fetch(AUCTION_URL) ?: return
            val root = JsonParser.parseString(body)
            if (!root.isJsonObject) return

            val auctionLbin = HashMap<String, Double>(8192)
            for ((id, element) in root.asJsonObject.entrySet()) {
                if (!element.isJsonPrimitive) continue
                val value = element.asDouble
                if (value > 0.0) auctionLbin[id] = value
            }

            if (auctionLbin.isEmpty()) return

            prices = prices.copy(auctionLbin = auctionLbin, revision = revision + 1)
            lastAuctionUpdateMs = System.currentTimeMillis()
            SkbMod.LOGGER.info("Auction prices updated: ${auctionLbin.size} items")
        } catch (throwable: Throwable) {
            SkbMod.LOGGER.warn("Failed to refresh auction prices", throwable)
        }
    }

    private fun fetch(url: String): String? {
        val request = HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(45))
            .header("User-Agent", "skbProfiTracker/${SkbMod.version}")
            .GET()
            .build()

        val response = client.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() != 200) {
            SkbMod.LOGGER.warn("Price request failed: HTTP ${response.statusCode()} for $url")
            return null
        }
        return response.body()
    }
}

enum class PriceMode(val shortName: String) {
    INSTA_SELL("insta"),
    SELL_OFFER("offer");

    fun next(): PriceMode = if (this == INSTA_SELL) SELL_OFFER else INSTA_SELL
}
