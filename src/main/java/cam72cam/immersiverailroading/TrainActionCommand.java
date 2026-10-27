package cam72cam.immersiverailroading;

import cam72cam.immersiverailroading.entity.EntityMoveableRollingStock;
import cam72cam.immersiverailroading.entity.EntityRollingStock;
import cam72cam.mod.entity.Entity;
import cam72cam.mod.entity.Player;
import cam72cam.mod.text.Command;
import cam72cam.mod.text.PlayerMessage;

import java.util.Optional;
import java.util.function.Consumer;

/**
 * Root commands used from inside a train: {@code /crack} leaves the rails, {@code /explossuion} detonates the consist.
 */
public final class TrainActionCommand extends Command {
    private final String name;
    private final boolean explode;

    public TrainActionCommand(String name, boolean explode) {
        this.name = name;
        this.explode = explode;
    }

    @Override
    public String getPrefix() {
        return name;
    }

    @Override
    public String getUsage() {
        return "Usage: /" + name + " while riding a train";
    }

    @Override
    public int getRequiredPermissionLevel() {
        return PermissionLevel.LEVEL4;
    }

    @Override
    public boolean execute(Consumer<PlayerMessage> sender, Optional<Player> player, String[] args) {
        if (player.isEmpty()) {
            sender.accept(PlayerMessage.direct("Ride a train to use /" + name));
            return true;
        }
        Entity riding = player.get().getRiding();
        if (!(riding instanceof EntityRollingStock stock)) {
            sender.accept(PlayerMessage.direct("You must be inside a train"));
            return true;
        }
        if (!(stock instanceof EntityMoveableRollingStock moveable)) {
            sender.accept(PlayerMessage.direct("This stock cannot " + (explode ? "explode" : "leave the rails")));
            return true;
        }
        if (explode) {
            moveable.explodeConsist();
            sender.accept(PlayerMessage.direct("The train explodes"));
        } else {
            moveable.derailConsist();
            sender.accept(PlayerMessage.direct("The train leaves the rails"));
        }
        return true;
    }
}
