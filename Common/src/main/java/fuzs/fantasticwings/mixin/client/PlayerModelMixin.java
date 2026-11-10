package fuzs.fantasticwings.mixin.client;

import fuzs.fantasticwings.client.handler.ClientEventHandler;
import fuzs.fantasticwings.client.handler.FirstPersonRenderGuard;
import fuzs.fantasticwings.client.init.ClientModRegistry;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(PlayerModel.class)
abstract class PlayerModelMixin<T extends LivingEntity> extends HumanoidModel<T> {

    @Unique private boolean fantasticwings$resetImpactPivots;
    @Unique private boolean fantasticwings$resetWingflightPivots;
    @Unique private boolean fantasticwings$resetNormalTransitionRoll;
    @Unique private float fantasticwings$lastNormalHeadRoll, fantasticwings$lastNormalBodyRoll;

    @Unique private static void fantasticwings$resetPivot(ModelPart part) {
        part.setPos(part.getInitialPose().x, part.getInitialPose().y, part.getInitialPose().z);
    }

    public PlayerModelMixin(ModelPart root) {
        super(root);
    }

    // Prevent vanilla from mixing its crouch pose into the landing pose. The
    // renderer sets crouching afresh for each player; do not affect first person.
    @Inject(method = "setupAnim", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/model/HumanoidModel;setupAnim(Lnet/minecraft/world/entity/LivingEntity;FFFFF)V", shift = At.Shift.BEFORE))
    private void fantasticwings$landingBeforeVanilla(T entity, float limbSwing, float limbSwingAmount, float ageInTicks, float netHeadYaw, float headPitch, CallbackInfo callback) {
        if (this.fantasticwings$resetNormalTransitionRoll) {
            // Vanilla setupAnim does not reliably reset these two Z rotations.
            // Remove only our last frame's value; respect a newer external value.
            if (Float.compare(this.head.zRot, this.fantasticwings$lastNormalHeadRoll) == 0)
                this.head.zRot = this.head.getInitialPose().zRot;
            if (Float.compare(this.body.zRot, this.fantasticwings$lastNormalBodyRoll) == 0)
                this.body.zRot = this.body.getInitialPose().zRot;
            this.fantasticwings$resetNormalTransitionRoll = false;
        }
        if (this.fantasticwings$resetWingflightPivots) {
            fantasticwings$resetPivot(this.head); fantasticwings$resetPivot(this.body);
            fantasticwings$resetPivot(this.leftArm); fantasticwings$resetPivot(this.rightArm);
            fantasticwings$resetPivot(this.leftLeg); fantasticwings$resetPivot(this.rightLeg);
            this.fantasticwings$resetWingflightPivots = false;
        }
        if (this.fantasticwings$resetImpactPivots) {
            // Vanilla does not reset all pivots; the model is shared by players.
            this.head.z = 0.0F; this.body.z = 0.0F;
            this.rightLeg.x = -1.9F; this.leftLeg.x = 1.9F;
            this.fantasticwings$resetImpactPivots = false;
        }
        if (!FirstPersonRenderGuard.active() && (LivingEntity) entity instanceof Player player
                && (ClientEventHandler.landingSneakBlend(player, ageInTicks - player.tickCount) >= 0.0F
                || ClientModRegistry.FLIGHT_VIEW_CAPABILITY.getIfProvided(player)
                        .map(view -> view.suppressAirExitSneak()).orElse(false))) {
            this.crouching = false;
        }
    }

    @Inject(method = "setupAnim", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/model/HumanoidModel;setupAnim(Lnet/minecraft/world/entity/LivingEntity;FFFFF)V", shift = At.Shift.AFTER))
    public void setupAnim(T entity, float limbSwing, float limbSwingAmount, float ageInTicks, float netHeadYaw, float headPitch, CallbackInfo callback) {
        if (!FirstPersonRenderGuard.active() && (LivingEntity) entity instanceof Player player) {
            ClientEventHandler.onAnimatePlayerModel(player, PlayerModel.class.cast(this), ageInTicks, headPitch);
            this.fantasticwings$resetNormalTransitionRoll = ClientEventHandler.hasNormalTransitionPose(player);
            if (this.fantasticwings$resetNormalTransitionRoll) {
                this.fantasticwings$lastNormalHeadRoll = this.head.zRot;
                this.fantasticwings$lastNormalBodyRoll = this.body.zRot;
            }
            this.fantasticwings$resetImpactPivots = ClientEventHandler.normalLandingWeight(player, ageInTicks - player.tickCount) > 0.0F;
            this.fantasticwings$resetWingflightPivots = ClientEventHandler.hasWingflightPose(player, ageInTicks - player.tickCount);
        }
    }
}
