package fuzs.fantasticwings.mixin.client;

import com.mojang.blaze3d.vertex.PoseStack;
import fuzs.fantasticwings.client.handler.ClientEventHandler;
import fuzs.fantasticwings.client.handler.FirstPersonRenderGuard;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.player.PlayerRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(PlayerRenderer.class)
abstract class PlayerRendererMixin extends LivingEntityRenderer<AbstractClientPlayer, PlayerModel<AbstractClientPlayer>> {

    public PlayerRendererMixin(EntityRendererProvider.Context context, PlayerModel<AbstractClientPlayer> model, float shadowRadius) {
        super(context, model, shadowRadius);
    }

    // The same PlayerModel is used by the first-person arm renderer. Prevent
    // third-person setupAnim from receiving its special zero-tick arm pose.
    @Inject(method = "renderHand", at = @At("HEAD"))
    private void fantasticwings$beginFirstPerson(PoseStack poseStack, MultiBufferSource buffer, int light, AbstractClientPlayer player, ModelPart arm, ModelPart sleeve, CallbackInfo ci) {
        FirstPersonRenderGuard.enter();
    }

    @Inject(method = "renderHand", at = @At("RETURN"))
    private void fantasticwings$endFirstPerson(PoseStack poseStack, MultiBufferSource buffer, int light, AbstractClientPlayer player, ModelPart arm, ModelPart sleeve, CallbackInfo ci) {
        FirstPersonRenderGuard.exit();
    }

    @Inject(method = "setupRotations", at = @At("TAIL"))
    protected void setupRotations(AbstractClientPlayer entityLiving, PoseStack poseStack, float ageInTicks, float rotationYaw, float partialTicks, CallbackInfo callback) {
        ClientEventHandler.onApplyRotations(entityLiving, poseStack, partialTicks);
    }
}
