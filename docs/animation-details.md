# Animation details

These notes describe the development source and intended visuals; behavior and transitions may still need in-game testing.

## Hover Flight

- **Default toggle: `V`.** The key can be changed in Minecraft's controls.
- Hover is a separate flight mode rather than a replacement for normal flight. The player can switch between Hover and Normal Flight in the air, with visual transitions between their poses.
- In Hover, the player can stay in the air and move forward, backward and sideways. The body leans in response to horizontal movement instead of remaining completely rigid.
- Reversing forward/backward input during Hover can trigger an Avian braking visual: the body straightens as the wings blend toward the quiet Glide pose, then return to Hover. MrMR711 tested the result and reported that it matched the intended feel. This is a visual effect and does not change movement physics.
- **Space** and **Shift** control ascent and descent. Vertical movement has its own smoothed visual pose and transitions; the descending leg pose is raised slightly without an extra twist.
- Sprinting forward uses a faster, more horizontal pose. Sprint itself does not automatically change altitude: Space and Shift remain available for vertical control. The body keeps a relatively fixed pitch while the head follows the player's view, and turning adds a bank similar to Normal Flight.
- Ground takeoff includes an automatic initial lift and a transition into the hovering pose. Hover's idle bob is reduced during vertical movement; the outgoing takeoff lift and incoming bob use coordinated blending to avoid an abrupt visual height change.
- Hover has a moving idle pose and an occasional idle variation. It is not a selection of all Enhanced Flight styles; the implemented pose is the one adapted for this project.
- In third person, the arms are positioned to suit hovering and the sprint pose. In first person, both hands can be shown in Hover, with a smoother visual transition when the extra hand appears or disappears. Item and camera rendering may still differ in special situations.

Hover uses Fantastic Wings' own flight implementation; it is not actual Creative-mode flight. Its behavior can therefore differ from mods based on vanilla Creative flight, especially around collisions, water, other mods and multiplayer.

## Animation and transition changes

### Hover takeoff and landing

- Added a custom ground-to-Hover takeoff animation, with preparation and a gradual blend into the Hover pose. The authored takeoff motion was adjusted to fit this project's Hover rather than ending in an unrelated idle pose.
- Added a custom Hover landing animation and a smoother return to the standing pose. The animation can also play when landing with Shift held.
- During the relevant landing and flight-exit transitions, the crouch pose may be suppressed **visually** to prevent the model from abruptly snapping into a crouch. This does not disable the actual Sneak key, its gameplay behavior or the player's collision box.

### Avian wing animation

- **Lift:** sustained flight uses the original Fantastic Wings Lift wing-motion amplitude and settled flap rate. On a detected ground-origin Normal Flight takeoff, wing motion briefly becomes stronger and faster before blending back into the sustained pattern. The added feather follow-through remains, so the full animation is not identical to upstream. Ground-origin detection and the visual result still need in-game testing.
- **Glide:** a three-cycle pattern blends two quieter, upstream-like cycles with a third cycle containing a smooth, more pronounced wing movement. The third-cycle movement rises to a peak and fades back down rather than remaining at maximum amplitude for the whole cycle. Entry can begin partway through the pattern, without resetting the wing phase. This is a visual animation and does not add lift or change falling physics.
- **Hover Sprint and transitions:** the wings blend between the existing Hover wing cycle and the quieter Lift motion during Sprint. Normal ↔ Hover, Lift ↔ Glide, and rapid direction changes should still be tested for continuity.

### Wing transitions and slow descent

- Avian wings blend between Lift, Glide, the continuous Land/slow-descent wing cycle, Idle and Falling instead of switching wing poses instantly. The existing Land transition after exiting Normal Flight can have a wing-opening, braking-like appearance, especially when leaving flight at speed. This is an animation transition, **not** a separate speed-triggered brake or additional physical deceleration.
- After leaving Normal Flight in the air, the next wing state depends on actual movement and whether slow descent is available. Descending with usable slow descent can lead into the Land wing cycle; when slow descent is unavailable, the Falling state can be selected instead. Depending on movement, an intermediate state may occur, so this transition is not guaranteed to look identical in every exit.
- While Hover is active, the Land wing cycle continues rather than restarting every tick. Hover's horizontal movement and ascent/descent add blended wing and feather motion on top of that cycle. Hover Sprint blends toward Lift and back when Sprint ends.
- In Normal Flight, the Lift and Glide states are chosen according to the flight and movement conditions. Their wing rotations blend across state changes. The shared wing phase also drives the custom Normal Flight body-motion loop; changing wing amplitude does not automatically scale the body's motion amplitude.

### Normal Flight

- Adjusted the Normal Flight player pose with a more forward-oriented arm position and a body motion loop coordinated with the wings. Head, arms and legs move with the flight pose rather than staying completely stiff.
- Added visual transitions for entering Normal Flight from the ground, switching between Normal and Hover in the air, and leaving Normal Flight. The goal is to reduce abrupt changes between flight, falling and the base player pose.
- Added a superhero-style landing pose for Normal Flight, adapted from Enhanced Flight. It can be used for fast or slow Normal Flight landings, including primarily horizontal or vertical approaches. The effect is visual: it does **not** add an area-damage or knockback shockwave.

### Falling and landing effects

- Added a custom Falling loop adapted to the Java player model, with a smoothed loop seam to reduce snapping when it repeats. Its body, head and limb motion was coordinated with the other flight animations.
- The Falling animation can also be used during ordinary airborne descent with the wings, without requiring Hover or Normal Flight. The existing fall and damage mechanics are retained.
- Landing visuals for Normal Flight and Falling vary with landing speed. These are cosmetic effects rather than an additional damage or knockback mechanic.

Animation blending is an approximation on Minecraft's Java player model. Armor, capes, held items, first-person rendering, servers and other player-animation mods may behave differently. Some transitions may still need further testing.
