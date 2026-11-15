package com.skbprofitracker.events

import com.skbprofitracker.events.models.AfterMouseClickEvent
import com.skbprofitracker.events.models.ActionBarCancellableEvent
import com.skbprofitracker.events.models.ActionBarEvent
import com.skbprofitracker.events.models.ChatCancellableEvent
import com.skbprofitracker.events.models.ChatEvent
import com.skbprofitracker.events.models.ClientTickEvent
import com.skbprofitracker.events.models.GameClosedEvent
import com.skbprofitracker.events.models.GameStartedEvent
import com.skbprofitracker.events.models.GuiClosedEvent
import com.skbprofitracker.events.models.ArmorStandDespawnedEvent
import com.skbprofitracker.events.models.ItemEntityLoadedEvent
import com.skbprofitracker.events.models.OwnFishingHookDespawnedEvent
import com.skbprofitracker.events.models.WorldChangedEvent
import com.skbprofitracker.events.models.ItemTooltipRenderedEvent
import com.skbprofitracker.events.models.ScreenBeforeInitEvent
import com.skbprofitracker.utils.ChatUtils.getFormattedString
import com.skbprofitracker.utils.ChatUtils.getUnformattedString
import com.skbprofitracker.utils.InputUtils
import kotlin.reflect.KClass
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.client.gui.screens.ChatScreen
import net.minecraft.client.gui.screens.inventory.InventoryScreen
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientEntityEvents
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLevelEvents as ClientWorldEvents
import net.fabricmc.fabric.api.client.item.v1.ItemTooltipCallback
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents
import net.minecraft.world.entity.item.ItemEntity
import net.minecraft.world.entity.decoration.ArmorStand
import net.minecraft.world.entity.projectile.FishingHook

object EventBus {
    private val subscribers = mutableMapOf<KClass<*>, MutableList<(Any) -> Unit>>()

    fun publish(event: Any) {
        subscribers[event::class]?.forEach { it -> it(event) }
    }

    fun <T : Any> subscribe(eventType: KClass<T>, callback: (T) -> Unit) {
        val callbacks = subscribers.getOrPut(eventType) { mutableListOf() }
        callbacks.add(callback as (Any) -> Unit)
    }

    fun init() {
        ClientReceiveMessageEvents.ALLOW_GAME.register { message, isOverlay ->
            if (isOverlay) {
                val formatted = message.getFormattedString()
                val unformatted = message.getUnformattedString()
                publish(ActionBarEvent(message, formatted, unformatted))

                val cancellableEvent = ActionBarCancellableEvent(message, formatted, unformatted, false)
                publish(cancellableEvent)
                !cancellableEvent.isCancelled
            } else {
                val formatted = message.getFormattedString()
                val unformatted = message.getUnformattedString()
                publish(ChatEvent(message, formatted, unformatted))

                val cancellableEvent = ChatCancellableEvent(message, formatted, unformatted, false)
                publish(cancellableEvent)
                !cancellableEvent.isCancelled
            }
        }

        ItemTooltipCallback.EVENT.register { stack, _, _, lines ->
            publish(ItemTooltipRenderedEvent(stack, lines))
        }

        ScreenEvents.BEFORE_INIT.register { _, screen, _, _ ->
            publish(ScreenBeforeInitEvent(screen))
        }

        ScreenEvents.AFTER_INIT.register { _, screen, _, _ ->
            ScreenEvents.remove(screen).register {
                val guiName = when (screen) {
                    is ChatScreen -> "Chat"
                    is InventoryScreen -> "Inventory"
                    is AbstractContainerScreen<*> -> screen.title.getUnformattedString()
                    else -> screen.javaClass.getSimpleName()
                }
                publish(GuiClosedEvent(guiName))
            }

            ScreenMouseEvents.afterMouseClick(screen).register { scr, click, consumed ->
               publish(AfterMouseClickEvent(scr, click.x(), click.y(), click.buttonInfo().button, InputUtils.hasControlDown(click)))
               consumed
            }
        }

        ClientTickEvents.END_CLIENT_TICK.register { client ->
            publish(ClientTickEvent(client))
        }

        ClientLifecycleEvents.CLIENT_STOPPING.register {
            publish(GameClosedEvent())
        }

        ClientLifecycleEvents.CLIENT_STARTED.register { _ ->
            publish(GameStartedEvent())
        }

        ClientWorldEvents.AFTER_CLIENT_LEVEL_CHANGE.register { mc, world ->
            publish(WorldChangedEvent(mc, world))
        }

        ClientEntityEvents.ENTITY_LOAD.register { entity, _ ->
            when (entity) {
                is ItemEntity -> publish(ItemEntityLoadedEvent(entity))
                else -> { }
            }
        }

        ClientEntityEvents.ENTITY_UNLOAD.register { entity, _ ->
            when (entity) {
                is ArmorStand -> publish(ArmorStandDespawnedEvent(entity))
                is FishingHook -> {
                    val player = Minecraft.getInstance().player
                    if (player != null && entity.playerOwner == player) {
                        publish(OwnFishingHookDespawnedEvent())
                    }
                }
                else -> { }
            }
        }
    }
}
