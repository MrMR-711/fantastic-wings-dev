package fuzs.fantasticwings.client.flight;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.util.Mth;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/** Client presentation only. No velocity, collision, controls, camera or wing state. */
public final class NormalFlightTransitions {
    private enum Mode { NONE, LAUNCH, NORMAL, HOVER, BASE, ABORT, IMPACT, AIR_BRIDGE }
    private static final float TO_NORMAL = 8.0F;       // 0.40 s, airborne
    private static final float GROUND_RELEASE = 7.0F; // 0.35 s AFTER actual lift-off
    private static final float TO_HOVER = 7.2F;       // 0.36 s
    private static final float IMPACT_JOIN = 2.8F;    // 0.14 s, overlaps existing clock
    private static final Quaternionf MODEL_FLIP = new Quaternionf(0.0F, 0.0F, 1.0F, 0.0F);
    private static final float MODEL_OFFSET = 1.501F;
    private final FlightViewCapability view;
    private Mode mode = Mode.NONE;
    private float start, duration, preparedTime = Float.NaN;
    private boolean history, observedFlying, observedHover, impactLatched;
    // This override is restricted to an airborne Normal -> base exit.
    private boolean normalBaseExit, lastBaseFallTarget;
    private float lastPoseTime = -1000.0F, lastRootTime = -1000.0F, lastNormalAirTime = -1000.0F;
    private float[][] lastPose, sourcePose;
    private Root lastRoot, sourceRoot = new Root(), targetRoot = new Root();
    private boolean lastPoseWasNormal;

    private static final class Root {
        final Vector3f position = new Vector3f();
        final Quaternionf rotation = new Quaternionf();
        final Vector3f scale = new Vector3f(1.0F);
        Root() {}
        Root(Root other) { position.set(other.position); rotation.set(other.rotation); scale.set(other.scale); }
        Root(Matrix4f matrix) {
            matrix.getTranslation(position); matrix.getScale(scale);
            matrix.getUnnormalizedRotation(rotation).normalize();
        }
    }

    public NormalFlightTransitions(FlightViewCapability view) { this.view = view; }

    private static float clamp(float value) { return Mth.clamp(value, 0.0F, 1.0F); }
    private static float smooth(float value) { float t = clamp(value); return t * t * (3.0F - 2.0F * t); }
    private static float brake(float value) { float t = clamp(value); return t * t * (6.0F + t * (-8.0F + 3.0F * t)); }
    private static float window(float t, float from, float to) { return smooth((t - from) / (to - from)); }
    private float delta(float now) { return clamp(now - this.view.getRenderPlayer().tickCount); }
    private boolean recent(float now) {
        return lastPose != null && lastRoot != null && now >= lastPoseTime && now >= lastRootTime
                && now - lastPoseTime < 5.0F && now - lastRootTime < 5.0F;
    }
    private static float[][] copy(float[][] input) {
        if (input == null) return null;
        float[][] result = new float[input.length][];
        for (int i = 0; i < input.length; i++) result[i] = input[i].clone();
        return result;
    }
    private void start(Mode next, float now, float ticks) {
        boolean valid = recent(now);
        sourcePose = valid ? copy(lastPose) : null;
        sourceRoot = valid ? new Root(lastRoot) : new Root();
        mode = next; start = now; duration = ticks;
        normalBaseExit = false;
    }
    private float phase(float now) { return duration <= 0.0F ? 1.0F : clamp((now - start) / duration); }

    /** Safe to call from both setupRotations and setupAnim in either order. */
    public void prepare(float now) {
        if ((!Float.isNaN(preparedTime) && now < preparedTime) || (lastPose != null && now - lastPoseTime >= 5.0F)) {
            lastPose = null; lastRoot = null; sourcePose = null; mode = Mode.NONE;
        }
        preparedTime = now;
        AbstractClientPlayer player = view.getRenderPlayer();
        boolean flying = view.getFlight().isFlying(), hover = view.getFlight().isHovering();
        boolean usable = !view.getFlight().getWings().isEmpty() && view.getFlight().canUseWings()
                && !player.isFallFlying() && !player.isInWaterOrBubble() && !player.isInLava() && !player.isPassenger();
        boolean wasFlying = observedFlying;
        observedFlying = flying; observedHover = hover;
        if (!player.onGround()) impactLatched = false;
        if (!usable) { mode = Mode.NONE; sourcePose = null; return; }

        // The old impact trigger, pose, clock, effects and recovery remain the authority.
        // Only its first 2.8 ticks acquire a visual source from the preceding flight.
        boolean contact = player.onGround() && !hover && !impactLatched && now - lastNormalAirTime < 5.0F && recent(now)
                && view.suppressNormalFlightForImpact();
        if (contact) { start(Mode.IMPACT, now, IMPACT_JOIN); history = true; impactLatched = true; return; }
        if (mode == Mode.IMPACT) {
            if (player.onGround() && !hover && phase(now) < 1.0F) return;
            mode = Mode.NONE; sourcePose = null;
        }
        if (view.suppressNormalFlightForImpact() || view.getNormalLanding(delta(now)) > 0.001F) {
            mode = Mode.NONE; sourcePose = null; return;
        }
        if (flying) {
            history = true;
            if (mode == Mode.LAUNCH) {
                if (!player.onGround()) start(Mode.NORMAL, now, GROUND_RELEASE);
            } else if (!wasFlying || (mode != Mode.NORMAL)) {
                boolean established = !recent(now) && view.getFlight().getFlyingAmount(delta(now)) > 0.95F;
                start(player.onGround() ? Mode.LAUNCH : Mode.NORMAL, now,
                        player.onGround() ? 2.0F : TO_NORMAL);
                if (established && !player.onGround()) { sourcePose = null; duration = 0.0F; }
            }
            return;
        }
        if (mode == Mode.ABORT) {
            if (player.onGround() && !hover && phase(now) < 1.0F) return;
            mode = Mode.NONE; sourcePose = null;
        }
        // A view first appearing during the old physical exit must not replay its long tail.
        if (!history && lastPose == null && view.getFlight().getFlyingAmount(delta(now)) > 0.001F) {
            history = true;
            if (hover && !player.onGround()) { start(Mode.HOVER, now, 0.0F); return; }
        }
        if (mode == Mode.NORMAL || mode == Mode.LAUNCH || wasFlying) {
            if (player.onGround()) {
                if (!hover) start(Mode.ABORT, now, 2.8F);
                else { mode = Mode.NONE; sourcePose = null; }
                return;
            }
            start(hover ? Mode.HOVER : Mode.BASE, now, hover ? TO_HOVER : 7.2F);
            if (!hover) {
                normalBaseExit = true;
                lastBaseFallTarget = wantsNormalExitFalling();
            }
        } else if (mode == Mode.HOVER && !hover) {
            // Return directly to the accepted Slow/Hover MODEL controller, even
            // when the new Normal -> Hover join is interrupted. Its source was
            // recorded AFTER our composition: never stack two temporal fades.
            boolean slowPair = view.getFlight().canSlowlyDescend() && view.getFallingPose(delta(now)) <= 0.001F;
            start(slowPair ? Mode.AIR_BRIDGE : Mode.BASE, now, 7.2F);
        } else if (mode == Mode.BASE && hover && !player.onGround()) {
            start(Mode.HOVER, now, TO_HOVER);
        } else if (mode == Mode.BASE) {
            boolean fallTarget = normalBaseExit && wantsNormalExitFalling();
            if (normalBaseExit && fallTarget != lastBaseFallTarget && !player.onGround()) {
                // Shift can change mid-join. Restart from the last displayed
                // pose/root so the two destinations do not snap into each other.
                start(Mode.BASE, now, 7.2F);
                normalBaseExit = true;
                lastBaseFallTarget = fallTarget;
            } else if (phase(now) >= 1.0F) {
                boolean slowReady = view.getSlowDescentPose(delta(now)) >= 0.999F;
                boolean fallReady = view.getFallingPose(delta(now)) >= 0.999F;
                // Keep the full visual Falling target until its real animation
                // has caught up, including while upward momentum remains.
                if (player.onGround() || (fallTarget ? fallReady
                        : !view.getFlight().canSlowlyDescend() || slowReady)) {
                    mode = Mode.NONE; sourcePose = null;
                }
            }
        } else if (mode == Mode.AIR_BRIDGE) {
            if (player.onGround() || view.getFallingPose(delta(now)) > 0.001F
                    || (!hover && !view.getFlight().canSlowlyDescend())) {
                mode = Mode.NONE; sourcePose = null;
            } else if (hover != lastBridgeHover) {
                sourceRoot = lastRoot == null ? new Root() : new Root(lastRoot);
                start = now;
            }
        }
        lastBridgeHover = hover;
        if (player.onGround() && mode != Mode.LAUNCH && mode != Mode.ABORT) { mode = Mode.NONE; sourcePose = null; }
    }
    private boolean lastBridgeHover;

    public boolean ownsModel() { return mode != Mode.NONE && mode != Mode.AIR_BRIDGE; }
    public boolean ownsRoot() { return mode != Mode.NONE; }
    public boolean suppressClips() { return ownsModel() && mode != Mode.IMPACT; }
    public float normalAmount() { return mode == Mode.NORMAL ? 1.0F : history ? 0.0F : -1.0F; }
    public float hoverAmount() {
        if (mode == Mode.HOVER) return 1.0F;
        if (mode == Mode.NORMAL || mode == Mode.LAUNCH || mode == Mode.BASE || mode == Mode.ABORT) return 0.0F;
        return -1.0F;
    }
    public float slowAmount(float original) {
        if (!suppressClips()) return original;
        // The visual destination may start before the delayed wing StateLand
        // is active, but only when the actual slow-descent apparatus is usable.
        boolean airborneSlow = mode == Mode.BASE && !view.getRenderPlayer().onGround()
                && view.getFlight().canSlowlyDescend();
        return airborneSlow ? 1.0F : 0.0F;
    }
    private boolean wantsNormalExitFalling() {
        AbstractClientPlayer player = view.getRenderPlayer();
        return !player.onGround() && !view.getFlight().isFlying() && !view.getFlight().isHovering()
                && player.isDescending() && !view.getFlight().canSlowlyDescend();
    }

    public float fallingAmount(float original) {
        // Both model and root sample this same full destination. The existing
        // coordinator supplies the only crossfade; physics is untouched.
        if (mode == Mode.BASE && normalBaseExit && wantsNormalExitFalling()) return 1.0F;
        return suppressClips() && mode != Mode.BASE ? 0.0F : original;
    }
    public float legacyFlyingAmount(float partialTick) {
        return history && !view.getFlight().isFlying() ? 0.0F : view.getFlight().getFlyingAmount(partialTick);
    }
    public float rootHoverAmount() {
        return mode == Mode.AIR_BRIDGE ? (view.getFlight().isHovering() ? 1.0F : 0.0F) : hoverAmount();
    }

    public void setRootTarget(Matrix4f matrix) {
        targetRoot = new Root(matrix);
        if (mode == Mode.LAUNCH) {
            targetRoot.position.set(0.0F, -0.025F, 0.0F);
            targetRoot.rotation.rotationX((float)Math.toRadians(-5.0));
            targetRoot.scale.set(1.0F);
        }
    }
    private float rootWeight(float now) {
        if (mode == Mode.AIR_BRIDGE && view.usesSingleAirSwitch()) return view.getAirSwitchProgress(now);
        float t = phase(now);
        return mode == Mode.HOVER || mode == Mode.IMPACT ? brake(t) : smooth(t);
    }
    private Root currentRoot(float now) {
        if (!ownsRoot()) return new Root(targetRoot);
        float u = rootWeight(now);
        Root result = new Root();
        result.position.set(sourceRoot.position).lerp(targetRoot.position, u);
        result.rotation.set(sourceRoot.rotation).slerp(targetRoot.rotation, u).normalize();
        result.scale.set(sourceRoot.scale).lerp(targetRoot.scale, u);
        return result;
    }
    public void applyRoot(PoseStack stack, float now) {
        Root root = currentRoot(now);
        stack.translate(root.position.x, root.position.y, root.position.z);
        stack.mulPose(new Quaternionf(root.rotation));
        if (root.scale.x != 1.0F || root.scale.y != 1.0F || root.scale.z != 1.0F)
            stack.scale(root.scale.x, root.scale.y, root.scale.z);
        lastRoot = new Root(root); lastRootTime = now;
    }
    public void recordLegacyRoot(Matrix4f matrix, float now) { lastRoot = new Root(matrix); lastRootTime = now; }

    private static ModelPart[] parts(PlayerModel<?> model) {
        return new ModelPart[]{model.body, model.head, model.rightArm, model.leftArm, model.rightLeg, model.leftLeg};
    }
    private static float[][] snapshot(ModelPart[] parts) {
        float[][] result = new float[6][6];
        for (int i = 0; i < 6; i++) {
            ModelPart p = parts[i]; result[i] = new float[]{p.x,p.y,p.z,p.xRot,p.yRot,p.zRot};
        }
        return result;
    }
    private static Quaternionf rotation(float[] pose) { return new Quaternionf().rotationZYX(pose[5],pose[4],pose[3]); }
    private static Quaternionf worldRotation(Root root, float[] pose) {
        return new Quaternionf(root.rotation).mul(MODEL_FLIP).mul(rotation(pose)).normalize();
    }
    private static Vector3f worldPivot(Root root, float[] pose) {
        Vector3f v = new Vector3f(pose[0]/16.0F, pose[1]/16.0F-MODEL_OFFSET, pose[2]/16.0F);
        MODEL_FLIP.transform(v); root.rotation.transform(v); return v.add(root.position);
    }
    private static void setRotation(ModelPart part, Quaternionf input) {
        // Double-precision normalization avoids an artificial near-gimbal snap
        // from rounding sin(Y) to one in single precision.
        double x=input.x,y=input.y,z=input.z,w=input.w;
        double inv=1.0D/Math.sqrt(x*x+y*y+z*z+w*w);x*=inv;y*=inv;z*=inv;w*=inv;
        double r00=1.0D-2.0D*(y*y+z*z),r10=2.0D*(x*y+w*z),sinY=2.0D*(w*y-z*x);
        double cosY=Math.hypot(r00,r10);
        if (cosY < 1.0E-9D) {
            double r01=2.0D*(x*y-w*z),r11=1.0D-2.0D*(x*x+z*z);
            part.xRot=(float)Math.atan2(Math.copySign(1.0D,sinY)*r01,r11);
            part.yRot=(float)Math.copySign(Math.PI*.5,sinY);part.zRot=0.0F;
        } else {
            part.xRot=(float)Math.atan2(2.0D*(w*x+y*z),1.0D-2.0D*(x*x+y*y));
            part.yRot=(float)Math.atan2(sinY,cosY);
            part.zRot=(float)Math.atan2(r10,r00);
        }
        // JOML's float half-angle reconstruction can lose a tiny cosine near
        // +/- PI. Use the equivalent Euler family only when it is materially
        // more accurate through the SAME quaternion path used by ModelPart.
        float ax=part.xRot+(part.xRot<=0.0F?(float)Math.PI:-(float)Math.PI);
        float ay=part.yRot>=0.0F?(float)Math.PI-part.yRot:-(float)Math.PI-part.yRot;
        float az=part.zRot+(part.zRot<=0.0F?(float)Math.PI:-(float)Math.PI);
        double original=rotationError(part.xRot,part.yRot,part.zRot,x,y,z,w);
        if (original>1.0E-12D && rotationError(ax,ay,az,x,y,z,w)<original*.25D) {
            part.xRot=ax;part.yRot=ay;part.zRot=az;
        }
    }
    private static double rotationError(float x,float y,float z,double tx,double ty,double tz,double tw) {
        Quaternionf q=new Quaternionf().rotationZYX(z,y,x);
        double minus=(q.x-tx)*(q.x-tx)+(q.y-ty)*(q.y-ty)+(q.z-tz)*(q.z-tz)+(q.w-tw)*(q.w-tw);
        double plus=(q.x+tx)*(q.x+tx)+(q.y+ty)*(q.y+ty)+(q.z+tz)*(q.z+tz)+(q.w+tw)*(q.w+tw);
        return Math.min(minus,plus);
    }
    private float partWeight(int index, float now) {
        float t = phase(now);
        if (mode == Mode.IMPACT) return index == 2 || index == 3 ? window(t,.20F,1.0F) : brake(t);
        if (mode == Mode.NORMAL) return index == 2 || index == 3 ? window(t,0.0F,.88F)
                : index >= 4 ? window(t,.04F,1.0F) : smooth(t);
        if (mode == Mode.HOVER) return index >= 4 ? window(t,0.0F,.86F)
                : index == 2 || index == 3 ? window(t,.06F,1.0F) : brake(t);
        return smooth(t);
    }

    public void bootstrapModel(PlayerModel<?> model) {
        if (ownsModel() && sourcePose == null && duration > 0.0F) sourcePose = snapshot(parts(model));
    }

    public void applyModel(PlayerModel<?> model, float now) {
        if (!ownsModel()) return;
        ModelPart[] p = parts(model);
        if (mode == Mode.LAUNCH) {
            model.head.xRot=(float)Math.toRadians(-4);
            if (!view.getRenderPlayer().isUsingItem() && !view.getRenderPlayer().swinging) {
                model.rightArm.xRot=(float)Math.toRadians(-24); model.leftArm.xRot=(float)Math.toRadians(-20);
                model.rightArm.yRot=(float)Math.toRadians(6); model.leftArm.yRot=(float)Math.toRadians(-6);
                model.rightArm.zRot=(float)Math.toRadians(10); model.leftArm.zRot=(float)Math.toRadians(-12);
            }
            model.rightLeg.xRot=(float)Math.toRadians(-2); model.leftLeg.xRot=(float)Math.toRadians(2);
        }
        if (sourcePose == null) {
            // A fresh ground request can bootstrap from the supplied vanilla pose.
            // Established off-screen flight instead starts directly at its destination.
            if (duration <= 0.0F) return;
            sourcePose = lastPose == null ? snapshot(p) : copy(lastPose);
        }
        float t = phase(now);
        if (t >= 1.0F) return; // Exact approved/current destination, no Euler round-trip.
        boolean keepArms = view.getRenderPlayer().isUsingItem() || view.getRenderPlayer().swinging;
        float[][] target = snapshot(p);
        Root current = currentRoot(now);
        Quaternionf parentInverse = new Quaternionf(current.rotation).mul(MODEL_FLIP).invert();
        Quaternionf sourceBody = worldRotation(sourceRoot, sourcePose[0]);
        Quaternionf targetBody = worldRotation(targetRoot, target[0]);
        Quaternionf body = new Quaternionf(sourceBody).slerp(targetBody,partWeight(0,now)).normalize();
        Vector3f fromBody = worldPivot(sourceRoot,sourcePose[0]);
        Vector3f toBody = worldPivot(targetRoot,target[0]);
        Vector3f bodyPivot = new Vector3f(fromBody).lerp(toBody,rootWeight(now));
        for (int i=0;i<6;i++) {
            float u=partWeight(i,now);
            if (!((i==2 || i==3) && keepArms)) {
                Quaternionf q=worldRotation(sourceRoot,sourcePose[i]).slerp(worldRotation(targetRoot,target[i]),u).normalize();
                setRotation(p[i],new Quaternionf(parentInverse).mul(q));
            }
            // Item/swing keeps its vanilla ANGLES, but its shoulder still has
            // to follow the moving torso pivot rather than detach from it.
            Vector3f position;
            if (i==0) position=new Vector3f(bodyPivot);
            else {
                // Preserve each endpoint's authored joint offset. Interpolating raw
                // independent world pivots would pull limbs away from the torso arc.
                Vector3f bind=new Vector3f(p[i].getInitialPose().x-p[0].getInitialPose().x,
                        p[i].getInitialPose().y-p[0].getInitialPose().y,
                        p[i].getInitialPose().z-p[0].getInitialPose().z).mul(1.0F/16.0F);
                Vector3f sourceAnchor=new Vector3f(bind);sourceBody.transform(sourceAnchor);sourceAnchor.add(fromBody);
                Vector3f targetAnchor=new Vector3f(bind);targetBody.transform(targetAnchor);targetAnchor.add(toBody);
                Vector3f offset=worldPivot(sourceRoot,sourcePose[i]).sub(sourceAnchor)
                        .lerp(worldPivot(targetRoot,target[i]).sub(targetAnchor),rootWeight(now));
                position=new Vector3f(bind);body.transform(position);position.add(bodyPivot).add(offset);
            }
            position.sub(current.position);new Quaternionf(current.rotation).invert().transform(position);
            new Quaternionf(MODEL_FLIP).invert().transform(position);position.y+=MODEL_OFFSET;position.mul(16.0F);
            p[i].x=position.x;p[i].y=position.y;p[i].z=position.z;
        }
    }
    public void recordModel(PlayerModel<?> model,float now) {
        lastPose=snapshot(parts(model));lastPoseTime=now;
        lastPoseWasNormal=view.getFlight().isFlying() && mode!=Mode.IMPACT && !view.getRenderPlayer().onGround();
        if (lastPoseWasNormal) lastNormalAirTime=now;
    }
}
