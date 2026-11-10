package fuzs.fantasticwings.flight;

import fuzs.fantasticwings.FantasticWings;
import fuzs.fantasticwings.config.ServerConfig;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.block.state.BlockState;
import fuzs.fantasticwings.flight.apparatus.FlightApparatus;
import fuzs.fantasticwings.init.ModRegistry;
import fuzs.fantasticwings.network.ServerboundControlFlyingMessage;
import fuzs.fantasticwings.network.ServerboundHoverMessage;
import fuzs.fantasticwings.util.CubicBezier;
import fuzs.fantasticwings.util.MathHelper;
import fuzs.puzzleslib.api.capability.v3.data.CapabilityComponent;
import fuzs.puzzleslib.api.network.v3.PlayerSet;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

public final class FlightCapability extends CapabilityComponent<Player> {
    private static final String KEY_HOVER = FantasticWings.id("hover").toString();
    private static final String KEY_IS_FLYING = FantasticWings.id("is_flying").toString();
    private static final String KEY_TIME_FLYING = FantasticWings.id("time_flying").toString();
    private static final String KEY_WING_TYPE = FantasticWings.id("wing_type").toString();
    private static final CubicBezier FLY_AMOUNT_CURVE = new CubicBezier(0.37F, 0.13F, 0.3F, 1.12F);
    private static final int INITIAL_TIME_FLYING = 0;
    private static final int MAX_TIME_FLYING = 20;
    private static final float MIN_SPEED = 0.03F;
    private static final float MAX_SPEED = 0.0715F;
    private static final float Y_BOOST = 0.05F;
    private static final float MANUAL_Y_BOOST = 0.06F;
    private static final float FALL_REDUCTION = 0.9F;
    private static final float PITCH_OFFSET = 30.0F;
    // Independent hover tuning parameters (blocks/tick and blend per tick).
    private static final double HOVER_VERTICAL_INPUT_SPEED = 0.46D;
    private static final double HOVER_VERTICAL_SPEED_LIMIT = 0.68D;
    private static final double HOVER_VERTICAL_RESPONSE = 0.35D;
    private static final double HOVER_VERTICAL_BLEND = 0.40D;
    private static final double HOVER_MAX_VERTICAL_ACCELERATION = 0.16D;

    private int prevTimeFlying = INITIAL_TIME_FLYING;
    private int timeFlying = INITIAL_TIME_FLYING;
    private boolean isFlying;
    // Server-only previous airborne sample for impact detection.
    private boolean normalImpactAirborne;
    // Consecutive server samples matching the Falling wing-state conditions.
    private int wingFallingTicks;
    private double normalImpactPreviousSpeed;
    private boolean hovering;
    private int hoverTicks;
    private boolean hoverAirborne;
    private double hoverTargetY;
    private boolean hoverCapturing;
    private float hoverVertical, prevHoverVertical;
    private float hoverLeg, prevHoverLeg;
    private float hoverLegTarget;
    private int nextLegChange;
    private float hoverBlend, prevHoverBlend;
    private float hoverForwardLean, prevHoverForwardLean;
    private float hoverSideLean, prevHoverSideLean;

    public boolean isHovering() { return this.hovering; }
    public int getHoverTicks() { return this.hoverTicks; }
    public float getHoverBlend(float partialTick) {
        return Mth.lerp(partialTick, this.prevHoverBlend, this.hoverBlend);
    }

    public float getHoverForwardLean(float partialTick) {
        return Mth.lerp(partialTick, this.prevHoverForwardLean, this.hoverForwardLean);
    }
    public float getHoverSideLean(float partialTick) {
        return Mth.lerp(partialTick, this.prevHoverSideLean, this.hoverSideLean);
    }

    public float getHoverVertical(float delta) { return Mth.lerp(delta, this.prevHoverVertical, this.hoverVertical); }
    public float getHoverLeg(float delta) { return Mth.lerp(delta, this.prevHoverLeg, this.hoverLeg); }

    private void beginHoverPose(Player player, boolean airborne) {
        this.hoverTicks = airborne ? 8 : 0;
        this.hoverAirborne = airborne;
        this.hoverCapturing = airborne;
        this.hoverTargetY = player.getY() + (airborne ? 0.0D : 1.5D);
        this.hoverLegTarget = player.getRandom().nextBoolean() ? 1.0F : -1.0F;
        this.hoverLeg = this.prevHoverLeg = this.hoverLegTarget;
        this.nextLegChange = this.hoverTicks + 180 + player.getRandom().nextInt(121);
    }

    public void setHovering(boolean active, @Nullable PlayerSet recipients) {
        if (this.hovering == active) return;
        this.hovering = active;
        boolean airborne = !this.getHolder().onGround();
        if (active) {
            this.beginHoverPose(this.getHolder(), airborne);
            this.getHolder().unRide();
        } else {
            this.hoverCapturing = false;
        }
        this.setChanged(recipients);
    }

    private FlightApparatus.Holder holder = FlightApparatus.Holder.empty();

    public void setIsFlying(boolean isFlying) {
        this.setIsFlying(isFlying, null);
    }

    public void setIsFlying(boolean isFlying, @Nullable PlayerSet playerSet) {
        if (this.isFlying != isFlying) {
            this.isFlying = isFlying;
            if (this.isFlying) {
                this.getHolder().unRide();
            }
            this.setChanged(playerSet);
        }
    }

    public boolean isFlying() {
        return this.isFlying;
    }

    public void toggleIsFlying(PlayerSet playerSet) {
        this.setIsFlying(!this.isFlying, playerSet);
    }

    public void setTimeFlying(int timeFlying) {
        if (this.timeFlying != timeFlying) {
            this.timeFlying = timeFlying;
            this.setChanged(PlayerSet.ofNone());
        }
    }

    public void setWings(FlightApparatus.Holder flightApparatus) {
        if (!this.holder.is(flightApparatus)) {
            this.holder = flightApparatus;
            this.setChanged();
        }
    }

    public FlightApparatus.Holder getWings() {
        return this.holder;
    }

    public float getFlyingAmount(float delta) {
        return FLY_AMOUNT_CURVE.eval(MathHelper.lerp(this.prevTimeFlying, this.timeFlying, delta) / MAX_TIME_FLYING);
    }

    public boolean canUseWings() {
        return !this.getHolder().getAbilities().flying && !this.getHolder()
                .getItemBySlot(EquipmentSlot.CHEST)
                .is(ModRegistry.WING_OBSTRUCTIONS);
    }

    public boolean canFly() {
        return this.holder.flightApparatus().isUsableForFlying(this.getHolder()) && this.canUseWings();
    }

    public boolean canSlowlyDescend() {
        return this.holder.flightApparatus().isUsableForSlowlyDescending(this.getHolder()) && this.canUseWings() && (
                this.isFlying() || !this.getHolder().isDescending());
    }

    private void onWornUpdate() {
        Player player = this.getHolder();
        if (!player.level().isClientSide && this.isFlying() && !this.canFly()) {
            this.setIsFlying(false);
        }
        if (this.hovering && player.isEffectiveAi()) {
            this.tickHoverMovement(player);
            return;
        }
        if (player.isEffectiveAi()) {
            if (this.isFlying()) {
                float speed = Mth.clampedLerp(MIN_SPEED, MAX_SPEED, player.zza);
                float elevationBoost = MathHelper.transform(Math.abs(player.getXRot()), 45.0F, 90.0F, 1.0F, 0.0F);
                float pitch = -MathHelper.toRadians(player.getXRot() - PITCH_OFFSET * elevationBoost);
                float yaw = -MathHelper.toRadians(player.getYRot()) - MathHelper.PI;
                float vxz = -Mth.cos(pitch);
                float vy = Mth.sin(pitch);
                float vz = Mth.cos(yaw);
                float vx = Mth.sin(yaw);
                player.setDeltaMovement(player.getDeltaMovement()
                        .add(vx * vxz * speed,
                                vy * speed + Y_BOOST * (player.getXRot() > 0.0F ? elevationBoost : 1.0D),
                                vz * vxz * speed));
                // similar to swimming where jumping and sneaking help with ascending / descending
                if (player.jumping) {
                    // with the elevation boost this can get quite crazy, so don't add as much as when descending
                    player.setDeltaMovement(player.getDeltaMovement().add(0.0, MANUAL_Y_BOOST / 2.0F, 0.0));
                } else if (player.isDescending()) {
                    player.setDeltaMovement(player.getDeltaMovement().add(0.0, -MANUAL_Y_BOOST, 0.0));
                }
            }
            if (this.canSlowlyDescend()) {
                Vec3 deltaMovement = player.getDeltaMovement();
                if (deltaMovement.y() < 0.0D) {
                    player.setDeltaMovement(deltaMovement.multiply(1.0D, FALL_REDUCTION, 1.0D));
                }
                player.fallDistance = 0.0F;
            }
        }
    }

    private void tickHoverMovement(Player player) {
        Vec3 velocity = player.getDeltaMovement();
        boolean ascend = player.jumping;
        boolean descend = player.isDescending();
        // Suppress the vanilla jump impulse while retaining its state for altitude control.
        player.jumping = false;
        if (this.hoverTicks < 8) {
            player.setDeltaMovement(velocity.x(), Math.min(velocity.y(), 0.0D), velocity.z());
            return;
        }
        // Capture current height while braking; do not pull the player back to
        // the height at which T was pressed after arresting a fast fall/jump.
        if (this.hoverCapturing) {
            this.hoverTargetY = player.getY();
            if (Math.abs(velocity.y()) < 0.14D) this.hoverCapturing = false;
        } else if (ascend != descend) {
            this.hoverTargetY += ascend ? HOVER_VERTICAL_INPUT_SPEED : -HOVER_VERTICAL_INPUT_SPEED;
        }
        // Keep the altitude target near the player in case of collision with a ceiling.
        this.hoverTargetY = Mth.clamp(this.hoverTargetY, player.getY() - 2.0D, player.getY() + 2.0D);
        double vertical = Mth.clamp((this.hoverTargetY - player.getY()) * HOVER_VERTICAL_RESPONSE,
                -HOVER_VERTICAL_SPEED_LIMIT, HOVER_VERTICAL_SPEED_LIMIT);
        // Horizontal motion is handled by Minecraft's normal travel pipeline,
        // using the hover-only flying acceleration in PlayerMixin. Leave the
        // collision and drag results intact rather than driving a target velocity.
        double desiredY = Mth.lerp(HOVER_VERTICAL_BLEND, velocity.y(), vertical);
        double verticalChange = Mth.clamp(desiredY - velocity.y(), -HOVER_MAX_VERTICAL_ACCELERATION, HOVER_MAX_VERTICAL_ACCELERATION);
        player.setDeltaMovement(velocity.x(), velocity.y() + verticalChange + 0.08D, velocity.z());
        if (!this.hoverCapturing && Math.abs(player.getY() - player.yo) < 0.15D) player.fallDistance = 0.0F;
    }

    // Enhanced Flight 1.0.6 impact VFX, purely cosmetic and server broadcast,
    // once per ground contact following Normal Flight or Falling wing descent.
    private void tickNormalImpact(Player player) {
        if (!(player.level() instanceof ServerLevel level)) return;
        boolean ground = player.onGround();
        boolean fallingLanding = ground && this.wingFallingTicks >= 4
                && !this.isFlying() && !this.isHovering() && !this.holder.isEmpty()
                && this.canUseWings() && !player.isFallFlying() && !player.isInWaterOrBubble()
                && !player.isInLava() && !player.isPassenger();
        if (ground && !this.isHovering() && (this.normalImpactAirborne || fallingLanding)) {
            ServerConfig cfg = FantasticWings.CONFIG.get(ServerConfig.class);
            if (cfg.normalLandingEffects) {
                // Full 3D displacement per tick from the last airborne sample.
                // Both Normal Flight and Falling landings scale their effects
                // with approach speed; a gentle landing retains a small effect.
                double strength = Mth.clamp(this.normalImpactPreviousSpeed, 0.0D, 4.0D);
                double t = Mth.clamp(strength / 0.8D, 0.0D, 1.0D);
                double intensity = 0.2D + 0.8D * (t * t * (3.0D - 2.0D * t));
                double x = player.getX(), y = player.getY(), z = player.getZ();
                int count = Math.max(4, (int) Math.round((16 + (int) (strength * 8.0D)) * intensity));
                double speed = (0.15D + strength * 0.12D) * intensity;
                for (int i = 0; i < count; i++) {
                    double a = i * Math.PI * 2.0D / count;
                    double vx = Math.cos(a) * speed, vz = Math.sin(a) * speed;
                    level.sendParticles(ParticleTypes.CLOUD, x + Math.cos(a) * 0.3D, y + 0.15D, z + Math.sin(a) * 0.3D, 0, vx, 0.02D, vz, 1.0D);
                    if (i % 2 == 0) level.sendParticles(ParticleTypes.POOF, x + Math.cos(a) * 0.15D, y + 0.1D, z + Math.sin(a) * 0.15D, 0, vx * 0.6D, 0.05D, vz * 0.6D, 1.0D);
                }
                int smokeCount = Math.max(2, (int) Math.round(12 * intensity));
                for (int i = 0; i < smokeCount; i++) {
                    double a = Math.toRadians(i * (360.0D / smokeCount) + player.getRandom().nextFloat() * 10.0D);
                    double dist = 0.3D + player.getRandom().nextFloat() * 1.5D * intensity;
                    double v = (0.03D + strength * 0.02D) * intensity;
                    level.sendParticles(ParticleTypes.CAMPFIRE_COSY_SMOKE, x + Math.cos(a) * dist, y + 0.05D, z + Math.sin(a) * dist, 0, Math.cos(a) * v, 0.01D, Math.sin(a) * v, 1.0D);
                }
                BlockState block = level.getBlockState(player.blockPosition().below());
                if (!block.isAir()) {
                    for (int i = 0, n = Math.max(2, (int) Math.round((10 + (int) (strength * 10.0D)) * intensity)); i < n; i++) {
                        double ox = (player.getRandom().nextFloat() - .5D) * 1.5D * intensity;
                        double oz = (player.getRandom().nextFloat() - .5D) * 1.5D * intensity;
                        double vx = (player.getRandom().nextFloat() - .5D) * .15D * intensity;
                        double vy = (.05D + player.getRandom().nextFloat() * .2D) * intensity;
                        double vz = (player.getRandom().nextFloat() - .5D) * .15D * intensity;
                        level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, block), x + ox, y + .1D, z + oz, 0, vx, vy, vz, 1.0D);
                    }
                }
                level.playSound(null, x, y, z, SoundEvents.GENERIC_EXPLODE, SoundSource.PLAYERS, (.8F + (float) strength * .3F) * (float) intensity, .6F + (float) strength * .1F);
                level.playSound(null, x, y, z, SoundEvents.ANVIL_LAND, SoundSource.PLAYERS, .5F * (float) intensity, .4F);
            }
        }
        if (ground) {
            this.normalImpactAirborne = false;
            this.normalImpactPreviousSpeed = 0.0D;
            this.wingFallingTicks = 0;
        } else {
            this.normalImpactAirborne = this.isFlying() && !this.isHovering();
            double dx = player.getX() - player.xo;
            double dy = player.getY() - player.yo;
            double dz = player.getZ() - player.zo;
            this.normalImpactPreviousSpeed = Math.sqrt(dx * dx + dy * dy + dz * dz);
            // Server approximation of StateFall: descending, winged, neither
            // flight mode active, and slow descent unavailable (e.g. Shift).
            boolean falling = !this.isFlying() && !this.isHovering() && !this.holder.isEmpty()
                    && this.canUseWings() && !this.canSlowlyDescend() && dy < 0.0D
                    && !player.isFallFlying() && !player.isInWaterOrBubble()
                    && !player.isInLava() && !player.isPassenger();
            this.wingFallingTicks = falling ? Math.min(this.wingFallingTicks + 1, 4) : 0;
        }
    }

    public void tick() {
        Player player = this.getHolder();
        this.tickNormalImpact(player);
        this.prevHoverBlend = this.hoverBlend;
        this.prevHoverVertical = this.hoverVertical;
        this.prevHoverLeg = this.hoverLeg;
        float verticalTarget = this.hovering ? (float) Mth.clamp((player.getY() - player.yo) / 0.24D, -1.0D, 1.0D) : 0.0F;
        this.hoverVertical += (verticalTarget - this.hoverVertical) * 0.18F;
        if (this.hovering && this.hoverTicks >= this.nextLegChange) {
            this.hoverLegTarget = -this.hoverLegTarget;
            this.nextLegChange = this.hoverTicks + 180 + player.getRandom().nextInt(121);
        }
        this.hoverLeg += (this.hoverLegTarget - this.hoverLeg) * 0.045F;
        this.prevHoverForwardLean = this.hoverForwardLean;
        this.prevHoverSideLean = this.hoverSideLean;
        double yaw = Math.toRadians(player.yBodyRot);
        double dx = player.getX() - player.xo;
        double dz = player.getZ() - player.zo;
        float targetForwardLean = this.hovering ? (float) Mth.clamp((-Math.sin(yaw) * dx + Math.cos(yaw) * dz) * 55.0D, -12.0D, 12.0D) : 0.0F;
        float targetSideLean = this.hovering ? (float) Mth.clamp((Math.cos(yaw) * dx + Math.sin(yaw) * dz) * 55.0D, -12.0D, 12.0D) : 0.0F;
        this.hoverForwardLean += (targetForwardLean - this.hoverForwardLean) * 0.25F;
        this.hoverSideLean += (targetSideLean - this.hoverSideLean) * 0.25F;
        this.hoverBlend += ((this.hovering ? 1.0F : 0.0F) - this.hoverBlend) * 0.16F;
        if (this.hovering) {
            this.hoverTicks++;
            if (!player.level().isClientSide && this.hoverTicks > 12 && Math.abs(player.getY() - player.yo) < 0.15D) player.fallDistance = 0.0F;
            if (!player.onGround()) this.hoverAirborne = true;
            if (!player.level().isClientSide && (!this.canFly() || this.holder.isEmpty() || (this.hoverAirborne && player.onGround()))) {
                this.setHovering(false, PlayerSet.nearEntity(player));
            } else if (player.isLocalPlayer() && this.hoverAirborne && player.onGround()) {
                this.setHovering(false, PlayerSet.ofNone());
                FantasticWings.NETWORK.sendToServer(new ServerboundHoverMessage(false));
            }
        }
        if (!this.holder.isEmpty() || !player.isEffectiveAi()) {
            this.onWornUpdate();
        } else if (!player.level().isClientSide && this.isFlying()) {
            this.setIsFlying(false);
        }
        this.prevTimeFlying = this.timeFlying;
        if (this.isFlying()) {
            if (this.timeFlying < MAX_TIME_FLYING) {
                this.setTimeFlying(this.timeFlying + 1);
            } else if (player.isLocalPlayer() && player.onGround()) {
                this.setIsFlying(false, PlayerSet.ofNone());
                FantasticWings.NETWORK.sendToServer(new ServerboundControlFlyingMessage(false));
            }
        } else if (this.timeFlying > INITIAL_TIME_FLYING) {
            this.setTimeFlying(this.timeFlying - 1);
        }
    }

    public void onFlown(Vec3 direction) {
        Player player = this.getHolder();
        if (this.isFlying()) {
            this.holder.flightApparatus().onFlying(player, direction);
        } else if (this.canSlowlyDescend() && player.getDeltaMovement().y() < -0.5) {
            this.holder.flightApparatus().onSlowlyDescending(player, direction);
        }
    }

    @Override
    public void write(CompoundTag compoundTag, HolderLookup.Provider registries) {
        compoundTag.putBoolean(KEY_IS_FLYING, this.isFlying);
        compoundTag.putBoolean(KEY_HOVER, this.hovering);
        compoundTag.putInt(KEY_TIME_FLYING, this.timeFlying);
        compoundTag.put(KEY_WING_TYPE, this.holder.writeToNbtTag());
    }

    @Override
    public void read(CompoundTag compoundTag, HolderLookup.Provider registries) {
        this.isFlying = compoundTag.getBoolean(KEY_IS_FLYING);
        boolean newHover = compoundTag.getBoolean(KEY_HOVER);
        if (newHover != this.hovering) {
            this.hovering = newHover;
            if (newHover) this.beginHoverPose(this.getHolder(), !this.getHolder().onGround());
            else this.hoverCapturing = false;
        }
        this.timeFlying = compoundTag.getInt(KEY_TIME_FLYING);
        this.holder = FlightApparatus.Holder.readFromNbtTag(compoundTag.getCompound(KEY_WING_TYPE));
    }
}
