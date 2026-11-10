package fuzs.fantasticwings.client.animator;

import com.google.common.collect.ImmutableMap;
import fuzs.fantasticwings.util.MathHelper;
import net.minecraft.world.level.levelgen.LegacyRandomSource;
import net.minecraft.world.level.levelgen.synth.SimplexNoise;
import net.minecraft.world.phys.Vec3;

public final class AnimatorAvian implements Animator {
    private static final int LAND_TRANSITION_DURATION = 8;
    private static final int GLIDE_TRANSITION_DURATION = 14;
    private static final int IDLE_TRANSITION_DURATION = 18;
    private static final int LIFT_TRANSITION_DURATION = 10;
    private static final int FALL_TRANSITION_DURATION = 8;

    private final Movement restPosition = new RestPosition();
    private Movement movement = new IdleMovement();
    private LiftMovement hoverSprintLift = new LiftMovement();
    private final GlideMovement hoverBrakeGlide = new GlideMovement();
    private float hoverSprint, previousHoverSprint, targetHoverSprint;
    private float hoverBrake, previousHoverBrake, targetHoverBrake;
    // Visual-only boost, armed by a ground-origin Normal activation. Never
    // infer takeoff from a Glide/Hover-to-Normal switch in the air.
    private boolean normalActive, hoverActive, previousNormal, previousHover, previousGround;
    private boolean contextInitialized;
    private int takeoffTicks = -1;
    private float takeoffPower, previousTakeoffPower;
    private float glideStartPhase;
    private float prevFlapCycle;
    private float flapCycle;
    private float targetHoverAscent, targetHoverDescent;
    private float targetHoverLevel, hoverLevel, previousHoverLevel;
    private float targetHoverMotion, hoverMotion, previousHoverMotion;
    private float hoverAscent, previousHoverAscent, hoverDescent, previousHoverDescent;

    @Override
    public void setHoverBrake(float amount) {
        this.targetHoverBrake = Math.max(0.0F, Math.min(1.0F, amount));
    }

    @Override
    public float getHoverBrakeVisual(float delta) {
        return MathHelper.lerp(this.previousHoverBrake, this.hoverBrake, Math.max(0.0F, Math.min(1.0F, delta)));
    }

    @Override
    public void setNormalFlightContext(boolean normal, boolean hover, boolean onGround) {
        this.normalActive = normal;
        this.hoverActive = hover;
        if (this.contextInitialized && normal && !this.previousNormal && !hover && !this.previousHover
                && (onGround || this.previousGround)) {
            this.takeoffTicks = 0;
        }
        if (!normal || hover) this.takeoffTicks = -1;
        this.previousNormal = normal;
        this.previousHover = hover;
        this.previousGround = onGround;
        this.contextInitialized = true;
    }

    @Override
    public void setHoverMotion(boolean hovering, float verticalSpeed, float horizontalSpeed) {
        this.targetHoverLevel = hovering ? 1.0F : 0.0F;
        this.targetHoverMotion = hovering ? Math.max(0.0F, Math.min(1.0F, horizontalSpeed / 0.75F)) : 0.0F;
        this.targetHoverAscent = hovering ? Math.max(0.0F, Math.min(1.0F, verticalSpeed)) : 0.0F;
        this.targetHoverDescent = hovering ? Math.max(0.0F, Math.min(1.0F, -verticalSpeed)) : 0.0F;
    }

    @Override
    public void setHoverSprint(float amount) {
        this.targetHoverSprint = Math.max(0.0F, Math.min(1.0F, amount));
    }

    private void beginMovement(Movement movement, int transitionDuration) {
        this.setMovement(new Transition(this.movement, movement, transitionDuration));
    }

    private void setMovement(Movement movement) {
        this.movement.onEnd();
        this.movement = movement;
    }

    private void flap(float amount) {
        this.flapCycle += amount;
    }

    private float getFlapTime(float delta) {
        return MathHelper.lerp(this.prevFlapCycle, this.flapCycle, delta);
    }

    @Override
    public void beginLand() {
        this.beginMovement(new LandMovement(), LAND_TRANSITION_DURATION);
    }

    @Override
    public void beginGlide() {
        // Align the three-cycle envelope with the existing wing phase. The
        // transition starts from the current pose; the clock itself never resets.
        this.glideStartPhase = (float) (Math.floor(this.flapCycle / (2.0D * Math.PI)) * (2.0D * Math.PI));
        this.beginMovement(new GlideMovement(), GLIDE_TRANSITION_DURATION);
    }

    @Override
    public void beginIdle() {
        this.beginMovement(new IdleMovement(), IDLE_TRANSITION_DURATION);
    }

    @Override
    public void beginLift() {
        this.beginMovement(new LiftMovement(), LIFT_TRANSITION_DURATION);
    }

    @Override
    public void beginFall() {
        this.beginMovement(new FallMovement(), FALL_TRANSITION_DURATION);
    }

    @Override
    public Vec3 getNormalFlightMotion(float delta) {
        float sprint = MathHelper.lerp(this.previousHoverSprint, this.hoverSprint, delta);
        float lift = this.normalBodyWeight(this.movement, delta, true);
        float glide = this.normalBodyWeight(this.movement, delta, false);
        return new Vec3(this.getFlapTime(delta), MathHelper.lerp(lift, 1.0F, sprint), glide * (1.0F - sprint));
    }

    private float normalBodyWeight(Movement movement, float delta, boolean lift) {
        if (movement instanceof Transition) {
            Transition transition = (Transition) movement;
            float t = MathHelper.easeInOut(MathHelper.lerp(transition.lastTime, transition.time, delta) / transition.duration);
            return MathHelper.lerp(this.normalBodyWeight(transition.start, delta, lift),
                    this.normalBodyWeight(transition.end, delta, lift), t);
        }
        return (lift ? movement instanceof LiftMovement : movement instanceof GlideMovement) ? 1.0F : 0.0F;
    }

    public Vec3 getWingRotation(int index, float delta) {
        Vec3 base = this.blendSprintLift(this.movement.getWingRotation(index, delta),
                this.hoverSprintLift.getWingRotation(index, delta), delta);
        float brake = MathHelper.lerp(this.previousHoverBrake, this.hoverBrake, delta);
        // Reuse the quiet Glide wing shape from the original Normal-flight
        // exit hypothesis. Blend from the live Hover output without resetting
        // the shared phase or adding forces to the player.
        Vec3 target = this.hoverBrakeGlide.getQuietWingRotation(index, delta);
        return new Vec3(MathHelper.lerpDegrees(base.x, target.x, brake),
                MathHelper.lerpDegrees(base.y, target.y, brake),
                MathHelper.lerpDegrees(base.z, target.z, brake));
    }

    public Vec3 getFeatherRotation(int index, float delta) {
        Vec3 base = this.blendSprintLift(this.movement.getFeatherRotation(index, delta),
                this.hoverSprintLift.getFeatherRotation(index, delta), delta);
        float brake = MathHelper.lerp(this.previousHoverBrake, this.hoverBrake, delta);
        Vec3 target = this.hoverBrakeGlide.getFeatherRotation(index, delta);
        return new Vec3(MathHelper.lerpDegrees(base.x, target.x, brake),
                MathHelper.lerpDegrees(base.y, target.y, brake),
                MathHelper.lerpDegrees(base.z, target.z, brake));
    }

    private Vec3 blendSprintLift(Vec3 base, Vec3 lift, float delta) {
        float blend = MathHelper.lerp(this.previousHoverSprint, this.hoverSprint, delta);
        return new Vec3(MathHelper.lerpDegrees(base.x, lift.x, blend),
                MathHelper.lerpDegrees(base.y, lift.y, blend),
                MathHelper.lerpDegrees(base.z, lift.z, blend));
    }

    @Override
    public void update() {
        this.prevFlapCycle = this.flapCycle;
        this.previousTakeoffPower = this.takeoffPower;
        float takeoffTarget = 0.0F;
        if (this.normalActive && !this.hoverActive && this.takeoffTicks >= 0) {
            // 5 seconds: build in the first 4 ticks, then smoothly settle.
            float t = Math.min(1.0F, this.takeoffTicks / 100.0F);
            float attack = Math.min(1.0F, this.takeoffTicks / 4.0F);
            attack = attack * attack * (3.0F - 2.0F * attack);
            float decay = 1.0F - t * t * (3.0F - 2.0F * t);
            takeoffTarget = attack * decay;
            if (++this.takeoffTicks > 100) this.takeoffTicks = -1;
        }
        // This also retires the boost smoothly on switching to Hover.
        this.takeoffPower += (takeoffTarget - this.takeoffPower) * 0.25F;
        this.previousHoverBrake = this.hoverBrake;
        float brakeTarget = this.targetHoverBrake * this.targetHoverLevel;
        float brakeRate = brakeTarget > this.hoverBrake ? 0.45F : 0.17F;
        this.hoverBrake += (brakeTarget - this.hoverBrake) * brakeRate;
        if (this.hoverBrake > 0.001F || brakeTarget > 0.001F) this.hoverBrakeGlide.update();
        this.previousHoverSprint = this.hoverSprint;
        this.hoverSprint += (this.targetHoverSprint - this.hoverSprint) * 0.22F;
        if (this.targetHoverSprint < 0.001F && this.hoverSprint < 0.001F) {
            this.hoverSprint = 0.0F;
        }
        this.previousHoverLevel = this.hoverLevel;
        this.previousHoverMotion = this.hoverMotion;
        this.hoverLevel += (this.targetHoverLevel - this.hoverLevel) * 0.12F;
        this.hoverMotion += (this.targetHoverMotion - this.hoverMotion) * 0.12F;
        this.previousHoverAscent = this.hoverAscent;
        this.previousHoverDescent = this.hoverDescent;
        this.hoverAscent += (this.targetHoverAscent - this.hoverAscent) * 0.12F;
        this.hoverDescent += (this.targetHoverDescent - this.hoverDescent) * 0.12F;
        float baseFlapRate = this.movement.update();
        float liftFlapRate = this.hoverSprint > 0.0F ? this.hoverSprintLift.update() : baseFlapRate;
        this.flap(MathHelper.lerp(baseFlapRate, liftFlapRate, this.hoverSprint));
    }

    float getWeight(int index) {
        return Math.min(Math.abs(index - 1), 2) / 2.0F;
    }

    private interface Movement {

        Vec3 getWingRotation(int index, float delta);

        Vec3 getFeatherRotation(int index, float delta);

        float update();

        default void onEnd() {
            // NO-OP
        }
    }

    private static final class RestPosition implements Movement {
        private final WingPose wing = WingPose.builder()
                .with(0, 0.0D, -23.5D, -16.0D)
                .with(1, 0.0D, 13.0D, 29.0D)
                .with(2, 0.0D, 12.0D, -28.0D)
                .with(3, 0.0D, 4.0D, 18.3D)
                .build();

        private final WingPose feather = WingPose.builder().with(0, 0.0D, 0.0D, 23.48D).build();

        @Override
        public Vec3 getWingRotation(int index, float delta) {
            return this.wing.get(index);
        }

        @Override
        public Vec3 getFeatherRotation(int index, float delta) {
            return this.feather.get(index);
        }

        @Override
        public float update() {
            return 0.0F;
        }

    }

    private final class LandMovement implements Movement {

        @Override
        public Vec3 getWingRotation(int index, float delta) {
            float pos = AnimatorAvian.this.getWeight(index + 1);
            float time = AnimatorAvian.this.getFlapTime(delta);
            float cycle = time - pos * 1.2F;
            double x = (Math.sin(cycle + MathHelper.PI / 2.0D) - 1.0D) / 2.0D * 20.0D + (1.0D - pos) * 50.0D;
            double y = (Math.sin(cycle) * 20.0D + (1.0D - pos) * 14.0D) * (1.0D - pos * (Math.min(Math.sin(cycle + MathHelper.PI),
                    0.0D
            ) / 2.0D + 1.0D) * Math.sin(time));
            float ascent = MathHelper.lerp(AnimatorAvian.this.previousHoverAscent, AnimatorAvian.this.hoverAscent, delta);
            float descent = MathHelper.lerp(AnimatorAvian.this.previousHoverDescent, AnimatorAvian.this.hoverDescent, delta);
            float hover = MathHelper.lerp(AnimatorAvian.this.previousHoverLevel, AnimatorAvian.this.hoverLevel, delta);
            float speed = MathHelper.lerp(AnimatorAvian.this.previousHoverMotion, AnimatorAvian.this.hoverMotion, delta);
            // Add a soft delayed feather-root pulse and open the wings under
            // forward load, without replacing the continuous Land cycle.
            x += hover * (Math.sin(cycle - 0.35D) * (3.0D + speed * 5.0D));
            y += hover * speed * Math.sin(cycle - pos * 0.30D) * 7.0D * pos;
            // Ascent emphasizes the downstroke; descent opens the wings and
            // adds smaller corrective motions, blended onto the Land cycle.
            double downstroke = Math.max(0.0D, Math.sin(cycle));
            x += ascent * (downstroke * 12.0D - 3.0D);
            y += ascent * Math.sin(cycle) * 19.0D * (0.4D + pos * 0.6D);
            y += descent * (-12.0D * pos + Math.sin(cycle * 0.75D) * 4.0D * pos);
            return AnimatorAvian.this.restPosition.getWingRotation(index, delta).add(x, y, 4.0D);
        }

        @Override
        public Vec3 getFeatherRotation(int index, float delta) {
            float ascent = MathHelper.lerp(AnimatorAvian.this.previousHoverAscent, AnimatorAvian.this.hoverAscent, delta);
            float descent = MathHelper.lerp(AnimatorAvian.this.previousHoverDescent, AnimatorAvian.this.hoverDescent, delta);
            float hover = MathHelper.lerp(AnimatorAvian.this.previousHoverLevel, AnimatorAvian.this.hoverLevel, delta);
            float speed = MathHelper.lerp(AnimatorAvian.this.previousHoverMotion, AnimatorAvian.this.hoverMotion, delta);
            double wave = Math.sin(AnimatorAvian.this.getFlapTime(delta) - index * 0.24D - 0.5D);
            return AnimatorAvian.this.restPosition.getFeatherRotation(index, delta)
                    .add(wave * (ascent * 5.0D + hover * (2.0D + speed * 3.0D)), 0.0D,
                            descent * Math.min(index + 1, 4) * 2.0D);
        }

        @Override
        public float update() {
            return 0.67F - AnimatorAvian.this.hoverLevel * 0.12F + AnimatorAvian.this.hoverMotion * 0.12F
                    - AnimatorAvian.this.hoverAscent * 0.20F - AnimatorAvian.this.hoverDescent * 0.14F;
        }
    }

    private final class GlideMovement implements Movement {
        private final SimplexNoise noise = new SimplexNoise(new LegacyRandomSource(1234L));

        private int time;

        private Vec3 getQuietWingRotation(int index, float delta) {
            float pos = AnimatorAvian.this.getWeight(index);
            float time = AnimatorAvian.this.getFlapTime(delta);
            return AnimatorAvian.this.restPosition.getWingRotation(index, delta)
                    .add(0.0D, (Math.sin(time) * 5.0D - 14.0D) * pos, 0.0D);
        }

        @Override
        public Vec3 getWingRotation(int index, float delta) {
            float pos = AnimatorAvian.this.getWeight(index);
            float time = AnimatorAvian.this.getFlapTime(delta);
            // Two quiet cycles, followed by a smooth larger-motion pulse.
            // The amplitude envelope and its derivative vanish at the seam.
            double cycles = (time - AnimatorAvian.this.glideStartPhase) / (2.0D * Math.PI);
            double block = cycles - Math.floor(cycles / 3.0D) * 3.0D;
            double strong = block >= 2.0D ? Math.pow(Math.sin(Math.PI * (block - 2.0D)), 2.0D) : 0.0D;
            double baseY = (Math.sin(time) * 5.0D - 14.0D) * pos;
            double wave = Math.sin(time - pos * 0.65D);
            double strongY = (wave * 15.0D - 14.0D) * pos;
            double x = strong * Math.cos(time - pos * 0.65D) * 5.0D * pos;
            return AnimatorAvian.this.restPosition.getWingRotation(index, delta)
                    .add(x, baseY + (strongY - baseY) * strong, 0.0D);
        }

        @Override
        public Vec3 getFeatherRotation(int index, float delta) {
            double x = this.noise.getValue((this.time + delta) * 0.17D, index * 0.13D) * 1.25D;
            return AnimatorAvian.this.restPosition.getFeatherRotation(index, delta).add(x, 0.0D, 0.0D);
        }

        @Override
        public float update() {
            this.time++;
            return 0.045F;
        }
    }

    private final class IdleMovement implements Movement {
        private final WingPose wing = WingPose.builder()
                .with(0, 40.0D, -60.0D, -50.0D)
                .with(1, 72.0D, 10.0D, 100.0D)
                .with(2, 0.0D, -10.0D, -120.0D)
                .with(3, 10.0D, 0.0D, 100.0D)
                .build();

        private final WingPose feather = WingPose.builder()
                .with(0, 10.0D, 20.0D, 23.48D)
                .with(1, 0.0D, 20.0D, -70.0D)
                .with(2, 0.0D, 10.0D, 40.0D)
                .with(3, -20.0D, 0.0D, 20.0D)
                .build();

        @Override
        public Vec3 getWingRotation(int index, float delta) {
            float pos = AnimatorAvian.this.getWeight(index);
            float time = AnimatorAvian.this.getFlapTime(delta);
            return this.wing.get(index).add(0.0D, Math.sin(time) * 3.0D * pos, 0.0D);
        }

        @Override
        public Vec3 getFeatherRotation(int index, float delta) {
            float pos = AnimatorAvian.this.getWeight(index);
            float time = AnimatorAvian.this.getFlapTime(delta);
            return this.feather.get(index).add(0, -Math.sin(time) * 5.0D * pos, 0.0D);
        }

        @Override
        public float update() {
            return 0.035F;
        }
    }

    private final class LiftMovement implements Movement {
        @Override
        public Vec3 getWingRotation(int index, float delta) {
            float pos = AnimatorAvian.this.getWeight(index);
            float time = AnimatorAvian.this.getFlapTime(delta);
            float cycle = time - pos * 1.2F;
            float power = MathHelper.lerp(AnimatorAvian.this.previousTakeoffPower, AnimatorAvian.this.takeoffPower, delta);
            // The sprint overlay must not inherit a ground takeoff boost.
            if (this == AnimatorAvian.this.hoverSprintLift) power = 0.0F;
            double xScale = 16.0D + 9.0D * power;
            double xOffset = 8.0D + 2.0D * power;
            double yScale = 26.0D + 12.0D * power;
            double x = (Math.sin(cycle + MathHelper.PI / 2.0D) - 1.0D) / 2.0D * xScale + xOffset;
            double y = (Math.sin(cycle) * yScale + 12.0D) * (1.0D - pos * (Math.min(Math.sin(cycle + MathHelper.PI),
                    0.0D
            ) / 2.0D + 1.0D) * Math.sin(time));
            return AnimatorAvian.this.restPosition.getWingRotation(index, delta).add(x, y, 0.0D);
        }

        @Override
        public Vec3 getFeatherRotation(int index, float delta) {
            // Feathers lag behind the wing root to give each downstroke a
            // softer, articulated follow-through instead of moving rigidly.
            float time = AnimatorAvian.this.getFlapTime(delta);
            double follow = Math.sin(time - index * 0.22D - 0.45D) * Math.min(index + 1, 4) * 2.0D;
            return AnimatorAvian.this.restPosition.getFeatherRotation(index, delta).add(follow, 0.0D, -follow * 0.45D);
        }

        @Override
        public float update() {
            // No re-acceleration on an airborne Lift entry or Hover Sprint.
            // The shared phase is advanced once by AnimatorAvian.update().
            return this == AnimatorAvian.this.hoverSprintLift ? 0.225F
                    : 0.225F + 0.150F * AnimatorAvian.this.takeoffPower;
        }
    }

    private static final class FallMovement implements Movement {
        private final SimplexNoise noise = new SimplexNoise(new LegacyRandomSource(1234L));

        private final WingPose wing = WingPose.builder()
                .with(0, 30.0D, -23.0D, -50.0D)
                .with(1, -10.0D, 5.0D, -10.0D)
                .with(2, -30.0D, -20.0D, -20.0D)
                .with(3, -20.0D, 0.0D, 20.0D)
                .build();

        private int time;

        @Override
        public Vec3 getWingRotation(int index, float delta) {
            double n = this.noise.getValue((this.time + delta) * 0.18D, index * 0.13D) * 0.92D * (index + 1);
            return this.wing.get(index).add(n, 0.0D, n);
        }

        @Override
        public Vec3 getFeatherRotation(int index, float delta) {
            double n = this.noise.getValue((this.time + delta) * 0.2D, index * 0.13D) * 1.75D;
            return new Vec3(-n, n * 4.0D, 0.0D);
        }

        @Override
        public float update() {
            this.time++;
            return 0.0F;
        }
    }

    private final class Transition implements Movement {
        private final Movement start;
        private final Movement end;
        private final int duration;
        private int lastTime, time;
        private boolean isActive = true;

        private Transition(Movement start, Movement end, int duration) {
            this.start = start;
            this.end = end;
            this.duration = duration;
        }

        @Override
        public Vec3 getWingRotation(int index, float delta) {
            return this.lerpRotation(index, delta, Movement::getWingRotation);
        }

        @Override
        public Vec3 getFeatherRotation(int index, float delta) {
            return this.lerpRotation(index, delta, Movement::getFeatherRotation);
        }

        private Vec3 lerpRotation(int index, float delta, RotationGetter getter) {
            Vec3 startRot = getter.get(this.start, index, delta);
            Vec3 endRot = getter.get(this.end, index, delta);
            float t = MathHelper.easeInOut(MathHelper.lerp(this.lastTime, this.time, delta) / this.duration);
            return new Vec3(MathHelper.lerpDegrees(startRot.x, endRot.x, t),
                    MathHelper.lerpDegrees(startRot.y, endRot.y, t),
                    MathHelper.lerpDegrees(startRot.z, endRot.z, t)
            );
        }

        @Override
        public float update() {
            this.lastTime = this.time;
            float flapStart = this.start.update();
            float flapEnd = this.end.update();
            float flap = MathHelper.lerp(flapStart, flapEnd, (float) this.time / this.duration);
            if (this.time < this.duration) {
                this.time++;
            } else if (this.isActive) {
                AnimatorAvian.this.setMovement(this.end);
            }
            return flap;
        }

        @Override
        public void onEnd() {
            this.isActive = false;
        }
    }

    @FunctionalInterface
    private interface RotationGetter {

        Vec3 get(Movement movement, int index, float delta);
    }

    private record WingPose(ImmutableMap<Integer, Vec3> rotations) {

        public Vec3 get(int index) {
            return this.rotations.getOrDefault(index, Vec3.ZERO);
        }

        private static Builder builder() {
            return new Builder();
        }

        private static final class Builder {
            private final ImmutableMap.Builder<Integer, Vec3> rotations = ImmutableMap.builder();

            private Builder() {
            }

            private Builder with(int index, double x, double y, double z) {
                this.rotations.put(index, new Vec3(x, y, z));
                return this;
            }

            private WingPose build() {
                return new WingPose(this.rotations.build());
            }
        }
    }
}
