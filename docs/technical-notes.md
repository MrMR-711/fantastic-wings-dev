# Technical notes

These notes are based on source review and prior user observations. Potential issues are not confirmed multiplayer failures unless stated.

### Other synchronization concerns to investigate

- **Hover Sprint:** the local client uses the Sprint and Forward keys, while remote players are evaluated using their sprint state and observed forward movement. As a result, the transition into or out of the Sprint pose may differ or be delayed between viewers. This is a code-based possibility, not a confirmed multiplayer bug.
- **Hover idle leg variation:** the initial leg variation and its later changes use random choices, while those variation values are not included in the flight capability's synchronized data. Different viewers may therefore see different idle leg variations or timing; this still needs an in-game multiplayer check.
- **Landing effects and animation timing:** Falling landing effects use server-side fall-state detection, while the client selects and blends its own visual animation. Their timing may not always match, especially with latency; this is a potential issue, not a confirmed failure.

Hover and Normal Flight mode state are synchronized; the notes above do **not** mean that Hover or all flight animations are local-only. More multiplayer testing is needed before drawing broader conclusions.

### Abrupt movement when rapidly reversing turns in Normal Flight

When flying in **Normal Flight**, rapidly turning the view left and then right, or right and then left, can cause a visible jump or jerk in the player's body and/or camera instead of a continuous, smooth change of bank. It is most noticeable when reversing the turn direction quickly; it is a visual movement issue, not a confirmed change in the flight physics.

At first, MrMR711 thought the problem was caused by the modifications. After comparing the behavior and turning code with Fuzss's 1.20.1 version, MrMR711 found that the issue was also present in the original Fuzss code. In testing, restoring the original body-turn behavior removed a separate full-spin symptom, but a visible jump remained. Simply smoothing the bank also did not eliminate the jump. Recent tests were performed with Enhanced Flight disabled to avoid confusing the result with a second animation mod.

The original **Wings source by Pau101 has not been tested** for this issue, so it is unknown whether the same behavior exists there or where it first appeared. The exact underlying cause has not been conclusively identified, and **MrMR711 has not been able to fix it**. Reproduction details, observations and contributions from anyone familiar with player rotations and camera interpolation are welcome.
