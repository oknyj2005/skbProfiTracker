package com.skbprofitracker.price

import com.google.gson.JsonParser
import com.skbprofitracker.SkbMod
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.client.Minecraft
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

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

    /** product id -> bazaar price per unit when selling to buy orders (insta-sell). */
    @Volatile
    private var bazaarInstaSell: Map<String, Double> = emptyMap()

    /** product id -> bazaar price per unit when buying (sell offer / lowest ask). */
    @Volatile
    private var bazaarSellOffer: Map<String, Double> = emptyMap()

    /** NEU internal id -> lowest bin. */
    @Volatile
    private var auctionLbin: Map<String, Double> = emptyMap()

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
        when (mode) {
            PriceMode.INSTA_SELL -> bazaarInstaSell[key]?.takeIf { it > 0.0 }?.let { return it }
            PriceMode.SELL_OFFER -> bazaarSellOffer[key]?.takeIf { it > 0.0 }?.let { return it }
        }
        return auctionLbin[key]?.takeIf { it > 0.0 }
    }

    fun hasBazaar(key: String): Boolean =
        bazaarInstaSell.containsKey(key) || bazaarSellOffer.containsKey(key)

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

            bazaarInstaSell = instaSell
            bazaarSellOffer = sellOffer
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

            val prices = HashMap<String, Double>(8192)
            for ((id, element) in root.asJsonObject.entrySet()) {
                if (!element.isJsonPrimitive) continue
                val value = element.asDouble
                if (value > 0.0) prices[id] = value
            }

            if (prices.isEmpty()) return

            auctionLbin = prices
            lastAuctionUpdateMs = System.currentTimeMillis()
            SkbMod.LOGGER.info("Auction prices updated: ${prices.size} items")
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

enum class PriceMode { INSTA_SELL, SELL_OFFER }
