package fuzs.fantasticwings.client.handler;

/**
 * Stateless Falling sampler: continuous loop flow plus the approved startup
 * hip/leg correction. No independent clock, entity offset, or frame history.
 * Original authored tables and the existing model adapter stay unchanged.
 */
final class FallingLoopFlow {
    static final float SEAM_SECONDS = 0.16F;
    static final float START_SECONDS = 0.5F;
    static final float RETURN_SECONDS = 0.95F;
    private static final double RAD = Math.PI / 180.0;
    private FallingLoopFlow() {}

    static float sample(float[] times, float[] values, float seconds, int axis,
                        float duration, float[] rootPos, float[] rootRot,
                        float[] waist, float[] leftLeg, float[] rightLeg) {
        float t = seconds % duration;
        if (t < 0.0F) t += duration;
        if (!Float.isFinite(t) || t == 0.0F) return values[axis];
        final float lastCut = duration - SEAM_SECONDS;
        final float base = flow(times, values, t, axis, duration);
        if (t >= RETURN_SECONDS && t <= lastCut) return base;
        if (!(values == rootPos || (axis == 0 &&
                (values == rootRot || values == waist || values == leftLeg || values == rightLeg)))) return base;

        final double relative, q;
        if (t < RETURN_SECONDS) {
            relative = t;
            q = t <= START_SECONDS ? 0.0 : smooth(((double) t - START_SECONDS) /
                    ((double) RETURN_SECONDS - START_SECONDS));
        } else {
            relative = (double) t - duration;
            q = 1.0 - smooth(((double) t - lastCut) / ((double) duration - lastCut));
        }
        double oldRx = flow(times, rootRot, t, 0, duration);
        double oldRy = flow(times, rootRot, t, 1, duration);
        double refRx = reference(times, rootRot, 0, relative);
        double refRy = reference(times, rootRot, 1, relative);
        float newRx = (float) (refRx + q * (oldRx - refRx));
        if (values == rootRot) return newRx;

        if (values == leftLeg || values == rightLeg) {
            double lx = flow(times, leftLeg, t, 0, duration);
            double ly = flow(times, leftLeg, t, 1, duration);
            double lz = flow(times, leftLeg, t, 2, duration);
            double rx = flow(times, rightLeg, t, 0, duration);
            double ry = flow(times, rightLeg, t, 1, duration);
            double rz = flow(times, rightLeg, t, 2, duration);
            double oldMean = 0.5 * (partUpZ(oldRx, oldRy, lx, ly, lz) +
                    partUpZ(oldRx, oldRy, rx, ry, rz));
            double refMean = 0.5 * (
                    partUpZ(refRx, refRy, reference(times, leftLeg, 0, relative),
                            reference(times, leftLeg, 1, relative), reference(times, leftLeg, 2, relative)) +
                    partUpZ(refRx, refRy, reference(times, rightLeg, 0, relative),
                            reference(times, rightLeg, 1, relative), reference(times, rightLeg, 2, relative)));
            double target = refMean * (1.0 - q) + oldMean * q;
            double la = coefficientA(newRx, oldRy, ly, lz), lb = coefficientB(newRx, oldRy, lz);
            double ra = coefficientA(newRx, oldRy, ry, rz), rb = coefficientB(newRx, oldRy, rz);
            double aa = 0.5 * (la * Math.cos(lx * RAD) - lb * Math.sin(lx * RAD)) +
                    0.5 * (ra * Math.cos(rx * RAD) - rb * Math.sin(rx * RAD));
            double bb = 0.5 * (la * Math.sin(lx * RAD) + lb * Math.cos(lx * RAD)) +
                    0.5 * (ra * Math.sin(rx * RAD) + rb * Math.cos(rx * RAD));
            // Same delta on both legs: retain their authored asymmetry.
            return (float) (base + solve(aa, bb, target, 0.0));
        }

        double oldPz = flow(times, rootPos, t, 2, duration);
        double oldHip = oldPz - 12.0 * Math.cos(oldRy * RAD) * Math.sin(oldRx * RAD);
        double refHip = reference(times, rootPos, 2, relative) -
                12.0 * Math.cos(refRy * RAD) * Math.sin(refRx * RAD);
        double targetHip = refHip + (oldHip - refHip) * q;
        double wx = flow(times, waist, t, 0, duration);
        double wy = flow(times, waist, t, 1, duration);
        double wz = flow(times, waist, t, 2, duration);
        double oldCenterZ = oldPz + localCenter(2, oldRx, oldRy, 0.0, wx, wy, wz);
        float newWx = (float) solve(coefficientA(newRx, oldRy, wy, wz),
                coefficientB(newRx, oldRy, wz), (oldCenterZ - targetHip) / 6.0, wx);
        if (values == waist) return newWx;

        // Preserve the original chest-center trajectory in ALL axes. A simple
        // subtraction of root Z would instead shove the whole body forwards.
        double rootZ = axis == 2 ? 0.0 : flow(times, rootRot, t, 2, duration);
        double oldLocal = localCenter(axis, oldRx, oldRy, rootZ, wx, wy, wz);
        double newLocal = localCenter(axis, newRx, oldRy, rootZ, newWx, wy, wz);
        return (float) (base + (axis == 0 ? -1.0 : 1.0) * (oldLocal - newLocal));
    }

    private static double coefficientA(double rx, double ry, double py, double pz) {
        double r0 = Math.sin(ry * RAD), r1 = -Math.cos(ry * RAD) * Math.sin(rx * RAD);
        double r2 = Math.cos(ry * RAD) * Math.cos(rx * RAD);
        return (r0 * Math.cos(pz * RAD) + r1 * Math.sin(pz * RAD)) * Math.sin(py * RAD) -
                r2 * Math.cos(py * RAD);
    }
    private static double coefficientB(double rx, double ry, double pz) {
        return -Math.sin(ry * RAD) * Math.sin(pz * RAD) -
                Math.cos(ry * RAD) * Math.sin(rx * RAD) * Math.cos(pz * RAD);
    }
    private static double partUpZ(double rx, double ry, double px, double py, double pz) {
        return coefficientA(rx, ry, py, pz) * Math.sin(px * RAD) +
                coefficientB(rx, ry, pz) * Math.cos(px * RAD);
    }
    private static double localCenter(int axis, double rx, double ry, double rz,
                                      double wx, double wy, double wz) {
        double sx = Math.sin(wx * RAD), cx = Math.cos(wx * RAD);
        double sy = Math.sin(wy * RAD), cy = Math.cos(wy * RAD);
        double sz = Math.sin(wz * RAD), cz = Math.cos(wz * RAD);
        double x = 6.0 * (cz * sy * sx - sz * cx);
        double y = 12.0 + 6.0 * (sz * sy * sx + cz * cx);
        double z = -6.0 * cy * sx;
        sx = -Math.sin(rx * RAD); cx = Math.cos(rx * RAD);
        sy = -Math.sin(ry * RAD); cy = Math.cos(ry * RAD);
        if (axis == 2) return -sy * x + cy * sx * y + cy * cx * z;
        sz = Math.sin(rz * RAD); cz = Math.cos(rz * RAD);
        if (axis == 0) return cz * cy * x + (cz * sy * sx - sz * cx) * y +
                (cz * sy * cx + sz * sx) * z;
        return sz * cy * x + (sz * sy * sx + cz * cx) * y + (sz * sy * cx - cz * sx) * z;
    }
    private static double solve(double a, double b, double target, double hintDegrees) {
        double x = hintDegrees * RAD;
        for (int i = 0; i < 6; i++) {
            double s = Math.sin(x), c = Math.cos(x), denominator = a * c - b * s;
            if (!Double.isFinite(denominator) || Math.abs(denominator) < 1.0E-8) return hintDegrees;
            x -= (a * s + b * c - target) / denominator;
        }
        return x / RAD;
    }
    private static double smooth(double u) {
        u = Math.max(0.0, Math.min(1.0, u));
        return u * u * u * (10.0 + u * (-15.0 + 6.0 * u));
    }
    private static double reference(float[] times, float[] values, int axis, double t) {
        return values[axis] + t * referenceTangent(times, values, axis);
    }
    private static double referenceTangent(float[] times, float[] values, int axis) {
        int n = times.length - 1;
        double left = slope(times, values, n - 1, axis), right = slope(times, values, 0, axis);
        if (left * right <= 0.0) return 0.0;
        double hl = (double) times[n] - times[n - 1], hr = (double) times[1] - times[0];
        double wl = 2.0 * hr + hl, wr = hr + 2.0 * hl;
        return (wl + wr) / (wl / left + wr / right);
    }
    private static float flow(float[] times, float[] values, float t, int axis, float duration) {
        float lastCut = duration - SEAM_SECONDS;
        if (t >= SEAM_SECONDS && t <= lastCut) return linear(times, values, t, axis);
        // Look through the small edge windows instead of bringing every channel
        // to a shape-preserving stop at the first key. The same tangent is used
        // at both ends; the body is never advanced by a second clock.
        double velocity = ((double) linear(times, values, SEAM_SECONDS, axis) -
                linear(times, values, lastCut, axis)) / (SEAM_SECONDS + (double) duration - lastCut);
        if (t < SEAM_SECONDS) return quintic(values[axis], velocity,
                linear(times, values, SEAM_SECONDS, axis), slope(times, values, segment(times, SEAM_SECONDS), axis),
                SEAM_SECONDS, t);
        return quintic(linear(times, values, lastCut, axis), slope(times, values, segment(times, lastCut), axis),
                values[axis], velocity, (double) duration - lastCut, (double) t - lastCut);
    }
    private static int segment(float[] times, float t) {
        int lo = 0, hi = times.length - 1;
        while (hi - lo > 1) {int mid = (lo + hi) >>> 1; if (times[mid] <= t) lo = mid; else hi = mid;}
        return lo;
    }
    private static float linear(float[] times, float[] values, float t, int axis) {
        int lo = segment(times, t), hi = lo + 1;
        float f = Math.max(0.0F, Math.min(1.0F, (t - times[lo]) / (times[hi] - times[lo])));
        float a = values[lo * 3 + axis], b = values[hi * 3 + axis];
        return a + f * (b - a);
    }
    private static double slope(float[] times, float[] values, int lo, int axis) {
        return ((double) values[(lo + 1) * 3 + axis] - values[lo * 3 + axis]) /
                ((double) times[lo + 1] - times[lo]);
    }
    private static float quintic(double a, double da, double b, double db, double width, double elapsed) {
        double u = Math.max(0.0, Math.min(1.0, elapsed / width));
        double d = b - a, va = da * width, vb = db * width;
        double c3 = 10.0 * d - 6.0 * va - 4.0 * vb;
        double c4 = -15.0 * d + 8.0 * va + 7.0 * vb;
        double c5 = 6.0 * d - 3.0 * va - 3.0 * vb;
        return (float) (a + u * (va + u * u * (c3 + u * (c4 + u * c5))));
    }
}
