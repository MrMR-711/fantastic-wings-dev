package fuzs.fantasticwings.client.flight;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import fuzs.fantasticwings.client.animator.Animator;
import fuzs.fantasticwings.client.flight.apparatus.WingForm;
import fuzs.fantasticwings.client.init.ClientModRegistry;
import fuzs.fantasticwings.client.animator.state.State;
import fuzs.fantasticwings.client.animator.state.StateIdle;
import fuzs.fantasticwings.client.animator.state.StateLand;
import fuzs.fantasticwings.client.animator.state.StateFall;
import fuzs.fantasticwings.flight.FlightCapability;
import fuzs.fantasticwings.init.ModRegistry;
import fuzs.puzzleslib.api.capability.v3.data.CapabilityComponent;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.util.Mth;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

import java.util.function.Consumer;

public final class FlightViewCapability extends CapabilityComponent<AbstractClientPlayer> {
    private WingState animator = PresentWingState.VOID;
    // Visual state only; never affects the server or hover velocity.
    private float hoverSprint, previousHoverSprint;
    private float hoverVerticalPose, previousHoverVerticalPose;
    // Phase offset keeps authored Takeoff's endpoint aligned with Hover idle.
    private float hoverIdlePhaseOffset;
    // First-person offhand visibility is independent of the shared hover pose.
    private float hoverHandVisibility, previousHoverHandVisibility;
    private boolean previousFirstPersonFlying;
    // Client-only landing clock, in ticks; -1 means inactive.
    private float hoverLandingTime = -1.0F, previousHoverLandingTime = -1.0F;
    private boolean hoverLandingWasAirborne;
    private int hoverRecentlyAirborne;
    // Recent actual wing descent animation, not merely equipped wings.
    private int wingDescentRecentlyAirborne;
    private int fallingWingRecentlyAirborne;
    private boolean fallingImpactLanding;
    private float fallingImpact, previousFallingImpact;
    // Client-only falling loop and four-tick crossfade into the impact pose.
    private float fallingPose, previousFallingPose;
    // Cosmetic slow wing descent; kept as the base while airborne Hover blends in.
    private float slowDescentPose, previousSlowDescentPose;
    private float fallingLandingBlend, previousFallingLandingBlend;
    private float normalLanding, previousNormalLanding;
    private int normalLandingRecovery, previousNormalLandingRecovery;
    // Latched after impact until the old Normal Flight blend has fully ended.
    private boolean suppressNormalFlightAfterImpact;
    private boolean normalWasAirborne;
    // Ground-initiated Hover takeoff only, client-side animation clock.
    private float hoverTakeoffTime = -1.0F, previousHoverTakeoffTime = -1.0F;
    private boolean previousHoverActive, takeoffLeftGround;
    // Per-player presentation only: retain neutral sneak during an airborne
    // flight exit and bridge to normal ground crouching if no landing clip fires.
    private boolean airborneFlightSneakGuard;
    private int exitSneakRecovery, previousExitSneakRecovery;
    // Client-only per-tick samples, interpolated during rendering.
    private Vec3 hoverMotion = Vec3.ZERO, previousHoverMotion = Vec3.ZERO;
    private final NormalFlightTransitions normalTransitions = new NormalFlightTransitions(this);

    // Delegated straight to the per-player wing animator; no second clock,
    // smoothing state, tick mutation or change to the wing animation itself.
    public Vec3 getNormalWingMotion(float partialTick) {
        return this.animator.getNormalFlightMotion(Mth.clamp(partialTick, 0.0F, 1.0F));
    }

    public NormalFlightTransitions getNormalTransitions() { return this.normalTransitions; }

    // Package-local access for the visual coordinator; getHolder() is protected.
    AbstractClientPlayer getRenderPlayer() { return this.getHolder(); }

    public void captureNormalAirPose(PlayerModel<?> model, float animationTicks) {
        // The new Normal join already supplied the ONLY temporal composition.
        // Record its final displayed result for a later Slow/Hover handoff;
        // never fade that result again through an independently started snapshot.
        this.switchAirPose = null;
        this.singleAirSwitch = false;
        this.blendAirSwitch(model, animationTicks);
    }

    // Root-only handoff after a Normal -> Hover join; the accepted model path
    // continues using its original physical Hover signal and captured pose.
    public float getAirSwitchProgress(float animationTicks) { return this.singleAirProgress(animationTicks); }


    public float getHoverIdleTime(float animationTicks) {
        return animationTicks + this.hoverIdlePhaseOffset;
    }

    public float getHoverHandVisibility(float partialTick) {
        float value = Mth.lerp(Mth.clamp(partialTick, 0.0F, 1.0F),
                this.previousHoverHandVisibility, this.hoverHandVisibility);
        return value * value * (3.0F - 2.0F * value);
    }

    public Vec3 getHoverMotion(float partialTick) {
        return this.previousHoverMotion.lerp(this.hoverMotion, Mth.clamp(partialTick, 0.0F, 1.0F));
    }

    private boolean pendingFallingImpact() {
        AbstractClientPlayer player = this.getHolder();
        FlightCapability flight = this.getFlight();
        return player.onGround() && this.hoverLandingWasAirborne && this.fallingWingRecentlyAirborne > 0
                && !flight.isFlying() && !flight.isHovering() && !flight.getWings().isEmpty()
                && flight.canUseWings()
                && !player.isFallFlying() && !player.isInWaterOrBubble() && !player.isInLava() && !player.isPassenger();
    }

    // Snapshots belong to the player, never the shared PlayerModel.
    // Slow <-> Hover has one temporal weight: the existing interpolated Hover
    // amount. Its destination is a FULL mode pose, not another crossfade.
    private float[][] lastAirPose, switchAirPose;
    private boolean lastRenderedHover, hasRenderedAirPose;
    private boolean lastAirWasSlow, lastAirWasPureHover;
    private boolean singleAirSwitch, airFrameValid;
    private float lastAirHoverBlend, switchFromHoverBlend;
    private float airSwitchStart = -1.0F, lastAirPoseTime;
    private static final float AIR_SWITCH_TICKS = 10.0F;

    private static ModelPart[] airParts(PlayerModel<?> model) {
        return new ModelPart[] {model.body, model.head, model.rightArm, model.leftArm, model.rightLeg, model.leftLeg};
    }

    private static float[][] captureAirPose(ModelPart[] parts) {
        float[][] pose = new float[parts.length][6];
        for (int i = 0; i < parts.length; i++) {
            ModelPart p = parts[i];
            pose[i] = new float[] {p.x, p.y, p.z, p.xRot, p.yRot, p.zRot};
        }
        return pose;
    }

    private static float blendAngle(float from, float to, float weight) {
        double difference = to - from;
        return from + weight * (float)Math.atan2(Math.sin(difference), Math.cos(difference));
    }

    public boolean hasAirSwitchPose() { return this.switchAirPose != null; }

    // Called BEFORE the handler constructs a pose, so it knows whether to
    // evaluate a full Hover/Slow destination or the original legacy layers.
    public void beginAirSwitch(float animationTicks) {
        AbstractClientPlayer player = this.getHolder();
        float delta = Mth.clamp(animationTicks - player.tickCount, 0.0F, 1.0F);
        boolean hovering = this.getFlight().isHovering();
        this.airFrameValid = !player.onGround() && !this.getFlight().isFlying()
                && !this.getFlight().getWings().isEmpty() && this.getFlight().canUseWings()
                && !player.isFallFlying() && !player.isInWaterOrBubble() && !player.isInLava() && !player.isPassenger();
        if (!this.airFrameValid) {
            this.switchAirPose = null;
            this.singleAirSwitch = false;
            this.hasRenderedAirPose = false;
            this.lastAirWasSlow = this.lastAirWasPureHover = false;
            return;
        }
        boolean slowOrHover = this.getFlight().getWings().flightApparatus().isUsableForSlowlyDescending(player)
                && (hovering || this.getFlight().canSlowlyDescend());
        boolean cleanPair = this.getHoverTakeoffTime(delta) < 0.0F && this.getHoverLandingTime(delta) < 0.0F
                && this.getFallingPose(delta) <= 0.001F && this.getNormalLanding(delta) <= 0.001F
                && this.normalTransitions.legacyFlyingAmount(delta) <= 0.001F;
        boolean recent = animationTicks >= this.lastAirPoseTime && animationTicks - this.lastAirPoseTime < 3.0F;
        if (this.hasRenderedAirPose && !recent) {
            // Off-screen samples or a reset animation clock are not a valid source.
            this.switchAirPose = null;
            this.singleAirSwitch = false;
            this.hasRenderedAirPose = false;
            this.lastAirWasSlow = this.lastAirWasPureHover = false;
        }
        if (this.hasRenderedAirPose && recent && hovering != this.lastRenderedHover && slowOrHover) {
            boolean pair = cleanPair && (this.singleAirSwitch
                    || (hovering && this.lastAirWasSlow)
                    || (!hovering && this.lastAirWasPureHover));
            this.switchAirPose = this.lastAirPose;
            this.airSwitchStart = animationTicks;
            this.switchFromHoverBlend = this.lastAirHoverBlend;
            this.singleAirSwitch = pair;
        }
        if (!hovering && !this.getFlight().canSlowlyDescend()) {
            // Shift -> Falling remains the old path, not a slow-descent pose.
            this.switchAirPose = null;
            this.singleAirSwitch = false;
        }
        if (this.singleAirSwitch && !cleanPair) {
            // Landing, Takeoff, Normal Flight and impact retain their own paths.
            this.switchAirPose = null;
            this.singleAirSwitch = false;
        }
    }

    public boolean usesSingleAirSwitch() {
        return this.airFrameValid && this.singleAirSwitch && this.switchAirPose != null;
    }

    private float singleAirProgress(float animationTicks) {
        float delta = Mth.clamp(animationTicks - this.getHolder().tickCount, 0.0F, 1.0F);
        float hover = Mth.clamp(this.getFlight().getHoverBlend(delta), 0.0F, 1.0F);
        boolean toHover = this.getFlight().isHovering();
        float remaining = toHover ? 1.0F - this.switchFromHoverBlend : this.switchFromHoverBlend;
        if (remaining <= 0.000001F) return 1.0F;
        float distance = toHover ? hover - this.switchFromHoverBlend : this.switchFromHoverBlend - hover;
        // This is a coordinate change of the SAME Hover amount, not a second
        // timer/smoothstep. Reversal starts at the last visible pose and amount.
        return Mth.clamp(distance / remaining, 0.0F, 1.0F);
    }

    public void blendAirSwitch(PlayerModel<?> model, float animationTicks) {
        if (!this.airFrameValid) return;
        AbstractClientPlayer player = this.getHolder();
        float delta = Mth.clamp(animationTicks - player.tickCount, 0.0F, 1.0F);
        boolean hovering = this.getFlight().isHovering();
        ModelPart[] parts = airParts(model);
        if (this.switchAirPose != null) {
            float t = this.singleAirSwitch ? this.singleAirProgress(animationTicks)
                    : Mth.clamp((animationTicks - this.airSwitchStart) / AIR_SWITCH_TICKS, 0.0F, 1.0F);
            float weight = this.singleAirSwitch ? t : t * t * (3.0F - 2.0F * t);
            for (int i = 0; i < parts.length; i++) {
                if ((i == 2 || i == 3) && (player.isUsingItem() || player.swinging)) continue;
                ModelPart p = parts[i]; float[] from = this.switchAirPose[i];
                p.x = Mth.lerp(weight, from[0], p.x);
                p.y = Mth.lerp(weight, from[1], p.y);
                p.z = Mth.lerp(weight, from[2], p.z);
                p.xRot = blendAngle(from[3], p.xRot, weight);
                p.yRot = blendAngle(from[4], p.yRot, weight);
                // Single-mode targets are rebuilt afresh, not added to a pose.
                if (this.singleAirSwitch || i >= 2) p.zRot = blendAngle(from[5], p.zRot, weight);
            }
            // Keep the single-mode destination owned until leaving the pair.
            // Switching back to partially weighted legacy layers at t=1 would
            // introduce a new recoil. The retained snapshot is only 36 floats.
            if (!this.singleAirSwitch && t >= 1.0F) this.switchAirPose = null;
        }
        this.lastAirPose = captureAirPose(parts);
        this.lastAirPoseTime = animationTicks;
        this.lastRenderedHover = hovering;
        this.lastAirHoverBlend = Mth.clamp(this.getFlight().getHoverBlend(delta), 0.0F, 1.0F);
        this.lastAirWasSlow = !hovering && this.getFlight().canSlowlyDescend()
                && (this.singleAirSwitch || this.getSlowDescentPose(delta) > 0.001F);
        this.lastAirWasPureHover = hovering && this.getHoverTakeoffTime(delta) < 0.0F
                && this.getHoverLandingTime(delta) < 0.0F && this.getFallingPose(delta) <= 0.001F
                && this.normalTransitions.legacyFlyingAmount(delta) <= 0.001F;
        this.hasRenderedAirPose = true;
    }

    public float getSlowDescentPose(float partialTick) {
        float value = Mth.lerp(Mth.clamp(partialTick, 0.0F, 1.0F), this.previousSlowDescentPose, this.slowDescentPose);
        return value * value * (3.0F - 2.0F * value);
    }

    public float getFallingPose(float partialTick) {
        float value = Mth.lerp(Mth.clamp(partialTick, 0.0F, 1.0F), this.previousFallingPose, this.fallingPose);
        return value * value * (3.0F - 2.0F * value);
    }

    public float getFallingLandingBlend(float partialTick) {
        if (this.pendingFallingImpact()) return 0.0F;
        if (!this.fallingImpactLanding) return 1.0F;
        float value = Mth.lerp(Mth.clamp(partialTick, 0.0F, 1.0F),
                this.previousFallingLandingBlend, this.fallingLandingBlend);
        return value * value * (3.0F - 2.0F * value);
    }

    public float getFallingImpact(float partialTick) {
        float value = Mth.lerp(Mth.clamp(partialTick, 0.0F, 1.0F), this.previousFallingImpact, this.fallingImpact);
        return value * value * (3.0F - 2.0F * value);
    }

    public boolean suppressNormalFlightForImpact() {
        AbstractClientPlayer player = this.getHolder();
        // All airborne Normal Flight ground contacts use the landing pose,
        // regardless of approach direction or vertical speed.
        boolean pendingImpact = player.onGround() && this.normalWasAirborne && !this.getFlight().isHovering();
        return this.suppressNormalFlightAfterImpact || pendingImpact;
    }

    public float getNormalLanding(float partialTick) {
        float t = Mth.lerp(Mth.clamp(partialTick, 0.0F, 1.0F), this.previousNormalLanding, this.normalLanding);
        return t * t * (3.0F - 2.0F * t);
    }

    private boolean exitSneakEligible() {
        AbstractClientPlayer player = this.getHolder();
        return !this.getFlight().getWings().isEmpty() && this.getFlight().canUseWings()
                && !player.isFallFlying() && !player.isInWaterOrBubble()
                && !player.isInLava() && !player.isPassenger();
    }

    // Same-frame guard also covers a switch before the next capability tick.
    public boolean suppressAirExitSneak() {
        return !this.getHolder().onGround() && !this.getFlight().isFlying() && !this.getFlight().isHovering()
                && this.exitSneakEligible()
                && (this.airborneFlightSneakGuard || this.normalWasAirborne || this.previousHoverActive);
    }

    // Ground-only fallback when no existing landing animation owns recovery.
    public float getExitGroundSneak(float partialTick) {
        if (!this.getHolder().onGround() || this.getFlight().isFlying() || this.getFlight().isHovering()
                || !this.exitSneakEligible()) return -1.0F;
        // Ground contact can render before tick starts the recovery timer.
        if (this.airborneFlightSneakGuard) return 0.0F;
        if (this.exitSneakRecovery <= 0 && this.previousExitSneakRecovery <= 0) return -1.0F;
        float remaining = Mth.lerp(Mth.clamp(partialTick, 0.0F, 1.0F),
                this.previousExitSneakRecovery, this.exitSneakRecovery);
        float x = Mth.clamp(1.0F - remaining / 6.0F, 0.0F, 1.0F);
        return x * x * (3.0F - 2.0F * x);
    }

    // -1 = no suppression; 0..1 = sneak pose recovery after impact.
    public float getNormalLandingSneak(float partialTick) {
        if (this.normalLanding > 0.0F || this.previousNormalLanding > 0.0F
                || pendingFallingImpact()
                || (this.getHolder().onGround() && this.normalWasAirborne && !this.getFlight().isHovering())) return 0.0F;
        if (this.normalLandingRecovery <= 0 && this.previousNormalLandingRecovery <= 0) return -1.0F;
        float remaining = Mth.lerp(Mth.clamp(partialTick, 0.0F, 1.0F), this.previousNormalLandingRecovery, this.normalLandingRecovery);
        float x = Mth.clamp(1.0F - remaining / 6.0F, 0.0F, 1.0F);
        return x * x * (3.0F - 2.0F * x);
    }

    public float getHoverTakeoffTime(float partialTick) {
        if (this.hoverTakeoffTime < 0.0F) {
            // Same-frame preview of a newly enabled ground takeoff.
            return this.getHolder().onGround() && this.getFlight().isHovering() && !this.previousHoverActive
                    && this.getFlight().getHoverTicks() < 8 && !this.getFlight().isFlying() ? 0.0F : -1.0F;
        }
        return Mth.lerp(Mth.clamp(partialTick, 0.0F, 1.0F), this.previousHoverTakeoffTime, this.hoverTakeoffTime) / 20.0F;
    }

    private boolean canTriggerWingDescentLanding(AbstractClientPlayer player) {
        return !this.getFlight().isHovering() && !this.getFlight().isFlying()
                && this.getFlight().canUseWings()
                && this.getFlight().getWings().flightApparatus().isUsableForSlowlyDescending(player)
                && !player.isFallFlying() && !player.isInWaterOrBubble()
                && !player.isInLava() && !player.isPassenger();
    }

    private boolean isWingDescentAnimation() {
        return this.animator instanceof PresentWingState present
                && present.behavior instanceof PresentWingState.WingStrategy<?> wings
                && (wings.state instanceof StateLand || wings.state instanceof StateFall);
    }

    public float getHoverLandingTime(float partialTick) {
        // Falling uses its own strong landing, never the gentle Hover landing.
        if (this.fallingImpactLanding || this.pendingFallingImpact()) return -1.0F;
        if (this.hoverLandingTime < 0.0F) {
            // Ground contact can be visible to the renderer before tick() has
            // started the landing clock. Treat that gap as the first frame,
            // without mutating state from the render thread.
            AbstractClientPlayer player = this.getHolder();
            boolean hoverLanding = this.hoverRecentlyAirborne > 0 && !this.getFlight().isFlying();
            boolean descentLanding = this.wingDescentRecentlyAirborne > 0 && this.canTriggerWingDescentLanding(player);
            boolean pendingLanding = player.onGround() && this.hoverLandingWasAirborne
                    && (hoverLanding || descentLanding);
            return pendingLanding ? 0.0F : -1.0F;
        }
        return Mth.lerp(Mth.clamp(partialTick, 0.0F, 1.0F), this.previousHoverLandingTime, this.hoverLandingTime) / 20.0F;
    }

    public float getHoverVerticalPose(float partialTick) {
        return Mth.lerp(Mth.clamp(partialTick, 0.0F, 1.0F), this.previousHoverVerticalPose, this.hoverVerticalPose);
    }

    public float getHoverBrakeVisual(float partialTick) {
        return this.animator.getHoverBrakeVisual(Mth.clamp(partialTick, 0.0F, 1.0F));
    }

    public float getHoverSprint(float partialTick) {
        return Mth.lerp(Mth.clamp(partialTick, 0.0F, 1.0F), this.previousHoverSprint, this.hoverSprint);
    }

    public void ifFormPresent(Consumer<WingForm.FormRenderer> consumer) {
        this.animator.ifFormPresent(consumer);
    }

    public void tick() {
        AbstractClientPlayer player = this.getHolder();
        // Remember a brief airborne hover even if its network state clears on landing.
        boolean onGround = player.onGround();
        boolean hoverActive = this.getFlight().isHovering();
        boolean wasHoverActive = this.previousHoverActive;
        this.previousExitSneakRecovery = this.exitSneakRecovery;
        if (!this.exitSneakEligible()) {
            this.airborneFlightSneakGuard = false;
            this.exitSneakRecovery = this.previousExitSneakRecovery = 0;
        } else if (!onGround) {
            if (hoverActive || this.getFlight().isFlying()) this.airborneFlightSneakGuard = true;
            this.exitSneakRecovery = this.previousExitSneakRecovery = 0;
        } else if (this.airborneFlightSneakGuard) {
            this.airborneFlightSneakGuard = false;
            if (!hoverActive && !this.getFlight().isFlying())
                this.exitSneakRecovery = this.previousExitSneakRecovery = 6;
        } else if (hoverActive || this.getFlight().isFlying()) {
            this.exitSneakRecovery = this.previousExitSneakRecovery = 0;
        } else if (this.exitSneakRecovery > 0) {
            this.exitSneakRecovery--;
        }
        this.previousNormalLanding = this.normalLanding;
        this.previousNormalLandingRecovery = this.normalLandingRecovery;
        this.previousFallingImpact = this.fallingImpact;
        boolean fallingContact = this.pendingFallingImpact();
        this.previousFallingPose = this.fallingPose;
        this.previousFallingLandingBlend = this.fallingLandingBlend;
        if (fallingContact) {
            this.fallingLandingBlend = this.previousFallingLandingBlend = 0.0F;
        } else if (this.fallingImpactLanding && onGround) {
            this.fallingLandingBlend = Math.min(1.0F, this.fallingLandingBlend + 0.25F);
        } else {
            this.fallingLandingBlend = this.previousFallingLandingBlend = 0.0F;
        }
        if (fallingContact) {
            this.fallingImpactLanding = true;
            this.fallingImpact = 1.0F;
            this.normalLanding = 1.0F;
            this.normalLandingRecovery = 0;
            this.hoverLandingTime = this.previousHoverLandingTime = -1.0F;
            this.wingDescentRecentlyAirborne = 0;
            this.fallingWingRecentlyAirborne = 0;
        } else {
            this.fallingImpact = Math.max(0.0F, this.fallingImpact - 0.16F);
        }
        if (!fallingContact && onGround && this.normalWasAirborne && !hoverActive) {
            this.normalLanding = 1.0F;
            this.normalLandingRecovery = 0;
            this.suppressNormalFlightAfterImpact = true;
        } else if (!fallingContact && this.normalLanding > 0.0F) {
            if (!onGround || hoverActive) {
                this.normalLanding = this.previousNormalLanding = 0.0F;
                this.normalLandingRecovery = 0;
            } else {
                double dx = player.getX() - player.xo, dz = player.getZ() - player.zo;
                this.normalLanding = Math.max(0.0F, this.normalLanding - (dx * dx + dz * dz > .001D ? .1F : .05F));
                if (this.normalLanding == 0.0F) this.normalLandingRecovery = 6;
            }
        } else if (!fallingContact && this.normalLandingRecovery > 0) {
            if (!onGround || hoverActive || this.getFlight().isFlying()) this.normalLandingRecovery = 0;
            else this.normalLandingRecovery--;
        }
        if (!onGround || hoverActive) {
            this.fallingImpactLanding = false;
            this.fallingImpact = this.previousFallingImpact = 0.0F;
            this.suppressNormalFlightAfterImpact = false;
        } else if (!this.getFlight().isFlying() && this.normalLanding <= 0.0F
                && this.getFlight().getFlyingAmount(1.0F) <= 0.001F) {
            this.suppressNormalFlightAfterImpact = false;
        }
        this.normalWasAirborne = !onGround && this.getFlight().isFlying() && !hoverActive;
        this.previousHoverTakeoffTime = this.hoverTakeoffTime;
        // Preserve idle phase when switching Hover in midair; resetting it
        // while the old blend is still visible makes bob and limbs jump.
        if (onGround && hoverActive && !this.previousHoverActive && this.getFlight().getHoverTicks() < 8
                && !this.getFlight().isFlying()) {
            // The entry reference begins at sin phase ~1.56248 radians.
            // At 17 ticks, idle has that phase; thereafter it advances normally.
            this.hoverIdlePhaseOffset = 15.6247965F - (player.tickCount + 17.0F);
            this.hoverTakeoffTime = this.previousHoverTakeoffTime = 0.0F;
            this.takeoffLeftGround = !onGround;
        } else if (this.hoverTakeoffTime >= 0.0F) {
            if (!hoverActive || this.getFlight().isFlying() || (this.takeoffLeftGround && onGround)) {
                this.hoverTakeoffTime = this.previousHoverTakeoffTime = -1.0F;
            } else if (this.hoverTakeoffTime >= 17.0F) {
                this.hoverTakeoffTime = this.previousHoverTakeoffTime = -1.0F;
            } else {
                if (!onGround) this.takeoffLeftGround = true;
                this.hoverTakeoffTime += 1.0F;
            }
        }
        this.previousHoverActive = hoverActive;
        if (this.getFlight().isHovering() && !onGround) this.hoverRecentlyAirborne = 5;
        else if (this.hoverRecentlyAirborne > 0) this.hoverRecentlyAirborne--;
        if (!this.canTriggerWingDescentLanding(player)) {
            this.wingDescentRecentlyAirborne = 0;
        } else if (!onGround && player.getY() - player.yo < -0.001D && this.isWingDescentAnimation()) {
            this.wingDescentRecentlyAirborne = 4;
        } else if (this.wingDescentRecentlyAirborne > 0) {
            this.wingDescentRecentlyAirborne--;
        }
        if (!onGround && !hoverActive && !this.getFlight().isFlying()
                && !this.getFlight().getWings().isEmpty() && this.getFlight().canUseWings()
                && !this.getFlight().canSlowlyDescend() && player.getY() - player.yo < -0.001D
                && !player.isFallFlying() && !player.isInWaterOrBubble() && !player.isInLava() && !player.isPassenger()
                && this.animator instanceof PresentWingState present
                && present.behavior instanceof PresentWingState.WingStrategy<?> wings && wings.state instanceof StateFall) {
            this.fallingWingRecentlyAirborne = 4;
        } else if (!onGround && (hoverActive || this.getFlight().isFlying() || this.getFlight().canSlowlyDescend())) {
            this.fallingWingRecentlyAirborne = 0;
        } else if (this.fallingWingRecentlyAirborne > 0) {
            this.fallingWingRecentlyAirborne--;
        }
        boolean fallingPoseActive = !onGround && !hoverActive && !this.getFlight().isFlying()
                && !this.getFlight().getWings().isEmpty() && this.getFlight().canUseWings()
                && !this.getFlight().canSlowlyDescend() && player.getY() - player.yo < -0.001D
                && !player.isFallFlying() && !player.isInWaterOrBubble() && !player.isInLava() && !player.isPassenger()
                && this.animator instanceof PresentWingState fallingPresent
                && fallingPresent.behavior instanceof PresentWingState.WingStrategy<?> fallingWings
                && fallingWings.state instanceof StateFall;
        this.fallingPose = Mth.clamp(this.fallingPose + (fallingPoseActive ? 0.2F : -0.25F), 0.0F, 1.0F);
        this.previousSlowDescentPose = this.slowDescentPose;
        boolean slowDescentActive = !onGround && !hoverActive && !this.getFlight().isFlying()
                && !this.getFlight().getWings().isEmpty() && this.getFlight().canSlowlyDescend()
                && player.getY() - player.yo < -0.001D
                && !player.isFallFlying() && !player.isInWaterOrBubble() && !player.isInLava() && !player.isPassenger()
                && this.animator instanceof PresentWingState slowPresent
                && slowPresent.behavior instanceof PresentWingState.WingStrategy<?> slowWings
                && slowWings.state instanceof StateLand;
        if (slowDescentActive) {
            this.slowDescentPose = Math.min(1.0F, this.slowDescentPose + 0.2F);
        } else if (!(hoverActive && !onGround && !this.getFlight().isFlying())) {
            // During airborne Hover entry retain the last descent pose underneath
            // the existing gradual Hover blend, avoiding a dip through vanilla.
            this.slowDescentPose = Math.max(0.0F, this.slowDescentPose - 0.2F);
        }
        if (this.fallingImpactLanding && this.normalLanding <= 0.0F
                && this.previousNormalLanding <= 0.0F && this.normalLandingRecovery <= 0) this.fallingImpactLanding = false;
        this.previousHoverLandingTime = this.hoverLandingTime;
        boolean hoverLanding = this.hoverRecentlyAirborne > 0 && !this.getFlight().isFlying();
        boolean descentLanding = this.wingDescentRecentlyAirborne > 0 && this.canTriggerWingDescentLanding(player);
        if (!this.fallingImpactLanding && onGround && this.hoverLandingWasAirborne && (hoverLanding || descentLanding)) {
            this.hoverLandingTime = this.previousHoverLandingTime = 0.0F;
            this.hoverRecentlyAirborne = 0;
            this.wingDescentRecentlyAirborne = 0;
        } else if (this.hoverLandingTime >= 0.0F) {
            if (!onGround || this.getFlight().isFlying()) {
                this.hoverLandingTime = this.previousHoverLandingTime = -1.0F;
            } else if (this.hoverLandingTime >= 21.0F) {
                this.hoverLandingTime = this.previousHoverLandingTime = -1.0F;
            } else {
                this.hoverLandingTime += 1.0F;
            }
        }
        // A fresh takeoff supersedes an unfinished landing presentation.
        if (this.hoverTakeoffTime >= 0.0F) {
            this.hoverLandingTime = this.previousHoverLandingTime = -1.0F;
        }
        // Animate only the extra first-person Hover hand over four ticks.
        // Landing and manual Hover exit share the same short, reversible transition.
        this.previousHoverHandVisibility = this.hoverHandVisibility;
        boolean normalFlying = this.getFlight().isFlying();
        if (normalFlying) {
            // Normal Flight renders its offhand through the original path.
            this.hoverHandVisibility = this.previousHoverHandVisibility = 0.0F;
        } else if (hoverActive && !wasHoverActive && this.previousFirstPersonFlying) {
            // The hand was already visible in Normal Flight; don't blink on mode switch.
            this.hoverHandVisibility = this.previousHoverHandVisibility = 1.0F;
        } else {
            boolean showHoverHand = hoverActive && this.getHoverLandingTime(1.0F) < 0.0F;
            this.hoverHandVisibility = Mth.clamp(this.hoverHandVisibility + (showHoverHand ? 0.25F : -0.25F), 0.0F, 1.0F);
        }
        this.previousFirstPersonFlying = normalFlying;
        this.hoverLandingWasAirborne = !onGround;
        this.previousHoverMotion = this.hoverMotion;
        this.hoverMotion = player.isLocalPlayer() ? player.getDeltaMovement()
                : new Vec3(player.getX() - player.xo, player.getY() - player.yo, player.getZ() - player.zo);
        // Displacement, rather than the key state, also fades on collision.
        this.previousHoverVerticalPose = this.hoverVerticalPose;
        double dy = player.getY() - player.yo;
        float verticalTarget = this.getFlight().isHovering()
                ? (float) (Math.signum(dy) * Mth.clamp((Math.abs(dy) - 0.012D) / 0.12D, 0.0D, 1.0D)) : 0.0F;
        // Slower decay gives a visible return instead of snapping back to idle.
        float blendRate = Math.abs(verticalTarget) < Math.abs(this.hoverVerticalPose) ? 0.10F : 0.18F;
        this.hoverVerticalPose += (verticalTarget - this.hoverVerticalPose) * blendRate;
        this.previousHoverSprint = this.hoverSprint;
        double yaw = Math.toRadians(player.yBodyRot);
        double forwardMotion = -Math.sin(yaw) * (player.getX() - player.xo)
                + Math.cos(yaw) * (player.getZ() - player.zo);
        Minecraft minecraft = Minecraft.getInstance();
        boolean sprintForward = player == minecraft.player
                ? minecraft.screen == null && minecraft.options.keySprint.isDown() && minecraft.options.keyUp.isDown()
                : player.isSprinting() && forwardMotion > 0.02D;
        float target = this.getFlight().isHovering() && sprintForward ? 1.0F : 0.0F;
        this.hoverSprint += (target - this.hoverSprint) * 0.18F;
        this.animator = WingForm.get(this.getFlight().getWings().flightApparatus())
                .map(view -> this.animator.next(view))
                .orElseGet(this.animator::nextAbsent);
        this.animator.update(this.getFlight(), this.getHolder());
    }

    public boolean resetEyeHeight() {
        return this.getFlight().isFlying() || (this.getFlight().getFlyingAmount(1.0F) > 0.0F && this.getHolder().getPose() == Pose.FALL_FLYING);
    }

    public FlightCapability getFlight() {
        return ModRegistry.FLIGHT_CAPABILITY.get(this.getHolder());
    }

    private interface Strategy {

        default float getHoverBrakeVisual(float delta) { return 0.0F; }
        default Vec3 getNormalFlightMotion(float delta) { return Vec3.ZERO; }

        void update(FlightCapability flight, Player player);

        void ifFormPresent(Consumer<WingForm.FormRenderer> consumer);
    }

    interface WingState {

        default float getHoverBrakeVisual(float delta) { return 0.0F; }
        default Vec3 getNormalFlightMotion(float delta) { return Vec3.ZERO; }

        WingState nextAbsent();

        WingState next(WingForm<?> form);

        void update(FlightCapability flight, Player player);

        void ifFormPresent(Consumer<WingForm.FormRenderer> consumer);
    }

    private record PresentWingState(WingForm<?> wing, Strategy behavior) implements WingState {
        static final WingState VOID = new WingState() {
            @Override
            public WingState nextAbsent() {
                return this;
            }

            @Override
            public WingState next(WingForm<?> form) {
                return PresentWingState.newState(form);
            }

            @Override
            public void update(FlightCapability flight, Player player) {
                // NO-OP
            }

            @Override
            public void ifFormPresent(Consumer<WingForm.FormRenderer> consumer) {
                // NO-OP
            }
        };

        @Override
        public float getHoverBrakeVisual(float delta) { return this.behavior.getHoverBrakeVisual(delta); }

        @Override
        public Vec3 getNormalFlightMotion(float delta) {
            return this.behavior.getNormalFlightMotion(delta);
        }

        @Override
        public WingState nextAbsent() {
            return VOID;
        }

        @Override
        public WingState next(WingForm<?> form) {
            if (this.wing.equals(form)) {
                return this;
            } else {
                return PresentWingState.newState(form);
            }
        }

        @Override
        public void update(FlightCapability flight, Player player) {
            this.behavior.update(flight, player);
        }

        @Override
        public void ifFormPresent(Consumer<WingForm.FormRenderer> consumer) {
            this.behavior.ifFormPresent(consumer);
        }

        public static <T extends Animator> WingState newState(WingForm<T> shape) {
            return new PresentWingState(shape, new WingStrategy<>(shape));
        }

        private static class WingStrategy<T extends Animator> implements Strategy {
            private final WingForm<T> shape;
            private final T animator;
            private State state;
            // Per-player visual cue, not a physics or network value.
            private int hoverBrakeHold;
            private float hoverBrakeStrength;

            public WingStrategy(WingForm<T> shape) {
                this.shape = shape;
                this.animator = shape.createAnimator();
                this.state = new StateIdle();
            }

            @Override
            public void update(FlightCapability flight, Player player) {
                double dx = player.getX() - player.xo;
                double dz = player.getZ() - player.zo;
                float horizontalSpeed = (float) Math.sqrt(dx * dx + dz * dz);
                // Use the local player's forward input and actual forward
                // displacement in the same look-relative coordinates. Remote
                // inputs are not reliably available; do not guess at braking.
                Minecraft mc = Minecraft.getInstance();
                boolean canBrake = player == mc.player && mc.screen == null && flight.isHovering()
                        && !flight.isFlying() && !player.onGround()
                        && !player.isInWaterOrBubble() && !player.isInLava() && !player.isPassenger();
                if (!canBrake) {
                    this.hoverBrakeHold = 0;
                    this.hoverBrakeStrength = 0.0F;
                } else {
                    double lookYaw = Math.toRadians(player.getYRot());
                    double forward = -Math.sin(lookYaw) * dx + Math.cos(lookYaw) * dz;
                    float input = player.zza;
                    // Opposing input must be substantial and the old motion
                    // visible; side strafing alone must not trigger this cue.
                    if (Math.abs(input) > 0.2F && Math.abs(forward) > 0.04D && input * forward < 0.0D) {
                        // Once the speed threshold is crossed, give the
                        // Glide-like opening time to become visible.
                        this.hoverBrakeStrength = 1.0F;
                        this.hoverBrakeHold = 8;
                    } else if (this.hoverBrakeHold > 0) {
                        --this.hoverBrakeHold;
                    } else {
                        this.hoverBrakeStrength = 0.0F;
                    }
                }
                this.animator.setHoverBrake(this.hoverBrakeStrength);
                this.animator.setNormalFlightContext(flight.isFlying(), flight.isHovering(), player.onGround());
                this.animator.setHoverMotion(flight.isHovering(), flight.getHoverVertical(1.0F), horizontalSpeed);
                float sprint = flight.isHovering() ? ClientModRegistry.FLIGHT_VIEW_CAPABILITY.getIfProvided(player)
                        .map(view -> view.getHoverSprint(1.0F)).orElse(0.0F) : 0.0F;
                this.animator.setHoverSprint(sprint);
                this.animator.update();
                State state = this.state.update(flight,
                        player.getX() - player.xo,
                        player.getY() - player.yo,
                        player.getZ() - player.zo,
                        player
                );
                if (!this.state.equals(state)) {
                    state.beginAnimation(this.animator);
                }
                this.state = state;
            }

            @Override
            public float getHoverBrakeVisual(float delta) { return this.animator.getHoverBrakeVisual(delta); }

            @Override
            public Vec3 getNormalFlightMotion(float delta) {
                return this.animator.getNormalFlightMotion(delta);
            }

            @Override
            public void ifFormPresent(Consumer<WingForm.FormRenderer> consumer) {
                consumer.accept(new WingForm.FormRenderer() {
                    @Override
                    public ResourceLocation getTexture() {
                        return WingStrategy.this.shape.getTextureLocation();
                    }

                    @Override
                    public void render(PoseStack matrixStack, VertexConsumer buffer, int packedLight, int packedOverlay, float red, float green, float blue, float alpha, float delta) {
                        WingStrategy.this.shape.getModel()
                                .render(WingStrategy.this.animator,
                                        delta,
                                        matrixStack,
                                        buffer,
                                        packedLight,
                                        packedOverlay,
                                        red,
                                        green,
                                        blue,
                                        alpha
                                );
                    }
                });
            }
        }
    }
}
