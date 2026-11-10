package fuzs.fantasticwings.client.handler;

/** Distinguishes the vanilla first-person arm model setup from third-person setup. */
public final class FirstPersonRenderGuard {
    private static final ThreadLocal<Integer> DEPTH = ThreadLocal.withInitial(() -> 0);
    private FirstPersonRenderGuard() {}
    public static void enter() { DEPTH.set(DEPTH.get() + 1); }
    public static void exit() { int n = DEPTH.get() - 1; if (n <= 0) DEPTH.remove(); else DEPTH.set(n); }
    public static boolean active() { return DEPTH.get() > 0; }
}
