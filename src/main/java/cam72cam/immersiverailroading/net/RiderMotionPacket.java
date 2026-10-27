package cam72cam.immersiverailroading.net;

import cam72cam.immersiverailroading.entity.EntityMoveableRollingStock;
import cam72cam.immersiverailroading.entity.EntityRidableRollingStock;
import cam72cam.mod.entity.Entity;
import cam72cam.mod.entity.Player;
import cam72cam.mod.math.Vec3d;
import cam72cam.mod.net.Packet;
import cam72cam.mod.serialization.TagField;

/**
 * Client movement input for a player walking inside a train.
 * The dedicated server does not receive vanilla move-strafe, so without this packet every other
 * client keeps the rider at the seat the server last stored.
 */
public class RiderMotionPacket extends Packet {
    @TagField
    private EntityMoveableRollingStock stock;
    @TagField
    private float x;
    @TagField
    private float y;
    @TagField
    private float z;

    public RiderMotionPacket() {
    }

    public RiderMotionPacket(EntityMoveableRollingStock stock, Vec3d motion) {
        this.stock = stock;
        this.x = (float) motion.x;
        this.y = (float) motion.y;
        this.z = (float) motion.z;
    }

    @Override
    protected void handle() {
        if (!(stock instanceof EntityRidableRollingStock)) {
            return;
        }
        Player player = getPlayer();
        Entity riding = player == null ? null : player.getRiding();
        if (riding == null || stock == null || !stock.getUUID().equals(riding.getUUID())) {
            return;
        }
        ((EntityRidableRollingStock) stock).acceptRiderMotion(player, new Vec3d(x, y, z));
    }
}
