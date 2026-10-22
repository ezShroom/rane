package me.shro.rane.core

import net.minecraft.server.level.ServerPlayer
import net.minecraft.util.Prediction
import net.minecraft.world.item.ItemStack

/** Puts the stack in the player's inventory, dropping whatever doesn't fit at their feet. */
fun giveItem(player: ServerPlayer, stack: ItemStack) {
    player.inventory.placeItemBackInInventory(stack, Prediction.SERVER_ONLY)
}

fun ServerPlayer.swingHand(hand: net.minecraft.world.InteractionHand) {
    swing(hand, net.minecraft.world.item.component.SwingAnimation.DEFAULT, true)
}

/** A sound only this player hears. */
fun net.minecraft.world.entity.player.Player.notifySound(sound: net.minecraft.sounds.SoundEvent, volume: Float = 1f, pitch: Float = 1f) {
    val sp = this as? ServerPlayer ?: return
    sp.connection.send(net.minecraft.network.protocol.game.ClientboundSoundPacket(
        net.minecraft.core.Holder.direct(sound), net.minecraft.sounds.SoundSource.MASTER, x, y, z, volume, pitch, random.nextLong()))
}

fun net.minecraft.world.entity.player.Player.notifySound(sound: net.minecraft.core.Holder<net.minecraft.sounds.SoundEvent>, volume: Float = 1f, pitch: Float = 1f) =
    notifySound(sound.value(), volume, pitch)

val net.minecraft.world.item.Item.displayName: String get() = net.minecraft.world.item.ItemStack(this).hoverName.string
