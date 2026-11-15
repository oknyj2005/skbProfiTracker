package com.skbprofitracker.utils

import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component

fun Minecraft.getScreenCompat(): Screen? {
    return gui.screen()
}

fun Minecraft.setScreenCompat(screen: Screen?) {
    if (screen == null) gui.setScreen(null) else setScreenAndShow(screen)
}

fun Minecraft.addClientChatMessageCompat(message: Component) {
    gui.hud.getChat().addClientSystemMessage(message)
}

fun Minecraft.showTitleCompat(title: Component, subtitle: Component, fadeIn: Int, stay: Int, fadeOut: Int) {
    gui.hud.apply {
        setTimes(fadeIn, stay, fadeOut)
        setTitle(title)
        setSubtitle(subtitle)
    }
}

fun openUriCompat(url: String) {
    net.minecraft.util.Util.getPlatform().openUri(url)
}

fun openPathCompat(path: java.nio.file.Path) {
    net.minecraft.util.Util.getPlatform().openUri(path.toUri().toString())
}
