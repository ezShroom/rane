package me.shro.rane.core

import net.minecraft.network.chat.Component
import net.minecraft.network.chat.MutableComponent
import net.minecraft.network.chat.Style
import net.minecraft.server.level.ServerPlayer

/** Text helpers. Strings may use `&` or `§` legacy formatting codes. */
fun txt(s: String): MutableComponent = Component.literal(s.replace('&', '§'))

/** Like [txt], but without the italic style Minecraft applies to item names and lore. */
fun plain(s: String): MutableComponent = txt(s).withStyle(Style.EMPTY.withItalic(false))

fun ServerPlayer.msg(s: String) = sendSystemMessage(txt(s))

fun ServerPlayer.actionBar(s: String) = sendOverlayMessage(txt(s))

/** House style for module messages. */
object Fmt {
    const val OK = "§a"
    const val ERR = "§c"
    const val INFO = "§7"
    const val HL = "§b"
    const val NAME = "§e"
}
