package fuzs.fantasticwings.network;

import fuzs.fantasticwings.flight.FlightCapability;
import fuzs.fantasticwings.init.ModRegistry;
import fuzs.puzzleslib.api.network.v3.PlayerSet;
import fuzs.puzzleslib.api.network.v3.ServerMessageListener;
import fuzs.puzzleslib.api.network.v3.ServerboundMessage;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;

public record ServerboundHoverMessage(boolean hovering) implements ServerboundMessage<ServerboundHoverMessage> {
    @Override
    public ServerMessageListener<ServerboundHoverMessage> getHandler() {
        return new ServerMessageListener<>() {
            @Override
            public void handle(ServerboundHoverMessage message, MinecraftServer server, ServerGamePacketListenerImpl handler, ServerPlayer player, ServerLevel level) {
                FlightCapability flight = ModRegistry.FLIGHT_CAPABILITY.get(player);
                if (!message.hovering()) {
                    flight.setHovering(false, PlayerSet.nearEntity(player));
                } else if (!flight.isHovering() && flight.canFly()) {
                    if (flight.isFlying()) flight.setIsFlying(false, PlayerSet.ofNone());
                    // Broadcast both mode values together to the player and observers.
                    flight.setHovering(true, PlayerSet.nearEntity(player));
                } else {
                    // Correct optimistic client state if the server rejects activation.
                    flight.setChanged(PlayerSet.nearEntity(player));
                }
            }
        };
    }
}
