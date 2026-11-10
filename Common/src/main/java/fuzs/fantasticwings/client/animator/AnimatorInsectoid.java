package fuzs.fantasticwings.client.animator;

import fuzs.fantasticwings.util.MathHelper;
import net.minecraft.world.phys.Vec3;

public final class AnimatorInsectoid implements Animator {
    private static final float IDLE_FLAP_RATE = 0.05F;
    private static final float LIFT_FLAP_RATE = 1.2F;
    private float targetFlapRate = IDLE_FLAP_RATE;

    private float flapRate;
    private float prevFlapCycle;
    private float flapCycle;

    @Override
    public void beginLand() {
        this.beginLift();
    }

    @Override
    public void beginGlide() {
        this.beginLift();
    }

    @Override
    public void beginIdle() {
        this.targetFlapRate = IDLE_FLAP_RATE;
    }

    @Override
    public void beginLift() {
        this.targetFlapRate = LIFT_FLAP_RATE;
    }

    @Override
    public void beginFall() {
        this.beginIdle();
    }

    @Override
    public Vec3 getNormalFlightMotion(float delta) {
        // Follow one body-load cycle per THREE fast wing cycles, not a 3 Hz
        // full-strength torso shake. Reading this never advances either clock.
        float rate = Math.max(0.0F, Math.min(1.0F, (this.flapCycle - this.prevFlapCycle) / LIFT_FLAP_RATE));
        return new Vec3(MathHelper.lerp(this.prevFlapCycle, this.flapCycle, delta) / 3.0D, 0.24F * rate, 0.0D);
    }

    public Vec3 getRotation(float delta) {
        return new Vec3(0.0D, Math.sin(MathHelper.lerp(this.prevFlapCycle, this.flapCycle, delta)) * 45.0D - 42.0D, 0.0D);
    }

    @Override
    public void update() {
        this.prevFlapCycle = this.flapCycle;
        this.flapCycle += this.flapRate;
        this.flapRate += (this.targetFlapRate - this.flapRate) * 0.2F;
    }
}
