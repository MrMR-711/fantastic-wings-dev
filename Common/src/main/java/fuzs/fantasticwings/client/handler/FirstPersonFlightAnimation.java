package fuzs.fantasticwings.client.handler;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import fuzs.fantasticwings.flight.FlightCapability;
import fuzs.fantasticwings.client.init.ClientModRegistry;
import fuzs.fantasticwings.init.ModRegistry;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

/** Frame-local, first-person only transforms; the caller owns the pose push/pop. */
public final class FirstPersonFlightAnimation {
    private FirstPersonFlightAnimation() {}

    public static void apply(Player player, InteractionHand hand, ItemStack stack, PoseStack pose, float partialTick, float swingProgress) {
        // Leave every vanilla held item/use/attack pose intact, including maps,
        // shields, bows, crossbows, eating, and interacting with blocks.
        if (!stack.isEmpty() || player.isUsingItem() || swingProgress > 0.01F || player.swinging) return;
        FlightCapability flight = ModRegistry.FLIGHT_CAPABILITY.get(player);
        float dt = Mth.clamp(partialTick, 0.0F, 1.0F);
        float hover = Mth.clamp(flight.getHoverBlend(dt), 0.0F, 1.0F);
        float flying = Mth.clamp(flight.getFlyingAmount(dt), 0.0F, 1.0F);
        if (hover < 0.001F && flying < 0.001F) return;
        HumanoidArm arm = hand == InteractionHand.MAIN_HAND ? player.getMainArm() : player.getMainArm().getOpposite();
        float sign = arm == HumanoidArm.RIGHT ? 1.0F : -1.0F;
        float vertical = Mth.clamp(flight.getHoverVertical(dt), -1.0F, 1.0F);
        float forward = Mth.clamp(flight.getHoverForwardLean(dt) / 12.0F, -1.0F, 1.0F);
        float side = Mth.clamp(flight.getHoverSideLean(dt) / 12.0F, -1.0F, 1.0F);
        // The reference mod has no separate first-person horizontal hand pose.
        // Keep our idle and vertical hand movement, and suppress only the old
        // directional overlay outside the existing sprint transition.
        float sprint = ClientModRegistry.FLIGHT_VIEW_CAPABILITY.getIfProvided(player)
                .map(view -> view.getHoverSprint(dt)).orElse(0.0F);
        forward *= sprint;
        side *= sprint;
        float time = player.tickCount + dt;
        float idle = Mth.sin(time * 0.105F + (sign > 0.0F ? 0.0F : 0.35F)) * 0.7F
                + Mth.sin(time * 0.071F + 1.2F) * 0.3F;
        float prepare = flight.isHovering() && flight.getHoverTicks() < 8
                ? Mth.sin((flight.getHoverTicks() + dt) / 8.0F * (float) Math.PI) : 0.0F;
        // Absolute, bounded pose deltas; never accumulate angles or mutate ModelPart.
        double x = hover * (sign * (0.009D * idle + 0.015D * Math.max(0.0F, -vertical)) + side * 0.020D);
        // Matches the third-person hover rhythm; changes the rendered hand only.
        double y = hover * (0.040D * idle + 0.025D * vertical - 0.025D * prepare);
        double z = hover * (-0.038D * forward - 0.012D * Math.max(0.0F, vertical)) - flying * 0.015D;
        float xAngle = Mth.clamp(hover * (-4.5F * forward - 2.0F * vertical + 2.0F * prepare) - flying * 2.0F, -7.0F, 7.0F);
        float yAngle = Mth.clamp(hover * (side * 2.8F + sign * idle * 0.7F), -4.0F, 4.0F);
        float zAngle = Mth.clamp(hover * (sign * (1.2F * idle + 2.0F * Math.max(0.0F, -vertical)) - side * 2.0F), -5.0F, 5.0F);
        pose.translate(x, y, z);
        pose.mulPose(Axis.XP.rotationDegrees(xAngle));
        pose.mulPose(Axis.YP.rotationDegrees(yAngle));
        pose.mulPose(Axis.ZP.rotationDegrees(zAngle));
    }
}
