package fuzs.fantasticwings.client.handler;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import fuzs.fantasticwings.client.audio.WingsSound;
import fuzs.fantasticwings.client.flight.FlightViewCapability;
import fuzs.fantasticwings.client.flight.NormalFlightTransitions;
import fuzs.fantasticwings.client.flight.NormalFlightBodyMotion;
import fuzs.fantasticwings.client.init.ClientModRegistry;
import fuzs.fantasticwings.flight.FlightCapability;
import fuzs.fantasticwings.init.ModRegistry;
import fuzs.fantasticwings.util.MathHelper;
import fuzs.puzzleslib.api.event.v1.core.EventResult;
import fuzs.puzzleslib.api.event.v1.data.MutableFloat;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.ItemInHandRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;

public class ClientEventHandler {
    // Enhanced Flight 1.0.6, CheatyBlack: adapted VERTICAL_ARMS_FORWARD idle
    // expressions. See THIRD-PARTY-ENHANCED-FLIGHT.md for attribution.
    private static Vec3 visualHoverMotion(Player player, float delta) {
        return ClientModRegistry.FLIGHT_VIEW_CAPABILITY.getIfProvided(player)
                .map(view -> view.getHoverMotion(delta)).orElseGet(player::getDeltaMovement);
    }

    static float enhancedIdleFade(Player player, float delta) {
        Vec3 motion = visualHoverMotion(player, delta);
        float speed = (float) Math.sqrt(motion.x * motion.x + motion.z * motion.z);
        float t = Mth.clamp(1.5F * speed, 0.0F, 1.0F);
        return 1.0F - t * t * (3.0F - 2.0F * t);
    }

    private static float rad(double degrees) { return (float) Math.toRadians(degrees); }

    // Body and root sample the SAME read-only wing signal, in either render
    // order. No independent animation clock and no calls to animator.update().
    private static NormalFlightBodyMotion normalFlightBodyMotion(Player player, float delta, float speed) {
        Vec3 wing = ClientModRegistry.FLIGHT_VIEW_CAPABILITY.getIfProvided(player)
                .map(view -> view.getNormalWingMotion(delta)).orElse(Vec3.ZERO);
        float bank = MathHelper.lerpDegrees(player.yBodyRotO - player.yRotO,
                player.yBodyRot - player.getYRot(), delta);
        float lookPitch = MathHelper.lerpDegrees(player.xRotO, player.getXRot(), delta);
        return NormalFlightBodyMotion.sample(wing, speed, bank, lookPitch);
    }

    private static void applyEnhancedIdle(Player player, PlayerModel<?> model, float time, float weight, float pitch) {
        // Assign absolute targets so shared ModelPart instances do not accumulate rotations.
        float wobble = 0.2F * Mth.sin(time * 0.1F);
        model.body.xRot = Mth.lerp(weight, model.body.xRot, 0.0F);
        model.body.yRot = Mth.lerp(weight, model.body.yRot, 0.0F);
        model.body.zRot = Mth.lerp(weight, model.body.zRot, 0.0F);
        model.head.xRot = Mth.lerp(weight, model.head.xRot, MathHelper.toRadians(pitch) + rad(3.0F));
        model.head.zRot = Mth.lerp(weight, model.head.zRot, 0.0F);
        model.rightLeg.xRot = Mth.lerp(weight, model.rightLeg.xRot, rad(6.0F - 20.0F * wobble));
        model.rightLeg.yRot = Mth.lerp(weight, model.rightLeg.yRot, rad(5.0F + 2.0F * wobble));
        model.rightLeg.zRot = Mth.lerp(weight, model.rightLeg.zRot, rad(2.0F - 2.0F * wobble));
        model.leftLeg.xRot = Mth.lerp(weight, model.leftLeg.xRot, rad(10.0F + 20.0F * wobble));
        model.leftLeg.yRot = Mth.lerp(weight, model.leftLeg.yRot, rad(-8.0F - 4.0F * wobble));
        model.leftLeg.zRot = Mth.lerp(weight, model.leftLeg.zRot, rad(-3.0F + 2.0F * wobble));
        if (!player.isUsingItem() && !player.swinging) {
            model.rightArm.xRot = Mth.lerp(weight, model.rightArm.xRot, rad(2.0F - 0.2F * wobble));
            model.rightArm.yRot = Mth.lerp(weight, model.rightArm.yRot, rad(10.0F));
            model.rightArm.zRot = Mth.lerp(weight, model.rightArm.zRot, rad(11.0F + 7.5F * wobble));
            model.leftArm.xRot = Mth.lerp(weight, model.leftArm.xRot, rad(7.0F + 0.2F * wobble));
            model.leftArm.yRot = Mth.lerp(weight, model.leftArm.yRot, rad(-10.0F));
            model.leftArm.zRot = Mth.lerp(weight, model.leftArm.zRot, rad(-13.0F - 10.5F * wobble));
        }
        model.hat.copyFrom(model.head);
    }


    // Original gentle wing-descent loop. Its restrained, asymmetric limb angles
    // sit between the Falling clip and the authored Landing/Hover poses. Do not
    // move pivots: Java has no waist bone and the model is shared by players.
    private static void applySlowWingDescent(Player player, PlayerModel<?> model, float ticks, float weight, float pitch) {
        float phase = ticks * (float)(2.0D * Math.PI / (3.141593D * 20.0D));
        float sway = Mth.sin(phase);
        float counter = Mth.sin(phase + 0.6F);
        model.body.xRot = Mth.lerp(weight, model.body.xRot, rad(2.0F + 1.2F * sway));
        model.head.xRot = Mth.lerp(weight, model.head.xRot, MathHelper.toRadians(pitch) + rad(2.0F + 0.5F * counter));
        model.rightLeg.xRot = Mth.lerp(weight, model.rightLeg.xRot, rad(3.5F - 2.0F * sway));
        model.rightLeg.yRot = Mth.lerp(weight, model.rightLeg.yRot, rad(5.0F));
        model.rightLeg.zRot = Mth.lerp(weight, model.rightLeg.zRot, rad(2.0F));
        model.leftLeg.xRot = Mth.lerp(weight, model.leftLeg.xRot, rad(8.0F + 2.5F * sway));
        model.leftLeg.yRot = Mth.lerp(weight, model.leftLeg.yRot, rad(-8.0F));
        model.leftLeg.zRot = Mth.lerp(weight, model.leftLeg.zRot, rad(-3.0F));
        if (!player.isUsingItem() && !player.swinging) {
            model.rightArm.xRot = Mth.lerp(weight, model.rightArm.xRot, rad(-2.0F + 2.0F * counter));
            model.rightArm.yRot = Mth.lerp(weight, model.rightArm.yRot, rad(10.0F));
            model.rightArm.zRot = Mth.lerp(weight, model.rightArm.zRot, rad(15.0F + 2.0F * sway));
            model.leftArm.xRot = Mth.lerp(weight, model.leftArm.xRot, rad(4.0F - 2.0F * counter));
            model.leftArm.yRot = Mth.lerp(weight, model.leftArm.yRot, rad(-10.0F));
            model.leftArm.zRot = Mth.lerp(weight, model.leftArm.zRot, rad(-17.0F - 2.0F * sway));
        }
        model.hat.copyFrom(model.head);
    }

    // Original experimental layer on top of the existing Enhanced Flight idle.
    // Signed movement is smoothed once per tick in the view capability.
    private static void applyVerticalIdle(Player player, PlayerModel<?> model, float time, float weight, float vertical) {
        float up = weight * Math.max(vertical, 0.0F);
        float down = weight * Math.max(-vertical, 0.0F);
        float wobble = 0.2F * Mth.sin(time * 0.1F);
        model.body.xRot = Mth.lerp(up, model.body.xRot, rad(-2.0D));
        model.body.xRot = Mth.lerp(down, model.body.xRot, rad(3.0D));
        model.rightLeg.xRot = Mth.lerp(up, model.rightLeg.xRot, rad(14.0F - 20.0F * wobble));
        model.leftLeg.xRot = Mth.lerp(up, model.leftLeg.xRot, rad(18.0F + 20.0F * wobble));
        model.rightLeg.y -= 1.5F * down;
        model.rightLeg.z -= 1.6F * down;
        if (!player.isUsingItem() && !player.swinging) {
            model.rightArm.zRot = Mth.lerp(up, model.rightArm.zRot, rad(14.0F + 7.5F * wobble));
            model.leftArm.zRot = Mth.lerp(up, model.leftArm.zRot, rad(-16.0F - 10.5F * wobble));
        }
    }

    private static float landingTime(Player player, float delta) {
        return ClientModRegistry.FLIGHT_VIEW_CAPABILITY.getIfProvided(player)
                .map(view -> view.getHoverLandingTime(delta)).orElse(-1.0F);
    }

    // Landing lasts 0.75 seconds; the following 0.3 seconds restore visual sneak.
    public static float landingSneakBlend(Player player, float delta) {
        float t = landingTime(player, delta);
        if (t < 0.0F) return ClientModRegistry.FLIGHT_VIEW_CAPABILITY.getIfProvided(player)
                .map(view -> {
                    float landing = view.getNormalLandingSneak(delta);
                    return landing >= 0.0F ? landing : view.getExitGroundSneak(delta);
                }).orElse(-1.0F);
        float f = Mth.clamp((t - 0.75F) / 0.3F, 0.0F, 1.0F);
        return f * f * (3.0F - 2.0F * f);
    }

    private static void applyLandingSneak(Player player, PlayerModel<?> model, float delta) {
        float blend = landingSneakBlend(player, delta);
        if (blend < 0.0F || !player.isCrouching()) return;
        model.body.xRot = Mth.lerp(blend, model.body.xRot, 0.5F);
        model.rightArm.xRot += 0.4F * blend;
        model.leftArm.xRot += 0.4F * blend;
        model.rightLeg.z += 4.0F * blend; model.leftLeg.z += 4.0F * blend;
        model.rightLeg.y += 0.2F * blend; model.leftLeg.y += 0.2F * blend;
        model.head.y += 4.2F * blend;
        model.body.y += 3.2F * blend;
        model.leftArm.y += 3.2F * blend; model.rightArm.y += 3.2F * blend;
        model.hat.copyFrom(model.head);
    }

    private static float takeoffTime(Player player, float delta) {
        return ClientModRegistry.FLIGHT_VIEW_CAPABILITY.getIfProvided(player)
                .map(view -> view.getHoverTakeoffTime(delta)).orElse(-1.0F);
    }

    private static float takeoffHoverBlend(float t) {
        if (t < 0.0F) return 1.0F;
        float x = Mth.clamp((t - 0.65F) / 0.2F, 0.0F, 1.0F);
        return x * x * (3.0F - 2.0F * x);
    }

    // Preserve the authored movement, then blend its endpoint into our Hover pose.
    private static void applyHoverTakeoff(Player player, PlayerModel<?> model, float t) {
        if (t >= 0.0F) WingflightAnimations.apply(player, model, t, false, 1.0F - takeoffHoverBlend(t));
    }

    private static float landingHoverFade(float t) {
        if (t < 0.0F) return 1.0F;
        float x = Mth.clamp(t / 0.1667F, 0.0F, 1.0F);
        return 1.0F - x * x * (3.0F - 2.0F * x);
    }

    public static float normalLandingWeight(Player player, float delta) {
        return ClientModRegistry.FLIGHT_VIEW_CAPABILITY.getIfProvided(player)
                .map(view -> view.getNormalLanding(delta) * view.getFallingLandingBlend(delta)).orElse(0.0F);
    }

    private static boolean suppressNormalFlightForImpact(Player player) {
        return ClientModRegistry.FLIGHT_VIEW_CAPABILITY.getIfProvided(player)
                .map(FlightViewCapability::suppressNormalFlightForImpact).orElse(false);
    }

    // Enhanced Flight 1.0.6 applyLandingBones; named Java model mappings.
    private static void applyNormalImpact(PlayerModel<?> m, float w) {
        if (w <= 0.0F) return;
        m.head.y = Mth.lerp(w, m.head.y, 5.5F); m.head.z = Mth.lerp(w, 0.0F, -7.0F);
        m.head.xRot = Mth.lerp(w, m.head.xRot, rad(55)); m.head.zRot = Mth.lerp(w, m.head.zRot, 0.0F);
        m.body.y = Mth.lerp(w, m.body.y, 5.0F); m.body.z = Mth.lerp(w, 0.0F, -6.0F);
        m.body.xRot = Mth.lerp(w, m.body.xRot, rad(35)); m.body.yRot = Mth.lerp(w, m.body.yRot, rad(10));
        m.body.zRot = Mth.lerp(w, m.body.zRot, 0.0F);
        m.rightArm.x = Mth.lerp(w, m.rightArm.x, -5.0F); m.rightArm.y = Mth.lerp(w, m.rightArm.y, 7.0F); m.rightArm.z = Mth.lerp(w, m.rightArm.z, -3.5F);
        m.rightArm.xRot = Mth.lerp(w, m.rightArm.xRot, rad(75)); m.rightArm.yRot = Mth.lerp(w, m.rightArm.yRot, rad(-15)); m.rightArm.zRot = Mth.lerp(w, m.rightArm.zRot, rad(20));
        m.leftArm.x = Mth.lerp(w, m.leftArm.x, 5.5F); m.leftArm.y = Mth.lerp(w, m.leftArm.y, 7.0F); m.leftArm.z = Mth.lerp(w, m.leftArm.z, -5.0F);
        m.leftArm.xRot = Mth.lerp(w, m.leftArm.xRot, rad(65)); m.leftArm.yRot = Mth.lerp(w, m.leftArm.yRot, rad(15)); m.leftArm.zRot = Mth.lerp(w, m.leftArm.zRot, rad(-20));
        m.rightLeg.x = Mth.lerp(w, -1.9F, -2.0F); m.rightLeg.y = Mth.lerp(w, m.rightLeg.y, 15.0F); m.rightLeg.z = Mth.lerp(w, m.rightLeg.z, -.5F);
        m.rightLeg.xRot = Mth.lerp(w, m.rightLeg.xRot, rad(60)); m.rightLeg.yRot = Mth.lerp(w, m.rightLeg.yRot, rad(15)); m.rightLeg.zRot = Mth.lerp(w, m.rightLeg.zRot, rad(5));
        m.leftLeg.x = Mth.lerp(w, 1.9F, 4.0F); m.leftLeg.y = Mth.lerp(w, m.leftLeg.y, 12.0F); m.leftLeg.z = Mth.lerp(w, m.leftLeg.z, -4.0F);
        m.leftLeg.xRot = Mth.lerp(w, m.leftLeg.xRot, rad(10)); m.leftLeg.yRot = Mth.lerp(w, m.leftLeg.yRot, rad(-10)); m.leftLeg.zRot = Mth.lerp(w, m.leftLeg.zRot, 0.0F);
        m.hat.copyFrom(m.head);
    }

    private static void applyHoverLanding(Player player, PlayerModel<?> model, float t) {
        if (t >= 0.0F) WingflightAnimations.apply(player, model, t, true, 1.0F);
    }

    public static boolean hasNormalTransitionPose(Player player) {
        return ClientModRegistry.FLIGHT_VIEW_CAPABILITY.getIfProvided(player)
                .map(view -> view.getNormalTransitions().ownsModel()).orElse(false);
    }

    // Used by the model mixin to restore pivots before the shared model is reused.
    public static boolean hasWingflightPose(Player player, float delta) {
        return landingTime(player, delta) >= 0.0F || takeoffTime(player, delta) >= 0.0F
                || ClientModRegistry.FLIGHT_VIEW_CAPABILITY.getIfProvided(player)
                .map(view -> view.getFallingPose(delta) > 0.0F || view.hasAirSwitchPose() || view.getNormalTransitions().ownsModel()).orElse(false);
    }

    // Enhanced Flight 1.0.6 VERTICAL_ARMS_FORWARD / hoverPropelled,
    // converted to named mappings. Movement values use displacement per tick.
    private static void applyEnhancedHorizontal(Player player, PlayerModel<?> model, float delta, float weight, float pitch) {
        Vec3 motion = visualHoverMotion(player, delta);
        double dx = motion.x, dz = motion.z;
        double speed = Math.sqrt(dx * dx + dz * dz);
        double scale = speed > 1.0D ? 1.0D / speed : 1.0D;
        double yaw = Math.toRadians(Mth.rotLerp(Mth.clamp(delta, 0.0F, 1.0F), player.yBodyRotO, player.yBodyRot));
        double side = (dx * Math.cos(yaw) + dz * Math.sin(yaw)) * scale;
        double forward = (dz * Math.cos(yaw) - dx * Math.sin(yaw)) * scale;
        double sn = side * Math.min(2.0D * speed, 1.0D);
        double fn = forward * Math.min(2.0D * speed, 1.0D);
        double adz = Math.abs(fn);
        model.body.xRot = Mth.lerp(weight, model.body.xRot, 0.0F);
        model.body.yRot = Mth.lerp(weight, model.body.yRot, 0.0F);
        model.body.zRot = Mth.lerp(weight, model.body.zRot, 0.0F);
        model.head.xRot = Mth.lerp(weight, model.head.xRot, MathHelper.toRadians(pitch) - rad(30.0D * Mth.clamp(fn, -2.0D, 2.0D)));
        model.head.zRot = Mth.lerp(weight, model.head.zRot, 0.0F);
        if (!player.isUsingItem() && !player.swinging) {
            model.rightArm.xRot = Mth.lerp(weight, model.rightArm.xRot, (float) (0.1D * fn + 0.1D * sn));
            model.rightArm.yRot = Mth.lerp(weight, model.rightArm.yRot, 0.0F);
            model.rightArm.zRot = Mth.lerp(weight, model.rightArm.zRot, (float) (0.1D * adz + 0.2D * sn + rad(3.0D)));
            model.leftArm.xRot = Mth.lerp(weight, model.leftArm.xRot, (float) (0.1D * fn - 0.1D * sn));
            model.leftArm.yRot = Mth.lerp(weight, model.leftArm.yRot, 0.0F);
            model.leftArm.zRot = Mth.lerp(weight, model.leftArm.zRot, (float) (-0.1D * adz + 0.2D * sn - rad(3.0D)));
        }
        model.rightLeg.xRot = Mth.lerp(weight, model.rightLeg.xRot, (float) (0.3D * fn));
        model.rightLeg.yRot = Mth.lerp(weight, model.rightLeg.yRot, 0.0F);
        model.rightLeg.zRot = Mth.lerp(weight, model.rightLeg.zRot, (float) (0.05D * adz + 0.4D * sn));
        model.leftLeg.xRot = Mth.lerp(weight, model.leftLeg.xRot, (float) (0.2D * fn));
        model.leftLeg.yRot = Mth.lerp(weight, model.leftLeg.yRot, 0.0F);
        model.leftLeg.zRot = Mth.lerp(weight, model.leftLeg.zRot, (float) (-0.05D * adz + 0.4D * sn));
    }

    public static void onAnimatePlayerModel(Player player, PlayerModel<?> model, float ticksExisted, float pitch) {
        FallingAnimation.beginModelFrame(model);
        FlightViewCapability airView = ClientModRegistry.FLIGHT_VIEW_CAPABILITY.getIfProvided(player).orElse(null);
        NormalFlightTransitions normalTransition = airView == null ? null : airView.getNormalTransitions();
        if (normalTransition != null) {
            normalTransition.prepare(ticksExisted);
            normalTransition.bootstrapModel(model);
        }
        if (airView != null) airView.beginAirSwitch(ticksExisted);
        boolean singleAirSwitch = airView != null && airView.usesSingleAirSwitch();
        FlightCapability flightCapability = ModRegistry.FLIGHT_CAPABILITY.get(player);
        float delta = ticksExisted - player.tickCount;
        float normalImpact = normalLandingWeight(player, delta);
        float slowDescent = ClientModRegistry.FLIGHT_VIEW_CAPABILITY.getIfProvided(player)
                .map(view -> view.getSlowDescentPose(delta)).orElse(0.0F);
        // A switch destination is a full mode. Its sole crossfade is applied
        // after the pose is constructed; never pass through vanilla/3-degree arms.
        if (singleAirSwitch) slowDescent = flightCapability.isHovering() ? 0.0F : 1.0F;
        if (normalTransition != null) slowDescent = normalTransition.slowAmount(slowDescent);
        if (slowDescent > 0.0F) applySlowWingDescent(player, model, ticksExisted, slowDescent, pitch);
        float flyingAmount = suppressNormalFlightForImpact(player) ? 0.0F : flightCapability.getFlyingAmount(delta);
        if (normalTransition != null && normalTransition.normalAmount() >= 0.0F)
            flyingAmount = normalTransition.normalAmount();
        if (flyingAmount > 0.0F) {
            // Enhanced Flight 1.0.6 HORIZONTAL_ARMS_FORWARD boost, adapted
            // to Fantastic Wings' existing flight transition and motion samples.
            Vec3 motion = visualHoverMotion(player, delta);
            Vec3 look = player.getViewVector(delta);
            double speed = Math.sqrt(motion.x * motion.x + motion.z * motion.z);
            double lookLength = Math.sqrt(look.x * look.x + look.z * look.z);
            double angle = speed > 0.0001D && lookLength > 0.0001D
                    ? Math.acos(Mth.clamp((motion.x * look.x + motion.z * look.z) / (speed * lookLength), -1.0D, 1.0D))
                    : 0.0D;
            double direction = Math.signum(motion.x * look.z - motion.z * look.x);
            float turn = (float) (Math.min(speed, 1.0D) * direction * angle / 2.5D);
            NormalFlightBodyMotion bodyMotion = normalFlightBodyMotion(player, delta, (float) speed);
            model.head.xRot = Mth.lerp(flyingAmount, model.head.xRot, rad(bodyMotion.headPitchDegrees));
            if (!player.isUsingItem() && !player.swinging) {
                model.rightArm.xRot = Mth.lerp(flyingAmount, model.rightArm.xRot, rad(-165.0F + bodyMotion.armPitchDegrees) - 0.5F * turn);
                model.rightArm.yRot = Mth.lerp(flyingAmount, model.rightArm.yRot, rad(15.0D));
                model.rightArm.zRot = Mth.lerp(flyingAmount, model.rightArm.zRot, rad(2.0F + bodyMotion.armRollDegrees) - 0.25F * turn);
                model.leftArm.xRot = Mth.lerp(flyingAmount, model.leftArm.xRot, rad(-165.0F + bodyMotion.armPitchDegrees) + 0.5F * turn);
                model.leftArm.yRot = Mth.lerp(flyingAmount, model.leftArm.yRot, -rad(15.0D));
                model.leftArm.zRot = Mth.lerp(flyingAmount, model.leftArm.zRot, -rad(2.0F + bodyMotion.armRollDegrees) - 0.25F * turn);
            }
            model.rightLeg.xRot = Mth.lerp(flyingAmount, model.rightLeg.xRot, rad(bodyMotion.rightLegPitchDegrees(turn)));
            model.rightLeg.yRot = Mth.lerp(flyingAmount, model.rightLeg.yRot, rad(1.0D));
            model.rightLeg.zRot = Mth.lerp(flyingAmount, model.rightLeg.zRot, rad(bodyMotion.legSpreadDegrees));
            model.leftLeg.xRot = Mth.lerp(flyingAmount, model.leftLeg.xRot, rad(bodyMotion.leftLegPitchDegrees(turn)));
            model.leftLeg.yRot = Mth.lerp(flyingAmount, model.leftLeg.yRot, -rad(1.0D));
            model.leftLeg.zRot = Mth.lerp(flyingAmount, model.leftLeg.zRot, -rad(bodyMotion.legSpreadDegrees));
            model.hat.copyFrom(model.head);
        }
        float hover = flightCapability.getHoverBlend(delta) * landingHoverFade(landingTime(player, delta))
                * takeoffHoverBlend(takeoffTime(player, delta));
        if (singleAirSwitch) hover = flightCapability.isHovering() ? 1.0F : 0.0F;
        if (normalTransition != null && normalTransition.hoverAmount() >= 0.0F)
            hover = normalTransition.hoverAmount();
        float sprintPose = ClientModRegistry.FLIGHT_VIEW_CAPABILITY.getIfProvided(player)
                .map(view -> view.getHoverSprint(delta)).orElse(0.0F);
        if (hover > 0.001F) {
            // Start with the approved non-sprint pose and its idle transition.
            applyEnhancedHorizontal(player, model, delta, hover, pitch);
            float referenceIdle = hover * enhancedIdleFade(player, delta) * (1.0F - sprintPose);
            float idleTime = ClientModRegistry.FLIGHT_VIEW_CAPABILITY.getIfProvided(player)
                    .map(view -> view.getHoverIdleTime(ticksExisted)).orElse(ticksExisted);
            applyEnhancedIdle(player, model, idleTime, referenceIdle, pitch);
            Vec3 idleMotion = visualHoverMotion(player, delta);
            float horizontalSpeed = (float) Math.sqrt(idleMotion.x * idleMotion.x + idleMotion.z * idleMotion.z);
            float stationary = 1.0F - Mth.clamp((horizontalSpeed - 0.04F) / 0.11F, 0.0F, 1.0F);
            float verticalPose = ClientModRegistry.FLIGHT_VIEW_CAPABILITY.getIfProvided(player)
                    .map(view -> view.getHoverVerticalPose(delta)).orElse(0.0F);
            applyVerticalIdle(player, model, idleTime, referenceIdle * stationary, verticalPose);


            // Experimental fixed midpoint: the reference mod fades out of boost
            // after overheating and does not define a separate fixed midpoint.
            float sprint = hover * sprintPose;
            model.head.xRot = Mth.lerp(sprint, model.head.xRot, MathHelper.toRadians(pitch) - rad(25.0D));
            if (!player.isUsingItem() && !player.swinging) {
                // Experimental half-open flight pose for the 55-degree torso:
                // less reach than Superman, some shoulder clearance, and a
                // very small asymmetrical sway. No change to model geometry.
                float sway = Mth.sin(ticksExisted * 0.10F);
                model.rightArm.xRot = Mth.lerp(sprint, model.rightArm.xRot, rad(-52.0D + 2.0D * sway));
                model.rightArm.yRot = Mth.lerp(sprint, model.rightArm.yRot, rad(7.0D));
                model.rightArm.zRot = Mth.lerp(sprint, model.rightArm.zRot, rad(20.0D + sway));
                model.leftArm.xRot = Mth.lerp(sprint, model.leftArm.xRot, rad(-47.0D - 2.0D * sway));
                model.leftArm.yRot = Mth.lerp(sprint, model.leftArm.yRot, rad(-7.0D));
                model.leftArm.zRot = Mth.lerp(sprint, model.leftArm.zRot, rad(-23.0D + sway));
            }
            model.rightLeg.xRot = Mth.lerp(sprint, model.rightLeg.xRot, rad(15.0D));
            model.rightLeg.yRot = Mth.lerp(sprint, model.rightLeg.yRot, rad(2.0D));
            model.rightLeg.zRot = Mth.lerp(sprint, model.rightLeg.zRot, rad(3.0D));
            model.leftLeg.xRot = Mth.lerp(sprint, model.leftLeg.xRot, rad(15.0D));
            model.leftLeg.yRot = Mth.lerp(sprint, model.leftLeg.yRot, rad(-2.0D));
            model.leftLeg.zRot = Mth.lerp(sprint, model.leftLeg.zRot, rad(-3.0D));
            model.hat.copyFrom(model.head);
        }
        float fallingPose = ClientModRegistry.FLIGHT_VIEW_CAPABILITY.getIfProvided(player)
                .map(view -> view.getFallingPose(delta)).orElse(0.0F);
        if (normalTransition != null) fallingPose = normalTransition.fallingAmount(fallingPose);
        if (fallingPose > 0.0F) FallingAnimation.apply(player, model, ticksExisted / 20.0F, fallingPose);
        applyNormalImpact(model, normalImpact);
        float fallingImpact = ClientModRegistry.FLIGHT_VIEW_CAPABILITY.getIfProvided(player)
                .map(view -> view.getFallingImpact(delta)).orElse(0.0F) * normalImpact;
        if (fallingImpact > 0.0F) {
            // Extra brief compression/recoil, over the existing superhero pose.
            model.body.xRot += rad(8.0F * fallingImpact);
            model.head.xRot += rad(5.0F * fallingImpact);
            model.rightLeg.xRot += rad(8.0F * fallingImpact);
            model.rightArm.xRot += rad(8.0F * fallingImpact);
            model.leftArm.xRot += rad(5.0F * fallingImpact);
            model.hat.copyFrom(model.head);
        }
        if (normalTransition == null || !normalTransition.suppressClips()) {
            applyHoverTakeoff(player, model, takeoffTime(player, delta));
            applyHoverLanding(player, model, landingTime(player, delta));
            applyLandingSneak(player, model, delta);
        }
        if (normalTransition != null && normalTransition.ownsModel()) {
            PoseStack targetRoot = new PoseStack();
            applyFlightRoot(player, targetRoot, delta, normalTransition);
            normalTransition.setRootTarget(targetRoot.last().pose());
            normalTransition.applyModel(model, ticksExisted);
        }
        ClientModRegistry.FLIGHT_VIEW_CAPABILITY.getIfProvided(player)
                .ifPresent(view -> {
                    if (normalTransition != null && normalTransition.ownsModel()) view.captureNormalAirPose(model, ticksExisted);
                    else view.blendAirSwitch(model, ticksExisted);
                });
        if (normalTransition != null) normalTransition.recordModel(model, ticksExisted);
        // setupAnim may already have copied outer layers; mirror the final pose.
        model.hat.copyFrom(model.head); model.jacket.copyFrom(model.body);
        model.rightSleeve.copyFrom(model.rightArm); model.leftSleeve.copyFrom(model.leftArm);
        model.rightPants.copyFrom(model.rightLeg); model.leftPants.copyFrom(model.leftLeg);
        FallingAnimation.finishModelFrame(model);
    }

    public static void onApplyRotations(Player player, PoseStack poseStack, float delta) {
        FlightViewCapability view = ClientModRegistry.FLIGHT_VIEW_CAPABILITY.getIfProvided(player).orElse(null);
        if (view == null) { applyFlightRoot(player, poseStack, delta, null); return; }
        float now = player.tickCount + delta;
        NormalFlightTransitions transition = view.getNormalTransitions();
        transition.prepare(now);
        view.beginAirSwitch(now);
        PoseStack target = new PoseStack();
        applyFlightRoot(player, target, delta, transition);
        transition.setRootTarget(target.last().pose());
        if (transition.ownsRoot()) transition.applyRoot(poseStack, now);
        else {
            // Execute the original operations on the real stack, preserving normals
            // and the accepted root behavior outside a Normal Flight handoff.
            applyFlightRoot(player, poseStack, delta, transition);
            transition.recordLegacyRoot(target.last().pose(), now);
        }
    }

    private static void applyFlightRoot(Player player, PoseStack poseStack, float delta, NormalFlightTransitions transition) {
        FlightCapability flightCapability = ModRegistry.FLIGHT_CAPABILITY.get(player);
        float takeoff = takeoffTime(player, delta);
        float landing = landingTime(player, delta);
        // The outgoing Takeoff lift and incoming Hover bob must use the SAME
        // interpolated movement mask. Otherwise Takeoff carries an unmasked
        // visual lift into a motion-suppressed Hover, which can withdraw height
        // during the crossfade while world-space ascent is braking as well.
        float verticalPose = ClientModRegistry.FLIGHT_VIEW_CAPABILITY.getIfProvided(player)
                .map(view -> view.getHoverVerticalPose(delta)).orElse(0.0F);
        float bobReturn = 1.0F - Mth.clamp(Math.abs(verticalPose), 0.0F, 1.0F);
        if (transition == null || !transition.suppressClips()) {
            // Contact may render before tick clears Takeoff: retain that old
            // contact frame rather than changing the protected Landing root.
            if (takeoff >= 0.0F) WingflightAnimations.applyTakeoffRoot(poseStack, takeoff,
                    1.0F - takeoffHoverBlend(takeoff), landing < 0.0F ? bobReturn : 1.0F);
            if (landing >= 0.0F) WingflightAnimations.applyRoot(poseStack, landing, true, 1.0F);
        }
        float sneakBlend = landingSneakBlend(player, delta);
        if ((transition == null || !transition.suppressClips()) && sneakBlend >= 0.0F && player.isCrouching()) {
            // Cancel vanilla's -0.125 render offset, then restore it smoothly.
            poseStack.translate(0.0D, 0.125D * (1.0F - sneakBlend), 0.0D);
        }
        float normalImpact = normalLandingWeight(player, delta);
        float fallingPose = ClientModRegistry.FLIGHT_VIEW_CAPABILITY.getIfProvided(player)
                .map(view -> view.getFallingPose(delta)).orElse(0.0F);
        if (transition != null) fallingPose = transition.fallingAmount(fallingPose);
        if (fallingPose > 0.0F) FallingAnimation.applyRoot(poseStack, (player.tickCount + delta) / 20.0F, fallingPose);
        float fallingImpact = ClientModRegistry.FLIGHT_VIEW_CAPABILITY.getIfProvided(player)
                .map(view -> view.getFallingImpact(delta)).orElse(0.0F) * normalImpact;
        if (fallingImpact > 0.0F) {
            poseStack.translate(0.0D, -0.08D * fallingImpact, 0.0D);
            poseStack.scale(1.0F + .025F * fallingImpact, 1.0F - .06F * fallingImpact, 1.0F + .025F * fallingImpact);
        }
        if (normalImpact > 0.0F) {
            poseStack.translate(0.0D, -0.35D * normalImpact, 0.25D * normalImpact);
            poseStack.mulPose(Axis.XP.rotationDegrees(20.0F * normalImpact));
        }
        float amt = suppressNormalFlightForImpact(player) ? 0.0F : flightCapability.getFlyingAmount(delta);
        if (transition != null && transition.normalAmount() >= 0.0F) amt = transition.normalAmount();
        if (amt > 0.0F) {
            // Original Fantastic Wings body transform; Enhanced Flight limb pose remains.
            float roll = MathHelper.lerpDegrees(player.yBodyRotO - player.yRotO,
                    player.yBodyRot - player.getYRot(), delta);
            Vec3 motion = visualHoverMotion(player, delta);
            float speed = (float) Math.sqrt(motion.x * motion.x + motion.z * motion.z);
            NormalFlightBodyMotion bodyMotion = normalFlightBodyMotion(player, delta, speed);
            // Render-only whole-rig pitch keeps hips and wing anchors attached.
            // The existing coordinator captures this live target on every handoff.
            float pitch = -MathHelper.lerpDegrees(player.xRotO, player.getXRot(), delta) - 90.0F
                    + bodyMotion.pitchDegrees;
            poseStack.mulPose(Axis.ZP.rotationDegrees(MathHelper.lerpDegrees(0.0F, roll, amt)));
            poseStack.mulPose(Axis.XP.rotationDegrees(MathHelper.lerpDegrees(0.0F, pitch, amt)));
            poseStack.translate(0.0D, -1.2D * MathHelper.easeInOut(amt), 0.0D);
        }
        float hover = flightCapability.getHoverBlend(delta) * landingHoverFade(landingTime(player, delta))
                * takeoffHoverBlend(takeoffTime(player, delta));
        if (transition != null && transition.rootHoverAmount() >= 0.0F) hover = transition.rootHoverAmount();
        if (hover > 0.001F) {
            // PoseStack's model axes point in the opposite direction to the
            // projected movement axes. Reverse both rotations, not the inputs.
            float sprintPose = ClientModRegistry.FLIGHT_VIEW_CAPABILITY.getIfProvided(player)
                    .map(view -> view.getHoverSprint(delta)).orElse(0.0F);
            Vec3 motion = visualHoverMotion(player, delta);
            double dx = motion.x, dz = motion.z;
            double yaw = Math.toRadians(Mth.rotLerp(Mth.clamp(delta, 0.0F, 1.0F), player.yBodyRotO, player.yBodyRot));
            double forward = dz * Math.cos(yaw) - dx * Math.sin(yaw);
            double side = dx * Math.cos(yaw) + dz * Math.sin(yaw);
            double speed = Math.sqrt(dx * dx + dz * dz);
            double lean = 30.0D * Math.min(1.5D * speed, 1.0D);
            // Keep Hover Sprint's body pitch fixed regardless of camera pitch;
            // the head still follows the player's look direction independently.
            float pitch = Mth.lerp(sprintPose, (float) (-lean * Mth.clamp(forward, -2.0D, 2.0D)), -55.0F);
            // Same interpolated visual weight used by the wings; only the
            // rendered root is straightened, never player velocity or camera.
            float wingBrake = ClientModRegistry.FLIGHT_VIEW_CAPABILITY.getIfProvided(player)
                    .map(view -> view.getHoverBrakeVisual(delta)).orElse(0.0F);
            pitch *= 1.0F - wingBrake;
            // Match Normal Flight's visual body bank on camera turns. This
            // rotates the rendered model only; it does not change velocity.
            float turnBank = MathHelper.lerpDegrees(player.yBodyRotO - player.yRotO,
                    player.yBodyRot - player.getYRot(), delta);
            float roll = Mth.lerp(sprintPose, (float) (lean * Mth.clamp(side, -2.0D, 2.0D)), turnBank);
            // Ground takeoff has its own clip. A separate hover-boolean pulse
            // here snapped off on an airborne toggle, so do not add one.
            double age = player.tickCount + delta;
            float ageTicks = (float) age;
            double idleAge = ClientModRegistry.FLIGHT_VIEW_CAPABILITY.getIfProvided(player)
                    .map(view -> view.getHoverIdleTime(ageTicks)).orElse(ageTicks);
            // Cosmetic only: the player model bobs, without changing world position.
            float referenceIdle = enhancedIdleFade(player, delta) * (1.0F - sprintPose);
            double previousBob = Math.sin(age * 0.105D) * 0.060D + Math.sin(age * 0.071D + 1.2D) * 0.020D;
            // Adapted from Enhanced Flight's VERTICAL_ARMS_FORWARD renderer.
            float referenceWobble = 0.2F * Mth.sin((float) idleAge * 0.1F);
            double bob = previousBob * sprintPose * 0.5D
                    + 0.15D * Math.sin(idleAge * 0.1D) * referenceIdle;
            // Like the horizontal idle crossfade, use the same interpolated
            // movement weight as the limb pose. Their exit is synchronized.
            // bobReturn was sampled once above for BOTH sides of ground entry.
            // Ordinary Hover keeps its original bob and movement suppression.
            poseStack.translate(0.0D, hover * bob * bobReturn, 0.0D);
            float idlePitch = (-3.5F + 5.0F * referenceWobble) * referenceIdle * (1.0F - wingBrake);
            poseStack.mulPose(Axis.ZP.rotationDegrees(hover * roll));
            poseStack.mulPose(Axis.XP.rotationDegrees(hover * (pitch + idlePitch)));
            // Move the pivot visually, not the player's actual position or collision box.
            poseStack.translate(0.0D, -0.85D * hover * sprintPose * (1.0F - wingBrake), 0.0D);
        }
    }

    public static void onComputeCameraAngles(GameRenderer renderer, Camera camera, float partialTick, MutableFloat pitch, MutableFloat yaw, MutableFloat roll) {
        ModRegistry.FLIGHT_CAPABILITY.getIfProvided(camera.getEntity()).ifPresent(flightViewCapability -> {
            float flyingAmount = flightViewCapability.getFlyingAmount(partialTick);
            float hoverSprint = 0.0F;
            if (camera.getEntity() instanceof Player player) {
                hoverSprint = flightViewCapability.getHoverBlend(partialTick)
                        * ClientModRegistry.FLIGHT_VIEW_CAPABILITY.getIfProvided(player)
                        .map(view -> view.getHoverSprint(partialTick)).orElse(0.0F);
            }
            float cameraFlightBlend = Math.max(flyingAmount, hoverSprint);
            if (cameraFlightBlend > 0.0F && camera.getEntity() instanceof LivingEntity cameraEntity) {
                float newRoll = MathHelper.lerpDegrees(cameraEntity.yBodyRotO - cameraEntity.yRotO,
                        cameraEntity.yBodyRot - cameraEntity.getYRot(),
                        partialTick);
                roll.accept(MathHelper.lerpDegrees(0.0F, -newRoll * 0.25F, cameraFlightBlend));
            }
        });
    }

    public static EventResult onEntityLoad(Entity entity, ClientLevel level) {
        if (entity instanceof LocalPlayer localPlayer) {
            FlightCapability flightCapability = ModRegistry.FLIGHT_CAPABILITY.get(localPlayer);
            Minecraft.getInstance().getSoundManager().play(new WingsSound(localPlayer, flightCapability));
        }

        return EventResult.PASS;
    }

    public static void onEndPlayerTick(Player player) {
        ClientModRegistry.FLIGHT_VIEW_CAPABILITY.getIfProvided(player).ifPresent(FlightViewCapability::tick);

    }

    public static EventResult onRenderHand(Player player, InteractionHand interactionHand, ItemStack itemStack, PoseStack poseStack, MultiBufferSource multiBufferSource, int lightCoords, float partialTick, float interpolatedPitch, float swingProgress, float equipProgress) {
        ItemInHandRenderer itemInHandRenderer = Minecraft.getInstance().gameRenderer.itemInHandRenderer;
        if (interactionHand == InteractionHand.OFF_HAND && itemStack.isEmpty() && !player.isScoping()
                && !player.isInvisible()) {
            FlightCapability flight = ModRegistry.FLIGHT_CAPABILITY.get(player);
            // Keep the original normal-flight offhand behavior. In hover, show
            // the free offhand only when doing so cannot obscure a held item.
            float hoverHandVisibility = ClientModRegistry.FLIGHT_VIEW_CAPABILITY.getIfProvided(player)
                    .map(view -> view.getHoverHandVisibility(partialTick))
                    .orElse(Mth.clamp(flight.getHoverBlend(partialTick), 0.0F, 1.0F));
            boolean hoverFreeHand = hoverHandVisibility > 0.001F
                    && itemInHandRenderer.mainHandItem.isEmpty() && !player.isUsingItem()
                    && swingProgress <= 0.01F && !player.swinging;
            if (!itemInHandRenderer.mainHandItem.is(Items.FILLED_MAP) && (flight.isFlying() || hoverFreeHand)) {
                poseStack.pushPose();
                if (!flight.isFlying()) {
                    // Move the extra Hover hand below the view; its mesh isn't alpha-faded.
                    poseStack.translate(0.0D, -1.5D * (1.0F - hoverHandVisibility), 0.0D);
                }
                FirstPersonFlightAnimation.apply(player, interactionHand, itemStack, poseStack, partialTick, swingProgress);
                itemInHandRenderer.renderPlayerArm(poseStack,
                        multiBufferSource,
                        lightCoords,
                        equipProgress,
                        swingProgress,
                        player.getMainArm().getOpposite());
                poseStack.popPose();
                return EventResult.INTERRUPT;
            }
        }
        return EventResult.PASS;
    }

    public static void onTurn(Entity entity, float deltaYaw) {
        if (entity instanceof Player player) {
            FlightCapability flight = ModRegistry.FLIGHT_CAPABILITY.get(player);
            boolean hoverSprint = flight.isHovering() && ClientModRegistry.FLIGHT_VIEW_CAPABILITY
                    .getIfProvided(player).map(view -> view.getHoverSprint(1.0F) > 0.01F).orElse(false);
            if (!flight.isFlying() && !hoverSprint) return;
            float theta = Mth.wrapDegrees(player.getYRot() - player.yBodyRot);
            if (theta < -50.0F || theta > 50.0F) {
                player.yBodyRot += deltaYaw;
                player.yBodyRotO += deltaYaw;
            }
        }
    }
}
