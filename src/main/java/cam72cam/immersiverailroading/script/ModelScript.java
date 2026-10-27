package cam72cam.immersiverailroading.script;

import cam72cam.immersiverailroading.ImmersiveRailroading;
import cam72cam.immersiverailroading.entity.EntityCoupleableRollingStock;
import cam72cam.immersiverailroading.entity.EntityCoupleableRollingStock.CouplerType;
import cam72cam.immersiverailroading.entity.EntityMoveableRollingStock;
import cam72cam.immersiverailroading.model.part.Door;
import cam72cam.immersiverailroading.script.lua.LuaException;
import cam72cam.immersiverailroading.script.lua.LuaRuntime;
import cam72cam.immersiverailroading.script.lua.LuaValue;
import util.Matrix4;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * One compiled Lua script shared by every entity of a rolling-stock definition.
 * Gameplay events run on the server. {@code on_tick} also runs on the client so pose and visibility
 * stay deterministic without an extra network channel; control writes from the client are ignored.
 */
public final class ModelScript {
    private static final int FAULT_LIMIT = 5;

    private final String name;
    private final LuaRuntime runtime;
    private final Map<UUID, Session> serverSessions = new HashMap<>();
    private final Map<UUID, Session> clientSessions = new HashMap<>();
    private int faults;

    private ModelScript(String name, LuaRuntime runtime) {
        this.name = name;
        this.runtime = runtime;
        runtime.setPrinter(message -> ImmersiveRailroading.info("[%s] %s", name, message));
    }

    public static ModelScript compile(String name, String source) throws LuaException {
        return new ModelScript(name, LuaRuntime.compile(source));
    }

    public void serverTick(EntityMoveableRollingStock stock) {
        tick(stock, true);
    }

    public void clientTick(EntityMoveableRollingStock stock) {
        tick(stock, false);
    }

    public void forget(UUID id) {
        if (id != null) {
            serverSessions.remove(id);
            clientSessions.remove(id);
        }
    }

    public Boolean visibility(EntityMoveableRollingStock stock, String group) {
        Session session = clientSessions.get(stock.getUUID());
        if (session == null) {
            return null;
        }
        return session.visibility.get(group);
    }

    public Matrix4 poseMatrix(EntityMoveableRollingStock stock, String group) {
        Session session = clientSessions.get(stock.getUUID());
        if (session == null) {
            return null;
        }
        Pose pose = session.poses.get(group);
        if (pose == null || !pose.active()) {
            return null;
        }
        Matrix4 matrix = new Matrix4();
        if (pose.hasTranslation) {
            matrix = matrix.translate(pose.tx, pose.ty, pose.tz);
        }
        if (pose.hasPivot) {
            matrix = matrix.translate(pose.px, pose.py, pose.pz);
        }
        if (pose.hasRotation) {
            if (pose.rx != 0) {
                matrix = matrix.rotate(Math.toRadians(pose.rx), 1, 0, 0);
            }
            if (pose.ry != 0) {
                matrix = matrix.rotate(Math.toRadians(pose.ry), 0, 1, 0);
            }
            if (pose.rz != 0) {
                matrix = matrix.rotate(Math.toRadians(pose.rz), 0, 0, 1);
            }
        }
        if (pose.hasScale) {
            matrix = matrix.scale(pose.sx, pose.sy, pose.sz);
        }
        if (pose.hasPivot) {
            matrix = matrix.translate(-pose.px, -pose.py, -pose.pz);
        }
        return matrix;
    }

    public Boolean glow(EntityMoveableRollingStock stock, String group) {
        Session session = clientSessions.get(stock.getUUID());
        if (session == null) {
            return null;
        }
        return session.glow.get(group);
    }

    public Float alpha(EntityMoveableRollingStock stock, String group) {
        Session session = clientSessions.get(stock.getUUID());
        if (session == null) {
            return null;
        }
        return session.alpha.get(group);
    }

    public java.util.Set<String> gifGroups(EntityMoveableRollingStock stock) {
        Session session = clientSessions.get(stock.getUUID());
        if (session == null) {
            return java.util.Set.of();
        }
        return session.gifs.keySet();
    }

    public String gifPath(EntityMoveableRollingStock stock, String group) {
        Session session = clientSessions.get(stock.getUUID());
        if (session == null) {
            return null;
        }
        return session.gifs.get(group);
    }

    private void tick(EntityMoveableRollingStock stock, boolean server) {
        if (faults >= FAULT_LIMIT || stock == null) {
            return;
        }
        Session session = session(stock, server);
        try {
            if (!session.loaded) {
                invoke("on_load", stock, server);
                session.loaded = true;
                snapshot(session, stock);
            }
            if (server) {
                fireControls(session, stock);
                fireDoors(session, stock);
                fireCouplers(session, stock);
                firePassengers(session, stock);
            }
            invoke("on_tick", stock, server);
            if (!server) {
                invoke("on_client_tick", stock, server);
            }
            snapshot(session, stock);
        } catch (LuaException ex) {
            faults++;
            ImmersiveRailroading.error("Lua script %s: %s", name, ex.format(name));
            if (faults == FAULT_LIMIT) {
                ImmersiveRailroading.error("Disabling Lua script %s after %s errors", name, FAULT_LIMIT);
            }
        }
    }

    private void fireControls(Session session, EntityMoveableRollingStock stock) throws LuaException {
        for (Map.Entry<String, Float> entry : new ArrayList<>(session.controls.entrySet())) {
            float current = stock.getControlPosition(entry.getKey());
            if (current != entry.getValue()) {
                invoke("on_control", stock, true, LuaValue.string(entry.getKey()), LuaValue.number(current), LuaValue.number(entry.getValue()));
            }
        }
    }

    private void fireDoors(Session session, EntityMoveableRollingStock stock) throws LuaException {
        for (Door<?> door : stock.getDefinition().getModel().getDoors()) {
            String id = door.controlGroup;
            boolean open = door.isOpen(stock);
            Boolean previous = session.doors.get(id);
            if (previous != null && previous != open) {
                invoke("on_door", stock, true, LuaValue.string(id), LuaValue.bool(open));
            }
        }
    }

    private void fireCouplers(Session session, EntityMoveableRollingStock stock) throws LuaException {
        if (!(stock instanceof EntityCoupleableRollingStock)) {
            return;
        }
        EntityCoupleableRollingStock coupled = (EntityCoupleableRollingStock) stock;
        fireCoupler(session, stock, "front", coupled.isCoupled(CouplerType.FRONT));
        fireCoupler(session, stock, "back", coupled.isCoupled(CouplerType.BACK));
    }

    private void fireCoupler(Session session, EntityMoveableRollingStock stock, String side, boolean coupled) throws LuaException {
        Boolean previous = session.couplers.get(side);
        if (previous == null || previous == coupled) {
            return;
        }
        invoke(coupled ? "on_couple" : "on_uncouple", stock, true, LuaValue.string(side));
    }

    private void firePassengers(Session session, EntityMoveableRollingStock stock) throws LuaException {
        Set<UUID> now = new HashSet<>();
        for (cam72cam.mod.entity.Entity passenger : stock.getPassengers()) {
            now.add(passenger.getUUID());
        }
        for (UUID id : now) {
            if (!session.passengers.contains(id)) {
                invoke("on_mount", stock, true, LuaValue.string(id.toString()));
            }
        }
        for (UUID id : session.passengers) {
            if (!now.contains(id)) {
                invoke("on_dismount", stock, true, LuaValue.string(id.toString()));
            }
        }
    }

    private void snapshot(Session session, EntityMoveableRollingStock stock) {
        session.controls.clear();
        for (var control : stock.getDefinition().getModel().getControls()) {
            session.controls.put(control.controlGroup, stock.getControlPosition(control));
        }
        session.doors.clear();
        for (Door<?> door : stock.getDefinition().getModel().getDoors()) {
            session.doors.put(door.controlGroup, door.isOpen(stock));
        }
        if (stock instanceof EntityCoupleableRollingStock coupled) {
            session.couplers.put("front", coupled.isCoupled(CouplerType.FRONT));
            session.couplers.put("back", coupled.isCoupled(CouplerType.BACK));
        }
        session.passengers.clear();
        for (cam72cam.mod.entity.Entity passenger : stock.getPassengers()) {
            session.passengers.add(passenger.getUUID());
        }
    }

    private void invoke(String event, EntityMoveableRollingStock stock, boolean server, LuaValue... extra) throws LuaException {
        if (runtime.get(event).isNil()) {
            return;
        }
        List<LuaValue> args = new ArrayList<>();
        args.add(stockTable(stock, server));
        for (LuaValue value : extra) {
            args.add(value);
        }
        runtime.call(event, args);
    }

    private LuaValue stockTable(EntityMoveableRollingStock stock, boolean server) throws LuaException {
        LuaValue table = LuaValue.table();
        var api = table.table(0);
        api.set("id", LuaValue.function((rt, args) -> List.of(LuaValue.string(stock.getDefinitionID()))));
        api.set("name", LuaValue.function((rt, args) -> List.of(LuaValue.string(nullToEmpty(stock.getDefinition().name())))));
        api.set("uuid", LuaValue.function((rt, args) -> List.of(LuaValue.string(stock.getUUID().toString()))));
        api.set("speed", LuaValue.function((rt, args) -> List.of(LuaValue.number(stock.getCurrentSpeed().metric()))));
        api.set("gauge", LuaValue.function((rt, args) -> List.of(LuaValue.number(stock.gauge.value()))));
        api.set("tick", LuaValue.function((rt, args) -> List.of(LuaValue.number(stock.getTickCount()))));
        api.set("texture", LuaValue.function((rt, args) -> List.of(LuaValue.string(nullToEmpty(stock.getTexture())))));
        api.set("passengers", LuaValue.function((rt, args) -> List.of(LuaValue.number(stock.getPassengers().size()))));
        api.set("server", LuaValue.function((rt, args) -> List.of(LuaValue.bool(server))));
        api.set("control", LuaValue.function((rt, args) -> List.of(LuaValue.number(stock.getControlPosition(arg(args, 0).asString())))));
        api.set("pressed", LuaValue.function((rt, args) -> List.of(LuaValue.bool(stock.getControlData(arg(args, 0).asString()).getLeft()))));
        api.set("set_control", LuaValue.function((rt, args) -> {
            if (server) {
                stock.setControlPosition(arg(args, 0).asString(), (float) arg(args, 1).asNumber(0));
            }
            return List.of();
        }));
        api.set("door_open", LuaValue.function((rt, args) -> List.of(LuaValue.bool(stock.getControlPosition(arg(args, 0).asString()) > 0.75f))));
        api.set("set_door", LuaValue.function((rt, args) -> {
            if (server) {
                stock.setControlPosition(arg(args, 0).asString(), arg(args, 1).isTruthy() ? 1f : 0f);
            }
            return List.of();
        }));
        api.set("group_visible", LuaValue.function((rt, args) -> {
            String group = arg(args, 0).asString();
            Session session = session(stock, server);
            if (arg(args, 1).isNil()) {
                session.visibility.remove(group);
            } else {
                session.visibility.put(group, arg(args, 1).isTruthy());
            }
            return List.of();
        }));
        api.set("rotate_group", LuaValue.function((rt, args) -> {
            session(stock, server).rotation(arg(args, 0).asString(), arg(args, 1).asNumber(0), arg(args, 2).asNumber(0), arg(args, 3).asNumber(0));
            return List.of();
        }));
        api.set("translate_group", LuaValue.function((rt, args) -> {
            session(stock, server).translation(arg(args, 0).asString(), arg(args, 1).asNumber(0), arg(args, 2).asNumber(0), arg(args, 3).asNumber(0));
            return List.of();
        }));
        api.set("clear_pose", LuaValue.function((rt, args) -> {
            Session session = session(stock, server);
            if (arg(args, 0).isNil()) {
                session.poses.clear();
                session.glow.clear();
                session.alpha.clear();
                session.gifs.clear();
            } else {
                String group = arg(args, 0).asString();
                session.poses.remove(group);
                session.glow.remove(group);
                session.alpha.remove(group);
                session.gifs.remove(group);
            }
            return List.of();
        }));
        api.set("scale_group", LuaValue.function((rt, args) -> {
            scale(session(stock, server), arg(args, 0).asString(), arg(args, 1), arg(args, 2), arg(args, 3));
            return List.of();
        }));
        api.set("pivot_group", LuaValue.function((rt, args) -> {
            session(stock, server).pivot(arg(args, 0).asString(), arg(args, 1).asNumber(0), arg(args, 2).asNumber(0), arg(args, 3).asNumber(0));
            return List.of();
        }));
        api.set("set_glow", LuaValue.function((rt, args) -> {
            String group = arg(args, 0).asString();
            Session session = session(stock, server);
            if (arg(args, 1).isNil()) {
                session.glow.remove(group);
            } else {
                session.glow.put(group, arg(args, 1).isTruthy());
            }
            return List.of();
        }));
        api.set("set_alpha", LuaValue.function((rt, args) -> {
            String group = arg(args, 0).asString();
            Session session = session(stock, server);
            if (arg(args, 1).isNil()) {
                session.alpha.remove(group);
            } else {
                double alpha = arg(args, 1).asNumber(0);
                session.alpha.put(group, (float) Math.max(0, Math.min(1, alpha)));
            }
            return List.of();
        }));
        api.set("gif", LuaValue.function((rt, args) -> {
            session(stock, server).gifs.put(arg(args, 0).asString(), arg(args, 1).asString());
            return List.of();
        }));
        api.set("part", LuaValue.function((rt, args) -> List.of(partTable(stock, server, arg(args, 0).asString()))));
        api.set("slipping", LuaValue.function((rt, args) -> List.of(LuaValue.bool(stock instanceof cam72cam.immersiverailroading.entity.Locomotive loco && loco.isSlipping()))));
        api.set("sand", LuaValue.function((rt, args) -> List.of(LuaValue.bool(stock instanceof cam72cam.immersiverailroading.entity.Locomotive loco && loco.isSanding()))));
        api.set("set_sand", LuaValue.function((rt, args) -> {
            if (server && stock instanceof cam72cam.immersiverailroading.entity.Locomotive loco) {
                loco.setSanding(arg(args, 0).isNil() || arg(args, 0).isTruthy());
            }
            return List.of();
        }));
        api.set("cruise", LuaValue.function((rt, args) -> {
            if (stock instanceof cam72cam.immersiverailroading.entity.Locomotive loco && loco.isCruiseEngaged()) {
                return List.of(LuaValue.number(loco.getCruiseTarget()));
            }
            return List.of(LuaValue.NIL);
        }));
        api.set("set_cruise", LuaValue.function((rt, args) -> {
            if (server && stock instanceof cam72cam.immersiverailroading.entity.Locomotive loco) {
                if (arg(args, 0).isNil()) {
                    loco.disengageCruise();
                } else {
                    loco.setCruiseTarget((float) arg(args, 0).asNumber(0));
                }
            }
            return List.of();
        }));
        api.set("throttle", LuaValue.function((rt, args) -> List.of(LuaValue.number(readThrottle(stock)))));
        api.set("set_throttle", LuaValue.function((rt, args) -> {
            if (server) {
                writeThrottle(stock, (float) arg(args, 0).asNumber(0));
            }
            return List.of();
        }));
        api.set("reverser", LuaValue.function((rt, args) -> List.of(LuaValue.number(readReverser(stock)))));
        api.set("set_reverser", LuaValue.function((rt, args) -> {
            if (server && stock instanceof cam72cam.immersiverailroading.entity.Locomotive loco) {
                loco.setReverser((float) arg(args, 0).asNumber(0));
            }
            return List.of();
        }));
        api.set("train_brake", LuaValue.function((rt, args) -> List.of(LuaValue.number(readTrainBrake(stock)))));
        api.set("set_train_brake", LuaValue.function((rt, args) -> {
            if (server && stock instanceof cam72cam.immersiverailroading.entity.Locomotive loco) {
                loco.setTrainBrake((float) arg(args, 0).asNumber(0));
            }
            return List.of();
        }));
        api.set("independent_brake", LuaValue.function((rt, args) -> List.of(LuaValue.number(stock.getIndependentBrake()))));
        api.set("set_independent_brake", LuaValue.function((rt, args) -> {
            if (server) {
                stock.setIndependentBrake((float) arg(args, 0).asNumber(0));
            }
            return List.of();
        }));
        api.set("brake_pressure", LuaValue.function((rt, args) -> List.of(LuaValue.number(stock.getBrakePressure()))));
        api.set("sliding", LuaValue.function((rt, args) -> List.of(LuaValue.bool(stock.sliding))));
        api.set("derailed", LuaValue.function((rt, args) -> List.of(LuaValue.bool(stock.isDerailed()))));
        api.set("derail", LuaValue.function((rt, args) -> {
            if (server) {
                stock.derailConsist();
            }
            return List.of();
        }));
        api.set("explode", LuaValue.function((rt, args) -> {
            if (server) {
                stock.explodeConsist();
            }
            return List.of();
        }));
        api.set("horn", LuaValue.function((rt, args) -> {
            if (server && stock instanceof cam72cam.immersiverailroading.entity.Locomotive loco) {
                loco.setHorn((int) arg(args, 0).asNumber(10), 1f);
            }
            return List.of();
        }));
        api.set("bell", LuaValue.function((rt, args) -> {
            if (server && stock instanceof cam72cam.immersiverailroading.entity.Locomotive loco) {
                loco.setBell((int) arg(args, 0).asNumber(20));
            }
            return List.of();
        }));
        api.set("emergency", LuaValue.function((rt, args) -> {
            if (server && stock instanceof cam72cam.immersiverailroading.entity.Locomotive loco) {
                loco.setEmergency(arg(args, 0).isNil() || arg(args, 0).isTruthy());
            }
            return List.of();
        }));
        api.set("coupled", LuaValue.function((rt, args) -> {
            if (!(stock instanceof EntityCoupleableRollingStock coupled)) {
                return List.of(LuaValue.FALSE);
            }
            boolean front = "front".equalsIgnoreCase(arg(args, 0).asString());
            return List.of(LuaValue.bool(coupled.isCoupled(front ? CouplerType.FRONT : CouplerType.BACK)));
        }));
        api.set("log", LuaValue.function((rt, args) -> {
            ImmersiveRailroading.info("[%s] %s", name, arg(args, 0).asString());
            return List.of();
        }));
        return table;
    }

    private Session session(EntityMoveableRollingStock stock, boolean server) {
        Map<UUID, Session> sessions = server ? serverSessions : clientSessions;
        return sessions.computeIfAbsent(stock.getUUID(), id -> new Session());
    }

    private static LuaValue arg(List<LuaValue> args, int index) {
        int start = !args.isEmpty() && args.get(0).kind == LuaValue.Kind.TABLE ? 1 : 0;
        int actual = start + index;
        return actual < args.size() ? args.get(actual) : LuaValue.NIL;
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static final class Session {
        boolean loaded;
        final Map<String, Float> controls = new HashMap<>();
        final Map<String, Boolean> doors = new HashMap<>();
        final Map<String, Boolean> couplers = new HashMap<>();
        final Set<UUID> passengers = new HashSet<>();
        final Map<String, Boolean> visibility = new HashMap<>();
        final Map<String, Pose> poses = new HashMap<>();
        final Map<String, Boolean> glow = new HashMap<>();
        final Map<String, Float> alpha = new HashMap<>();
        final Map<String, String> gifs = new HashMap<>();

        Pose rotation(String group, double x, double y, double z) {
            Pose pose = poses.computeIfAbsent(group, key -> new Pose());
            pose.rx = x;
            pose.ry = y;
            pose.rz = z;
            pose.hasRotation = true;
            return pose;
        }

        Pose translation(String group, double x, double y, double z) {
            Pose pose = poses.computeIfAbsent(group, key -> new Pose());
            pose.tx = x;
            pose.ty = y;
            pose.tz = z;
            pose.hasTranslation = true;
            return pose;
        }

        Pose pivot(String group, double x, double y, double z) {
            Pose pose = poses.computeIfAbsent(group, key -> new Pose());
            pose.px = x;
            pose.py = y;
            pose.pz = z;
            pose.hasPivot = true;
            return pose;
        }
    }

    private static final class Pose {
        double rx;
        double ry;
        double rz;
        double tx;
        double ty;
        double tz;
        double sx = 1;
        double sy = 1;
        double sz = 1;
        double px;
        double py;
        double pz;
        boolean hasRotation;
        boolean hasTranslation;
        boolean hasScale;
        boolean hasPivot;

        boolean active() {
            return hasRotation || hasTranslation || hasScale || hasPivot;
        }
    }

    private LuaValue partTable(EntityMoveableRollingStock stock, boolean server, String group) throws LuaException {
        LuaValue table = LuaValue.table();
        var api = table.table(0);
        api.set("move", LuaValue.function((rt, args) -> {
            session(stock, server).translation(group, arg(args, 0).asNumber(0), arg(args, 1).asNumber(0), arg(args, 2).asNumber(0));
            return List.of();
        }));
        api.set("rotate", LuaValue.function((rt, args) -> {
            session(stock, server).rotation(group, arg(args, 0).asNumber(0), arg(args, 1).asNumber(0), arg(args, 2).asNumber(0));
            return List.of();
        }));
        api.set("scale", LuaValue.function((rt, args) -> {
            scale(session(stock, server), group, arg(args, 0), arg(args, 1), arg(args, 2));
            return List.of();
        }));
        api.set("pivot", LuaValue.function((rt, args) -> {
            session(stock, server).pivot(group, arg(args, 0).asNumber(0), arg(args, 1).asNumber(0), arg(args, 2).asNumber(0));
            return List.of();
        }));
        api.set("glow", LuaValue.function((rt, args) -> {
            session(stock, server).glow.put(group, arg(args, 0).isNil() || arg(args, 0).isTruthy());
            return List.of();
        }));
        api.set("alpha", LuaValue.function((rt, args) -> {
            session(stock, server).alpha.put(group, (float) Math.max(0, Math.min(1, arg(args, 0).asNumber(1))));
            return List.of();
        }));
        api.set("visible", LuaValue.function((rt, args) -> {
            session(stock, server).visibility.put(group, arg(args, 0).isNil() || arg(args, 0).isTruthy());
            return List.of();
        }));
        api.set("gif", LuaValue.function((rt, args) -> {
            session(stock, server).gifs.put(group, arg(args, 0).asString());
            return List.of();
        }));
        return table;
    }

    private static void scale(Session session, String group, LuaValue x, LuaValue y, LuaValue z) throws LuaException {
        double sx = x.isNil() ? 1 : x.asNumber(0);
        double sy = y.isNil() ? sx : y.asNumber(0);
        double sz = z.isNil() ? sx : z.asNumber(0);
        Pose pose = session.poses.computeIfAbsent(group, key -> new Pose());
        pose.sx = sx;
        pose.sy = sy;
        pose.sz = sz;
        pose.hasScale = true;
    }

    private static float readThrottle(EntityMoveableRollingStock stock) {
        if (stock instanceof cam72cam.immersiverailroading.entity.Locomotive loco) {
            return loco.getThrottle();
        }
        return 0;
    }

    private static void writeThrottle(EntityMoveableRollingStock stock, float value) {
        if (stock instanceof cam72cam.immersiverailroading.entity.Locomotive loco) {
            loco.setThrottle(value);
        }
    }

    private static float readReverser(EntityMoveableRollingStock stock) {
        if (stock instanceof cam72cam.immersiverailroading.entity.Locomotive loco) {
            return loco.getReverser();
        }
        return 0;
    }

    private static float readTrainBrake(EntityMoveableRollingStock stock) {
        if (stock instanceof cam72cam.immersiverailroading.entity.Locomotive loco) {
            return loco.getTrainBrake();
        }
        return 0;
    }
}
