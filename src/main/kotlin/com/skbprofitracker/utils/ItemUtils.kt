package com.skbprofitracker.utils

import com.skbprofitracker.utils.ChatUtils.getFormattedString
import com.skbprofitracker.utils.ChatUtils.getUnformattedString
import com.skbprofitracker.utils.ChatUtils.removeFormatting
import com.skbprofitracker.utils.enums.ColorCodes.GRAY
import com.google.gson.JsonObject
import com.mojang.serialization.JsonOps
import net.minecraft.core.component.DataComponents
import net.minecraft.world.item.component.CustomData
import net.minecraft.world.item.ItemStack
import net.minecraft.nbt.NbtOps
import net.minecraft.world.item.FishingRodItem

object ItemUtils {

    /*
     * Gets the unformatted lore lines from the item stack.
     * Null lines are returned as empty strings.
     * @param stack The item stack to get the lore lines from.
     * @returns {List<String>} The lore lines without formatting.
     */
    fun getUnformattedLoreLines(stack: ItemStack): List<String> {
        if (stack.isEmpty) return emptyList()
        val loreLines = stack.get(DataComponents.LORE)?.lines()?.map { it?.getUnformattedString() ?: "" } ?: emptyList()
        return loreLines
    }

    /*
     * Gets the formatted lore lines from the item stack.
     * Null lines are returned as empty strings.
     * @param stack The item stack to get the lore lines from.
     * @returns {List<String>} The lore lines with formatting.
     */
    fun getFormattedLoreLines(stack: ItemStack): List<String> {
        if (stack.isEmpty) return emptyList()
        val loreLines = stack.get(DataComponents.LORE)?.lines()?.map { it?.getFormattedString() ?: "" } ?: emptyList()
        return loreLines
    }

    /**
     * Returns the item's custom data component (CUSTOM_DATA), or null if absent.
     */
    fun getCustomData(stack: ItemStack): CustomData? {
        if (stack.isEmpty) return null
        return stack.get(DataComponents.CUSTOM_DATA)
    }

    /**
     * Returns the "id" field from custom data (e.g. "PET" for pets).
     */
    fun getCustomDataId(customData: CustomData): String? {
        val obj = customDataToJsonObject(customData) ?: return null
        return obj.get("id")?.takeIf { it.isJsonPrimitive }?.asString
    }

    /**
     * Returns the "petInfo" JSON string from custom data. Caller should parse it for further processing.
     */
    fun getCustomDataPetInfo(customData: CustomData): String? {
        val obj = customDataToJsonObject(customData) ?: return null
        return obj.get("petInfo")?.takeIf { it.isJsonPrimitive }?.asString
    }

    /*
     * Converts a custom data component to a JSON object.
     * @param customData The custom data component to convert.
     * @returns {JsonObject} The JSON object, or null if the conversion fails.
     */
    fun customDataToJsonObject(customData: CustomData): JsonObject? {
        val nbt = customData.copyTag()
        if (nbt.isEmpty) return null
        val jsonEl = try {
            NbtOps.INSTANCE.convertTo(JsonOps.INSTANCE, nbt)
        } catch (_: Exception) {
            return null
        }
        return jsonEl?.takeIf { it.isJsonObject }?.asJsonObject
    }

    /*
     * Gets the clean item name without formatting. It also removes the amount suffix from NPC menu if present (e.g. §9Fish Affinity Talisman §8x1 -> Fish Affinity Talisman).
     * @param itemName The formatted item name to clean.
     * @returns {String} The clean item name.
     */
    fun getCleanItemName(itemName: String): String {
        if (itemName.isBlank()) return ""
        var cleanedItemName = itemName

        // Booster cookie menu or NPCs append the amount to the item name - e.g. §9Fish Affinity Talisman §8x1
        if (Regex(".+ §8x\\d+$").matches(cleanedItemName)) {
            cleanedItemName = cleanedItemName.split(" ").dropLast(1).joinToString(" ")
        }

        cleanedItemName = cleanedItemName.removeFormatting()

        // ☘ Rough Jade Gemstone -> Rough Jade Gemstone
        if (cleanedItemName.endsWith(" Gemstone")) {
            cleanedItemName = cleanedItemName.replace(Regex("[^a-zA-Z0-9 ]"), "").trim()
        }

        return cleanedItemName
    }

    /*
     * Checks if the item is a Dirt Rod.
     * @param item The item to check.
     * @returns {Boolean} True if the item is a Dirt Rod, false otherwise.
     */
    fun isDirtRod(item: ItemStack?): Boolean {
        if (item == null || item.isEmpty) return false
        return item.hoverName.getUnformattedString().contains("Dirt Rod")
    }

    /*
     * Checks if the item is a Skyblock fishing rod.
     * @param item The item stack to check.
     * @returns {Boolean} True if the item is a fishing rod, false otherwise.
     */
    fun isFishingRod(itemStack: ItemStack?): Boolean {
        if (itemStack == null || itemStack.isEmpty) return false        
        if (itemStack.item == null || itemStack.item !is FishingRodItem) return false

        val name = itemStack.hoverName.getUnformattedString()
        if (name.contains("Carnival Rod")) return false

        val loreLines = getUnformattedLoreLines(itemStack)
        if (loreLines.any { it.contains("This rod is broken")}) return false

        return loreLines.any { it.contains("FISHING ROD", ignoreCase = true) || it.contains("FISHING WEAPON", ignoreCase = true) }
    }

    private val PET_NAME_TO_ID = mapOf(
        "T-Rex" to "TYRANNOSAURUS",
    )

    private val PET_ID_TO_NAME = mapOf(
        "TYRANNOSAURUS" to "T-Rex",
    )
   
    /*
     * Converts a pet display name (e.g. T-Rex, Flying Fish) to the base ID without level information (TYRANNOSAURUS, FLYING_FISH).
     */
    fun getPetBaseIdByDisplayName(petDisplayName: String): String {
        val name = petDisplayName.removeFormatting().trim()
        return PET_NAME_TO_ID[name]
            ?: name.replace("-", " ").split(Regex("\\s+")).joinToString("_").uppercase()
    }

    /*
     * Gets the item ID for a level 1 pet, e.g. FLYING_FISH;4
     * @param petDisplayName The display name of the pet with formatting.
     * @returns {String} The item ID for the level 1 pet, e.g. FLYING_FISH;4
     */
    fun getLevel1PetId(petDisplayName: String): String {
        val rarityCode = CommonUtils.getRarityNumericCode(petDisplayName.substring(0, 2))
        val basePetId = getPetBaseIdByDisplayName(petDisplayName)
        val itemIdLevel1 = "${basePetId};${rarityCode}"
        return itemIdLevel1
    }

    /*
     * Gets the item ID for a maxed (leveled up to 100 or 200) pet, e.g. FLYING_FISH;4+100
     * @param petDisplayName The display name of the pet with formatting.
     * @param level The max level the pet reached.
     * @returns {String} The item ID for the maxed pet, e.g. FLYING_FISH;4+100
     */
    fun getMaxedPetId(petDisplayName: String, level: Int): String {
        val level1PetId = getLevel1PetId(petDisplayName)
        val maxLevelPetId = "${level1PetId}+${level}"
        return maxLevelPetId
    }

    /*
     * Checks if the item ID is a maxed pet ID.
     * @param itemId The item ID to check, e.g. FLYING_FISH;4+100
     * @returns {Boolean} True if the item is a maxed pet, false otherwise.
     */
    fun isMaxedPet(itemId: String): Boolean =
        itemId.endsWith("+100") || itemId.endsWith("+200")

    /*
     * Gets the pet name by pet ID.
     * @param itemId The item ID to get the name for.
     * @returns {String} The unformatted name for the pet, e.g. Flying Fish
     */
    fun getPetNameByPetId(itemId: String): String {
        if (!isMaxedPet(itemId)) return ""
        val basePetId = itemId.split(";")[0]
        return PET_ID_TO_NAME[basePetId]
            ?: CommonUtils.fromUppercaseToCapitalizedFirstLetters(basePetId, "_")
    }

    /*
     * Gets the item display name by pet ID.
     * @param itemId The item ID to get the display name for.
     * @returns {String} The item display name for the pet with formatting and level, e.g. "[Lvl 100] Flying Fish"
     */
    fun getLeveledPetDisplayNameByPetId(itemId: String): String {
        if (!isMaxedPet(itemId)) return ""
        val level = itemId.split("+")[1]
        val rarityNumericCode = itemId.split(";")[1].substringBefore("+").toInt()
        val rarityCode = CommonUtils.getRarityColorCode(rarityNumericCode)
        val petName = getPetNameByPetId(itemId)
        return "${GRAY}[Lvl ${level}] ${rarityCode}${petName}"
    }

    /*
     * Gets the item display name by pet ID.
     * @param itemId The item ID to get the display name for.
     * @param petName The item name to get the display name for, e.g. "Flying Fish"
     * @returns {String} The item display name for the pet with formatting and level, e.g. "[Lvl 100] Flying Fish"
     */
    fun getLeveledPetDisplayNameByPetIdAndName(itemId: String, petName: String): String {
        if (!isMaxedPet(itemId)) return ""
        val level = itemId.split("+")[1]
        val rarityNumericCode = itemId.split(";")[1].substringBefore("+").toInt()
        val rarityCode = CommonUtils.getRarityColorCode(rarityNumericCode)
        return "${GRAY}[Lvl ${level}] ${rarityCode}${petName}"
    }

    /*
     * Gets the name of the enchanted book from the stack.
     * @param stack The item stack to get the name from.
     * @returns {String} The name of the enchanted book, or null if the stack is not an enchanted book.
     */
    fun getEnchantedBookName(stack: ItemStack): String? {
        val lore = getUnformattedLoreLines(stack)
        val filteredLore = lore.filter { it.isNotBlank() && !it.contains("Combinable in Anvil") }
        val bookName = filteredLore.firstOrNull()?.trim() ?: return null
        if (bookName.isEmpty()) return null
        return bookName
    }

    private val RARITY_LINE = Regex(
        "^(VERY SPECIAL|UNOBTAINABLE|COMMON|UNCOMMON|RARE|EPIC|LEGENDARY|MYTHIC|DIVINE|SPECIAL)" +
            "(\\s+[A-Z][A-Z ]*)?$"
    )

    /*
     * Gets the colour prefix of the item's rarity line (e.g. "§9§l" for RARE).
     *
     * Feesh gets its colours from a hand written table; we cannot, so the colour is read
     * from the hover lore once and remembered per price key.
     *
     * The rarity line is usually the last lore line, but attribute shards end it with
     * "LEGENDARY SHARD" rather than a bare "LEGENDARY", hence the trailing word group.
     *
     * @param stack The item stack to read the rarity line from.
     * @returns {String?} The formatting codes of the rarity line, or null when there is none.
     */
    fun getRarityPrefix(stack: ItemStack): String? {
        if (stack.isEmpty) return null

        val rarityLine = getFormattedLoreLines(stack).lastOrNull { line ->
            RARITY_LINE.matches(line.replace(Regex("§."), "").trim().uppercase())
        }

        if (rarityLine != null) {
            leadingCodes(rarityLine)?.let { return it }
        }

        // Attribute shards do not always repeat the rarity in the lore; their hover name is
        // already coloured by rarity though ("§6Paragon Shard").
        return leadingCodes(stack.hoverName.getFormattedString())?.takeIf { prefix ->
            prefix.any { it in "0123456789abcde" } && prefix.none { it in "f78" }
        }
    }

    /** Every `§x` pair at the start of [text], e.g. "§9§l". */
    private fun leadingCodes(text: String): String? {
        val prefix = StringBuilder()
        var i = 0
        while (i + 1 < text.length && text[i] == '§') {
            prefix.append(text, i, 2)
            i += 2
        }
        return prefix.toString().ifEmpty { null }
    }
}
