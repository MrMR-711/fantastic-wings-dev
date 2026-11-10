package fuzs.fantasticwings.client.flight;

import net.minecraft.world.phys.Vec3;

/**
 * Pure Normal-only presentation targets, in degrees. The input phase and mode
 * weights are READ from the existing wing animator, never advanced here.
 * No tick/render state, random wobble, physics, camera or joint-position changes.
 */
public final class NormalFlightBodyMotion {
    public final float pitchDegrees;
    public final float headPitchDegrees;
    public final float armPitchDegrees;
    public final float armRollDegrees;
    public final float legSpreadDegrees;
    public final float bankTrailDegrees;
    private final float rightLegDegrees, leftLegDegrees;

    private NormalFlightBodyMotion(float pitch, float head, float arm, float roll,
                                   float spread, float rightLeg, float leftLeg, float bankTrail) {
        this.pitchDegrees = pitch;
        this.headPitchDegrees = head;
        this.armPitchDegrees = arm;
        this.armRollDegrees = roll;
        this.legSpreadDegrees = spread;
        this.bankTrailDegrees = bankTrail;
        this.rightLegDegrees = rightLeg;
        this.leftLegDegrees = leftLeg;
    }

    private static float clamp(float value) { return Math.max(0.0F, Math.min(1.0F, value)); }

    public static NormalFlightBodyMotion sample(Vec3 wing, float horizontalSpeed) {
        return sample(wing, horizontalSpeed, 0.0F, 0.0F);
    }

    public static NormalFlightBodyMotion sample(Vec3 wing, float horizontalSpeed,
                                                 float bankDegrees, float lookPitchDegrees) {
        double phase = Double.isFinite(wing.x) ? wing.x : 0.0D;
        float lift = Double.isFinite(wing.y) ? clamp((float) wing.y) : 0.0F;
        float glide = Double.isFinite(wing.z) ? clamp((float) wing.z) : 0.0F;
        float total = lift + glide;
        if (total > 1.0F) { lift /= total; glide /= total; }
        float speed = Float.isFinite(horizontalSpeed) ? clamp(horizontalSpeed * 1.2F) : 0.0F;

        // Wing downstroke passes its midpoint near phase zero. The torso load
        // peaks just after it; leg follow-through arrives another ~0.09 s later
        // at the settled Avian flap rate. Never alternate the feet like walking.
        float load = pulse(phase - 0.40D);
        float legRight = pulse(phase - 0.96D);
        float legLeft = pulse(phase - 1.02D);
        float shoulder = pulse(phase - 0.68D);
        float drift = (float) Math.sin(phase - 0.30D);
        float driftLeg = (float) Math.sin(phase - 0.85D);

        // An actual shallow arch, not a flat torso followed by dropped legs.
        // On a steep climb the existing look transform already raises the rig;
        // smoothly retire the extra four degrees rather than double-pitching.
        float up = clamp(-(Float.isFinite(lookPitchDegrees) ? lookPitchDegrees : 0.0F) / 30.0F);
        float arch = 4.0F * (1.0F - up * up * (3.0F - 2.0F * up));
        float pitch = 5.0F + arch - 0.65F * speed + 2.65F * lift * load + 0.45F * glide * drift;

        // A bank can put the high boot over the waist even with a safe neutral
        // angle. Trail the pair ONLY while banked, not a permanently low stance.
        // C1 at zero roll, bounded in sharp turns, and no second smoothing state.
        double bank = Math.toRadians(Float.isFinite(bankDegrees) ? bankDegrees : 0.0F);
        double sin = Math.sin(bank), epsilon = Math.sin(Math.toRadians(2.0D));
        double softBank = sin * sin / Math.sqrt(sin * sin + epsilon * epsilon);
        float bankTrail = 14.0F * (float) Math.tanh(64.0D * softBank / 14.0D);
        float head = -50.0F + pitch + 0.12F * lift * shoulder;
        float arm = 0.55F * pitch + 0.75F * lift * shoulder + 0.12F * glide * driftLeg;
        float spread = 1.90F + 0.07F * (lift * legRight + 0.4F * glide * drift);
        return new NormalFlightBodyMotion(pitch, head, arm, 0.28F * lift * shoulder,
                spread, 3.0F + arch + 1.65F * lift * legRight + 0.30F * glide * driftLeg,
                3.55F + arch + 1.70F * lift * legLeft + 0.30F * glide * driftLeg, bankTrail);
    }

    public float rightLegPitchDegrees(float turnRadians) {
        return this.legPitch(turnRadians, this.rightLegDegrees, 2.5F);
    }

    public float leftLegPitchDegrees(float turnRadians) {
        return this.legPitch(-turnRadians, this.leftLegDegrees, 2.8F);
    }

    private float legPitch(float turn, float rest, float turnRangeDegrees) {
        double range = Math.toRadians(turnRangeDegrees);
        float turnDegrees = turnRangeDegrees * (float) Math.tanh(0.3D * turn / range);
        // Bound trailing inclination in the flight-aligned Normal rig, not
        // just the local hip target. Both unbanked [-4.4, +0.6] degree limits
        // are soft. Banking shifts this envelope down, never the actual body
        // roll, player input or flight trajectory.
        float relative = rest + turnDegrees - this.pitchDegrees;
        return this.pitchDegrees - 1.9F + 2.5F * (float) Math.tanh((relative + 1.9F) / 2.5F) - this.bankTrailDegrees;
    }

    /** Periodic C1 authored load/recovery curve; no spring/overshoot or reset. */
    public static float pulse(double phase) {
        double u = phase / (2.0D * Math.PI);
        u -= Math.floor(u);
        if (u < 0.18D) return hermite(u / 0.18D, 1.0F, 0.20F, 0.0F, -5.2F, 0.18F);
        if (u < 0.48D) return hermite((u - 0.18D) / 0.30D, 0.20F, -0.80F, -5.2F, 0.0F, 0.30F);
        if (u < 0.78D) return hermite((u - 0.48D) / 0.30D, -0.80F, -0.35F, 0.0F, 4.0F, 0.30F);
        return hermite((u - 0.78D) / 0.22D, -0.35F, 1.0F, 4.0F, 0.0F, 0.22F);
    }

    private static float hermite(double t, float a, float b, float ma, float mb, float span) {
        double t2 = t * t, t3 = t2 * t;
        return (float) ((2.0D*t3 - 3.0D*t2 + 1.0D)*a + (t3 - 2.0D*t2 + t)*span*ma
                + (-2.0D*t3 + 3.0D*t2)*b + (t3 - t2)*span*mb);
    }
}
