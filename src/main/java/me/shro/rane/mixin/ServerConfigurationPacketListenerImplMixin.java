package me.shro.rane.mixin;

import me.shro.rane.core.ResourcePacks;
import net.minecraft.network.protocol.common.ServerboundResourcePackPacket;
import net.minecraft.server.network.ConfigurationTask;
import net.minecraft.server.network.ServerConfigurationPacketListenerImpl;
import net.minecraft.server.network.config.ServerResourcePackConfigurationTask;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Only finish a resource pack task when the response belongs to it: rane's multi-pack task waits
 * for every pack, and stray responses (clients re-report packs on reload) are ignored instead of
 * finishing whatever task happens to be current.
 */
@Mixin(ServerConfigurationPacketListenerImpl.class)
public abstract class ServerConfigurationPacketListenerImplMixin {
    @Shadow private ConfigurationTask currentTask;

    @Shadow protected abstract void finishCurrentTask(ConfigurationTask.Type type);

    @Redirect(method = "handleResourcePackResponse", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/server/network/ServerConfigurationPacketListenerImpl;finishCurrentTask(Lnet/minecraft/server/network/ConfigurationTask$Type;)V"))
    private void rane$finishPackTask(ServerConfigurationPacketListenerImpl self, ConfigurationTask.Type type, ServerboundResourcePackPacket packet) {
        if (currentTask instanceof ResourcePacks.Task task) {
            if (task.finished(packet.id())) finishCurrentTask(ResourcePacks.Task.Companion.getTYPE());
        } else if (currentTask != null && currentTask.type().equals(ServerResourcePackConfigurationTask.TYPE)) {
            finishCurrentTask(type);
        }
    }
}
