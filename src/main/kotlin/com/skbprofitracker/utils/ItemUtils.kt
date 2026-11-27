package com.skbprofitracker.utils

import com.skbprofitracker.utils.ChatUtils.getFormattedString
import com.skbprofitracker.utils.ChatUtils.getUnformattedString
import com.skbprofitracker.utils.ChatUtils.removeFormatting
import com.skbprofitracker.utils.enums.ColorCodes.GRAY
import com.mojang.serialization.JsonOps
import net.minecraft.core.component.DataComponents
import net.minecraft.world.item.component.CustomData
import net.minecraft.world.item.ItemStack
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.NbtOps
import net.minecraft.nbt.NumericTag
import net.minecraft.nbt.StringTag
import net.minecraft.world.item.FishingRodItem

object ItemUtils {
    private val npcAmountSuffix = Regex(".+ §8x\\d+$")
    private val gemstoneNameGlyph = Regex("[^a-zA-Z0-9 ]")

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
    fun getCustomDataId(customData: CustomData): String? =
        readCustomDataValue(customData.copyTag(), "id")

    /**
     * Returns the petInfo JSON string for a pet stack.
     */
    fun getCustomDataPetInfo(customData: CustomData): String? {
        val tag = customData.copyTag()
        if (readCustomDataValue(tag, "id") != "PET") return null
        return readCustomDataValue(tag, "petInfo")
    }

    private fun readCustomDataValue(tag: CompoundTag, key: String): String? = when (val value = tag.get(key)) {
        is StringTag -> value.value()
        is NumericTag -> runCatching {
            NbtOps.INSTANCE.convertTo(JsonOps.INSTANCE, value).asString
        }.getOrNull()
        else -> null
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
        if (npcAmountSuffix.matches(cleanedItemName)) {
            cleanedItemName = cleanedItemName.substringBeforeLast(' ')
        }

        cleanedItemName = cleanedItemName.removeFormatting()

        // ☘ Rough Jade Gemstone -> Rough Jade Gemstone
        if (cleanedItemName.endsWith(" Gemstone")) {
            cleanedItemName = cleanedItemName.replace(gemstoneNameGlyph, "").trim()
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
    private val PET_ID = Regex("([A-Z0-9_]+);([0-8])(?:\\+(100|200))?")
   
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

    /**
     * Base pets need a catalog name to distinguish their keys from items such as runes.
     * A max-level key can supply its own pet name when the catalog has no entry for it.
     */
    fun getLeveledPetDisplayNameByPetId(itemId: String, petName: String?): String? {
        val (baseId, rarity, maxLevel) = PET_ID.matchEntire(itemId)?.destructured ?: return null
        if (petName == null && maxLevel.isEmpty()) return null

        val name = petName ?: PET_ID_TO_NAME[baseId]
            ?: CommonUtils.fromUppercaseToCapitalizedFirstLetters(baseId, "_")
        val level = maxLevel.ifEmpty { "1" }
        val color = CommonUtils.getRarityColorCode(rarity.toInt())
        return "${GRAY}[Lvl $level] $color$name"
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
    fun getRarityPrefix(stack: ItemStack, formattedName: String? = null): String? {
        if (stack.isEmpty) return null

        val lore = stack.get(DataComponents.LORE)?.lines().orEmpty()
        for (index in lore.indices.reversed()) {
            val line = lore[index].getFormattedString()
            if (RARITY_LINE.matches(line.removeFormatting().trim().uppercase())) {
                leadingCodes(line)?.let { return it }
                break
            }
        }

        // Attribute shards do not always repeat the rarity in the lore; their hover name is
        // already coloured by rarity though ("§6Paragon Shard").
        return leadingCodes(formattedName ?: stack.hoverName.getFormattedString())?.takeIf { prefix ->
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
