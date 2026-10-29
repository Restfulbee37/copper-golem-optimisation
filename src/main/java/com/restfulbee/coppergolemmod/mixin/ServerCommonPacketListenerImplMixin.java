package com.restfulbee.coppergolemmod.mixin;

import com.restfulbee.coppergolemmod.ConfigScreen;
import net.minecraft.network.protocol.common.ServerboundCustomClickActionPacket;
import net.minecraft.server.network.ServerCommonPacketListenerImpl;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Receives Save from the settings screen. Vanilla passes custom click actions on without saying which player sent
 * them, so this picks up the mod's own action here, where the player is known. It runs after vanilla has moved the
 * packet onto the server thread.
 */
@Mixin(ServerCommonPacketListenerImpl.class)
public abstract class ServerCommonPacketListenerImplMixin {
    @Inject(
        method = "handleCustomClickAction",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/server/MinecraftServer;handleCustomClickAction(Lnet/minecraft/resources/Identifier;Ljava/util/Optional;)V"),
        cancellable = true
    )
    private void copperGolemMod$saveSettings(ServerboundCustomClickActionPacket packet, CallbackInfo ci) {
        if (packet.id().equals(ConfigScreen.SAVE_ACTION) && (Object) this instanceof ServerGamePacketListenerImpl game) {
            ConfigScreen.save(game.player.createCommandSourceStack(), packet.payload());
            ci.cancel();
        }
    }
}
