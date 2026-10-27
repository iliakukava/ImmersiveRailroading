package cam72cam.immersiverailroading.registry;

import cam72cam.immersiverailroading.Config.ConfigPerformance;
import cam72cam.immersiverailroading.ImmersiveRailroading;
import cam72cam.mod.util.collection.BiMultiMap;
import cam72cam.immersiverailroading.util.CAML;
import cam72cam.immersiverailroading.util.DataBlock;
import cam72cam.immersiverailroading.library.Gauge;
import cam72cam.immersiverailroading.model.TrackModel;
import cam72cam.immersiverailroading.util.JSON;
import cam72cam.immersiverailroading.util.MathUtil;
import cam72cam.mod.gui.Progress;
import cam72cam.mod.resource.Identifier;
import org.apache.commons.lang3.tuple.Pair;

import java.io.IOException;
import java.io.InputStream;
import java.lang.management.ManagementFactory;
import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinWorkerThread;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class DefinitionManager {
    private static Map<String, EntityRollingStockDefinition> definitions;
    private static BiMultiMap<String, EntityRollingStockDefinition> stockTags;
    private static Map<String, TrackDefinition> tracks;
    private static PackLoadReport packReport = new PackLoadReport();
    private static final Map<String, StockLoader> stockLoaders;

    static {
        stockLoaders = new LinkedHashMap<>();
        stockLoaders.put("locomotives", (String defID, DataBlock data) -> {
            String era = data.getValue("era").asString();
            switch (era) {
                case "steam":
                    return new LocomotiveSteamDefinition(defID, data);
                case "diesel":
                    return new LocomotiveDieselDefinition(defID, data);
                default:
                    ImmersiveRailroading.warn("Invalid era %s in %s", era, defID);
                    return null;
            }
        });

        stockLoaders.put("tender", TenderDefinition::new);
        stockLoaders.put("passenger", CarPassengerDefinition::new);
        stockLoaders.put("freight", CarFreightDefinition::new);
        stockLoaders.put("tank", CarTankDefinition::new);
        stockLoaders.put("hand_car", HandCarDefinition::new);
    }

    private static void initGauges() throws IOException {
        for (Gauge value : new ArrayList<>(Gauge.values())) {
            Gauge.remove(value.value());
        }

        List<DataBlock> blocks = readPackIndex("rolling_stock/gauges.json", "rolling_stock/gauges.caml");

        List<Double> toRemove = new ArrayList<>();

        for (DataBlock gauges : blocks) {
            DataBlock register = gauges.getBlock("register");
            if (register != null) {
                register.getValueMap().forEach((key, value) -> Gauge.register(value.asDouble(), key));
            }
            List<DataBlock.Value> remove = gauges.getValues("remove");
            if (remove != null) {
                for (DataBlock.Value gauge : remove) {
                    toRemove.add(gauge.asDouble());
                }
            }
        }

        for (double gauge : toRemove) {
            Gauge.remove(gauge);
        }
    }

    public static void initDefinitions() {
        if (definitions != null) {
            for (EntityRollingStockDefinition def : definitions.values()) {
                if (def.stockModel.model != null) {
                    def.stockModel.model.free();
                }
            }
        }

        if (tracks != null) {
            for (TrackDefinition def : tracks.values()) {
                for (TrackModel trackModel : def.models) {
                    trackModel.model.free();
                }
            }
        }


        try {
            initGauges();
        } catch (Exception e) {
            throw new RuntimeException("Unable to load gauges, do you have a broken pack?", e);
        }

        // Parallel streams use numCPUs-1 threads for stream workloads.
        Runtime runtime = Runtime.getRuntime();
        int processors = runtime.availableProcessors();

        // Manual garbage collection so we get an accurate quantity of free memory.
        runtime.gc();

        long maxMemory = runtime.maxMemory();
        if (maxMemory == Long.MAX_VALUE) {
            maxMemory = runtime.totalMemory();
        }
        ImmersiveRailroading.info("Detected %sMB of memory free", maxMemory/1024/1024);
        try {
            com.sun.management.OperatingSystemMXBean os = (com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
            maxMemory = Math.min(os.getFreePhysicalMemorySize() + runtime.totalMemory(), maxMemory);
            ImmersiveRailroading.info("Adjusted to %sMB of memory free", maxMemory/1024/1024);
        } catch (UnsatisfiedLinkError | Exception ex) {
            ImmersiveRailroading.catching(ex);
        }

        long bytesPerThread = ConfigPerformance.megabytesReservedPerStockLoadingThread * 1024L * 1024L;
        int loadingThreads = MathUtil.clamp((int) (maxMemory / bytesPerThread), 1, processors);
        ImmersiveRailroading.info("Using %s threads to load Immersive Railroading (%sMB per thread)", loadingThreads, ConfigPerformance.megabytesReservedPerStockLoadingThread);
        ForkJoinPool stockLoadingPool = new ForkJoinPool(loadingThreads, pool -> {
            final ForkJoinWorkerThread worker = ForkJoinPool.defaultForkJoinWorkerThreadFactory.newThread(pool);
            worker.setName("ImmersiveRailroading-" + worker.getPoolIndex());
            return worker;
        }, null, false);
        try {
            stockLoadingPool.submit(() -> {
                try {
                    initModels();
                } catch (IOException e) {
                    throw new RuntimeException("Unable to load rolling stock, do you have a broken pack?", e);
                }
            }).get();
        } catch (InterruptedException | ExecutionException e) {
            // the pool broke in unexpected ways or was killed
            throw new RuntimeException(e);
        } finally {
            stockLoadingPool.shutdown();
        }
        try {
            initTracks();
        } catch (Exception e) {
            throw new RuntimeException("Unable to load tracks, do you have a broken pack?", e);
        }
        String report = packReport.format();
        if (packReport.hasFailures()) {
            ImmersiveRailroading.error(report);
        } else {
            ImmersiveRailroading.info(report);
        }
    }

    public static PackLoadReport getPackReport() {
        return packReport;
    }

    private static void initModels() throws IOException {
        packReport = new PackLoadReport();
        ImmersiveRailroading.info("Loading stock models.");

        Set<String> defTypes = stockLoaders.keySet();
        List<String> blacklist = getModelBlacklist(defTypes);

        LinkedHashMap<String, String> definitionIDMap = new LinkedHashMap<>();
        Map<String, DataBlock.Value> definitionIDPacks = new HashMap<>();

        List<DataBlock> blocks = readPackIndex("rolling_stock/stock.json", "rolling_stock/stock.caml");


        for (DataBlock stock : blocks) {
            for (String defType : defTypes) {
                List<DataBlock.Value> names = stock.getValues(defType);
                if (names != null) {
                    for (String defName : names.stream().map(DataBlock.Value::asString).collect(Collectors.toList())) {
                        if (blacklist.contains(defName)) {
                            packReport.skipped(defName, "blacklisted");
                            ImmersiveRailroading.info("Skipping blacklisted %s", defName);
                            continue;
                        }

                        String defID = String.format("rolling_stock/%s/%s.json", defType, defName);
                        if (definitionIDMap.containsKey(defID)) {
                            continue;
                        }

                        definitionIDMap.put(defID, defType);
                        DataBlock.Value pack = stock.getValue("pack");
                        if (pack.asString() != null) {
                            definitionIDPacks.put(defID, pack);
                        }
                    }
                }
            }
        }

        Progress.Bar bar = Progress.push("Loading Models", definitionIDMap.size());

        stockTags = new BiMultiMap<>();
        Map<String, EntityRollingStockDefinition> loaded = getStockLoadingStream(definitionIDMap.entrySet()).map(tuple -> {
            String defID = tuple.getKey();
            String defType = tuple.getValue();

            ImmersiveRailroading.debug("Loading stock " + defID);
            Identifier resource = new Identifier(ImmersiveRailroading.MODID, defID);

            try {
                if (!resource.canLoad()) {
                    resource = new Identifier(resource.getDomain(), resource.getPath().replace(".json", ".caml"));
                }
                if (!resource.canLoad()) {
                    String message = "file not found. Looked for " + defID + " and the .caml sibling.";
                    packReport.failed(defID, packName(definitionIDPacks.get(defID)), message, null);
                    ImmersiveRailroading.error("Unable to load stock %s: %s", defID, message);
                    return null;
                }

                DataBlock block = DataBlock.load(resource);

                if (definitionIDPacks.containsKey(defID) && block.getValue("pack").asString() == null) {
                    // This is kind of a nasty hack...
                    block.getValueMap().put("pack", definitionIDPacks.get(defID));
                }

                EntityRollingStockDefinition stockDefinition = stockLoaders.get(defType).apply(defID, block);

                maybeCollectDuringLoad();
                synchronized (stockTags) {
                    stockDefinition.tags.forEach(tag -> stockTags.put(tag, stockDefinition));
                }
                packReport.loaded(stockDefinition.defID, stockDefinition.packName);

                return Pair.of(stockDefinition.defID, stockDefinition);
            } catch (Exception e) {
                String pack = packName(definitionIDPacks.get(defID));
                String message = PackLoadReport.rootMessage(e);
                packReport.failed(defID, pack, message, e);
                ImmersiveRailroading.error("Error loading %s (%s) from pack %s: %s", defID, defType, pack, message);
                ImmersiveRailroading.catching(e);

                return null;
            } finally {
                synchronized (bar) {
                    bar.step(defID);
                }
            }
        }).filter(Objects::nonNull).collect(Collectors.toMap(Pair::getKey, Pair::getValue));

        definitions = new LinkedHashMap<>();
        definitionIDMap.keySet().stream().filter(loaded::containsKey).forEach(x -> definitions.put(x, loaded.get(x)));

        Progress.pop(bar);
    }

    private static List<String> getModelBlacklist(Set<String> defTypes) throws IOException {
        List<String> blacklist = new ArrayList<>();
        List<DataBlock> blocks = readPackIndex("rolling_stock/blacklist.json", "rolling_stock/blacklist.caml");

        for (DataBlock block : blocks) {
            for (String defType : defTypes) {
                List<DataBlock.Value> found = block.getValues(defType);
                if (found != null) {
                    blacklist.addAll(found.stream().map(DataBlock.Value::asString).collect(Collectors.toList()));
                }
            }
        }

        return blacklist;
    }

    private static void initTracks() throws IOException {
        tracks = new LinkedHashMap<>();

        ImmersiveRailroading.info("Loading tracks.");

        List<DataBlock> blocks = readPackIndex("track/track.json", "track/track.caml");


        for (DataBlock track : blocks) {
            List<String> types = track.getValues("types").stream().map(DataBlock.Value::asString).collect(Collectors.toList());
            Progress.Bar bar = Progress.push("Loading Tracks", types.size());

            for (String def : types) {
                bar.step(def);
                String trackID = String.format("immersiverailroading:track/%s.json", def);
                ImmersiveRailroading.debug("Loading Track %s", trackID);

                Identifier identifier = new Identifier(trackID);

                if (!identifier.canLoad()) {
                    identifier = new Identifier(identifier.getDomain(), identifier.getPath().replace(".json", ".caml"));
                }

                if (!identifier.canLoad()) {
                    String message = "file not found. Looked for " + trackID + " and the .caml sibling.";
                    packReport.failed(trackID, track.getValue("pack").asString(), message, null);
                    ImmersiveRailroading.error("Unable to load track '%s': %s", trackID, message);
                    continue;
                }

                String pack = track.getValue("pack").asString();
                try {
                    DataBlock block = DataBlock.load(identifier);

                    if (pack != null && block.getValue("pack").asString() == null) {
                        block.getValueMap().put("pack", track.getValue("pack"));
                    }
                    TrackDefinition definition = new TrackDefinition(trackID, block);
                    tracks.put(trackID, definition);
                    packReport.loaded(trackID, block.getValue("pack").asString(pack));
                } catch (Exception e) {
                    String message = PackLoadReport.rootMessage(e);
                    packReport.failed(trackID, pack, message, e);
                    ImmersiveRailroading.error("Unable to load track %s: %s", trackID, message);
                    ImmersiveRailroading.catching(e);
                }
            }

            Progress.pop(bar);
        }
    }

    /**
     * Get a stream for a collection that is used to load stocks in a singlethreaded or a multithreaded way.
     *
     * @param collection Collection of items.
     * @param <E> Type of item.
     * @return Singlethreaded or multithreaded stream.
     */
    private static <E> Stream<E> getStockLoadingStream(Collection<E> collection) {
        if (!ConfigPerformance.multithreadedStockLoading) {
            return collection.stream();
        }
        return collection.parallelStream();
    }

    public static EntityRollingStockDefinition getDefinition(String defID) {
        return definitions.get(defID);
    }

    public static Collection<EntityRollingStockDefinition> getDefinitions() {
        return definitions.values();
    }

    public static Set<String> getDefinitionNames() {
        return definitions.keySet();
    }

    public static Set<EntityRollingStockDefinition> getTaggedStocks(String tag) {
        return stockTags.getValues(tag);
    }

    public static Set<String> getStockTags(EntityRollingStockDefinition def) {
        return stockTags.getKeys(def);
    }

    public static boolean isTaggedWith(EntityRollingStockDefinition def, String tag) {
        return stockTags.containsEntry(tag, def);
    }

    public static List<TrackDefinition> getTracks() {
        return new ArrayList<>(tracks.values());
    }

    public static List<String> getTrackIDs() {
        return new ArrayList<>(tracks.keySet());
    }

    public static TrackModel getTrack(String track, double value) {
        return getTrack(track).getTrackForGauge(value);
    }

    public static TrackDefinition getTrack(String track) {
        TrackDefinition def = tracks.get(track);
        if (def == null) {
            def = tracks.values().stream().findFirst().get();
        }
        return def;
    }

    private static List<DataBlock> readPackIndex(String jsonPath, String camlPath) throws IOException {
        List<DataBlock> blocks = new ArrayList<>();
        blocks.addAll(readAll(jsonPath, false));
        blocks.addAll(readAll(camlPath, true));
        return blocks;
    }

    private static List<DataBlock> readAll(String path, boolean caml) throws IOException {
        Identifier identifier = new Identifier(ImmersiveRailroading.MODID, path);
        List<InputStream> inputs = identifier.getResourceStreamAll();
        List<DataBlock> blocks = new ArrayList<>();
        int index = 0;
        for (InputStream input : inputs) {
            index++;
            try {
                blocks.add(caml ? CAML.parse(input) : JSON.parse(input));
            } catch (Exception e) {
                throw new IOException("Failed to parse " + path + " (resource #" + index + "): " + PackLoadReport.rootMessage(e), e);
            }
        }
        return blocks;
    }

    private static String packName(DataBlock.Value pack) {
        if (pack == null) {
            return null;
        }
        return pack.asString();
    }

    private static volatile long lastLoadGc;

    private static void maybeCollectDuringLoad() {
        Runtime runtime = Runtime.getRuntime();
        if (runtime.freeMemory() >= runtime.maxMemory() * 0.25) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastLoadGc < 1000) {
            return;
        }
        lastLoadGc = now;
        ImmersiveRailroading.info("Free memory is low while loading packs; requesting a collection");
        System.gc();
    }

    @FunctionalInterface
    private interface StockLoader {
        EntityRollingStockDefinition apply(String defID, DataBlock data) throws Exception;
    }
}
