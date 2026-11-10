package fuzs.fantasticwings.mixin.client;

import com.mojang.blaze3d.vertex.PoseStack;
import fuzs.fantasticwings.client.handler.FirstPersonFlightAnimation;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.ItemInHandRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ItemInHandRenderer.class)
abstract class ItemInHandRendererMixin {
    @Inject(method = "renderArmWithItem", at = @At("HEAD"))
    private void fantasticwings$beginArm(AbstractClientPlayer player, float partialTick, float pitch,
            InteractionHand hand, float swingProgress, ItemStack stack, float equipProgress,
            PoseStack pose, MultiBufferSource buffer, int light, CallbackInfo ci) {
        pose.pushPose();
        FirstPersonFlightAnimation.apply(player, hand, stack, pose, partialTick, swingProgress);
    }

    @Inject(method = "renderArmWithItem", at = @At("RETURN"))
    private void fantasticwings$endArm(AbstractClientPlayer player, float partialTick, float pitch,
            InteractionHand hand, float swingProgress, ItemStack stack, float equipProgress,
            PoseStack pose, MultiBufferSource buffer, int light, CallbackInfo ci) {
        pose.popPose();
    }
}
