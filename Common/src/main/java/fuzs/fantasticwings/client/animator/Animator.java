package fuzs.fantasticwings.client.animator;

import net.minecraft.world.phys.Vec3;

public interface Animator {

    void beginLand();

    void beginGlide();

    void beginIdle();

    void beginLift();

    void beginFall();

    default void setHoverMotion(boolean hovering, float verticalSpeed, float horizontalSpeed) {
        // Other wing rigs may keep their existing animation.
    }

    default void setHoverSprint(float amount) {
        // Rigs without a separate sprint animation keep their current behavior.
    }

    // Client presentation context only. Rigs without an Avian takeoff stay unchanged.
    default void setNormalFlightContext(boolean normal, boolean hover, boolean onGround) {
    }

    // Client-side visual-only braking cue; other rigs retain their animation.
    default void setHoverBrake(float amount) {
    }

    // Presentation weight shared by wing and root rendering.
    default float getHoverBrakeVisual(float partialTick) { return 0.0F; }

    // Read-only body-follow signal: phase radians, Lift weight, Glide weight.
    // Unknown/custom rigs keep their original animation and use a quiet base.
    default Vec3 getNormalFlightMotion(float delta) { return Vec3.ZERO; }

    void update();
}
