package org.wodichka.worldgen_editor.world;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wodichka.worldgen_editor.Worldgen_editor;
import org.wodichka.worldgen_editor.config.IslandConfig;
import org.wodichka.worldgen_editor.config.IslandConfigException;
import org.wodichka.worldgen_editor.config.IslandConfigLoader;
import org.wodichka.worldgen_editor.config.IslandTemperature;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

public final class IslandWorldState {
    private static final Logger LOGGER = LoggerFactory.getLogger(Worldgen_editor.MOD_ID);
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final AtomicReference<IslandConfig> CONFIG = new AtomicReference<>(IslandConfig.EMPTY);
    private static final AtomicReference<IslandMask> MASK = new AtomicReference<>(new IslandMask(IslandConfig.EMPTY, 0L));

    private static volatile long worldSeed;
    private static volatile boolean enabled;
    private static volatile boolean worldEnabled = true;
    private static volatile String worldPresetName;
    private static volatile Path worldStatePath;
    private static volatile Registry<Biome> biomeRegistry;
    private static volatile Holder<Biome> oceanBiome;
    private static volatile Holder<Biome> deepOceanBiome;
    private static volatile Holder<Biome> outerOceanBiome;
    private static volatile Holder<Biome> frozenOceanBiome;
    private static volatile Holder<Biome> deepFrozenOceanBiome;
    private static volatile Holder<Biome> coldOceanBiome;
    private static volatile Holder<Biome> deepColdOceanBiome;
    private static volatile Holder<Biome> lukewarmOceanBiome;
    private static volatile Holder<Biome> deepLukewarmOceanBiome;
    private static volatile Holder<Biome> warmOceanBiome;
    private static volatile Holder<Biome> beachBiome;
    private static volatile Holder<Biome> plainsBiome;
    private static volatile Holder<Biome> forestBiome;
    private static volatile Holder<Biome> meadowBiome;
    private static volatile Holder<Biome> snowyPlainsBiome;
    private static volatile Holder<Biome> taigaBiome;
    private static volatile Holder<Biome> savannaBiome;
    private static volatile Holder<Biome> jungleBiome;
    private static volatile Holder<Biome> desertBiome;

    private static volatile Holder<Biome> coldFallback1;
    private static volatile Holder<Biome> coldFallback2;
    private static volatile Holder<Biome> coldFallback3;

    private static volatile Holder<Biome> temperateFallback1;
    private static volatile Holder<Biome> temperateFallback2;
    private static volatile Holder<Biome> temperateFallback3;

    private static volatile Holder<Biome> warmFallback1;
    private static volatile Holder<Biome> warmFallback2;
    private static volatile Holder<Biome> warmFallback3;

    // Deeper and Darker sub-biomes, placed directly by WGE using the same temperature/humidity
    // windows as the mod's own data/ddoverworld/biolith/biome_placement.json. This does not
    // depend on Biolith's own placement mixin reaching WGE's delegate biome source, because it
    // doesn't reliably: WGE's IslandBiomeSource sits between the dimension and the vanilla
    // multi_noise delegate, and Biolith's LevelStem-level substitution never sees past it, so
    // Deep Dark reaches WGE unmodified and Deeper and Darker's biomes never appear otherwise.
    private static volatile Holder<Biome> deeperDarkerDeeplands;
    private static volatile Holder<Biome> deeperDarkerEchoingForest;
    private static volatile Holder<Biome> deeperDarkerBloomingCaverns;
    private static volatile Holder<Biome> deeperDarkerOvercastColumns;
    private static final List<DeepDarkSubBiome> DEEP_DARK_SUB_BIOMES = List.of(
            new DeepDarkSubBiome(() -> deeperDarkerDeeplands, -0.65D, -0.35D, -0.65D, -0.35D),
            new DeepDarkSubBiome(() -> deeperDarkerEchoingForest, 0.46D, 0.76D, 0.25D, 0.55D),
            new DeepDarkSubBiome(() -> deeperDarkerBloomingCaverns, -0.78D, -0.48D, 0.38D, 0.68D),
            new DeepDarkSubBiome(() -> deeperDarkerOvercastColumns, 0.55D, 0.85D, -0.45D, -0.15D)
    );
    private static final Set<String> EXCLUSION_WARNINGS = ConcurrentHashMap.newKeySet();
    private static final List<TagKey<Biome>> OCEAN_TAGS = List.of(tag("minecraft:is_ocean"), tag("c:is_ocean"), tag("forge:is_ocean"));
    private static final List<TagKey<Biome>> COLD_TAGS = List.of(tag("c:is_cold"), tag("forge:is_cold"), tag("c:cold"), tag("forge:cold"));
    private static final List<TagKey<Biome>> HOT_TAGS = List.of(tag("c:is_hot"), tag("forge:is_hot"), tag("c:is_warm"), tag("forge:is_warm"), tag("c:hot"), tag("forge:hot"));
    private static final List<TagKey<Biome>> SNOWY_TAGS = List.of(tag("c:is_snowy"), tag("forge:is_snowy"), tag("c:snowy"), tag("forge:snowy"));
    private static final List<TagKey<Biome>> TEMPERATE_TAGS = List.of(tag("c:is_temperate"), tag("forge:is_temperate"), tag("c:temperate"), tag("forge:temperate"));
    private static final List<TagKey<Biome>> CAVE_TAGS = List.of(tag("c:cave"), tag("forge:cave"), tag("c:is_cave"), tag("forge:is_cave"), tag("c:underground"), tag("forge:underground"));
    private static final Set<String> CAVE_BIOME_NAMESPACES = Set.of("deeperdarker");

    private IslandWorldState() {
    }

    public static void loadGlobalConfig() {
        loadGlobalConfig("init", false);
    }

    private static void loadGlobalConfig(String phase, boolean keepPreviousOnFailure) {
        try {
            IslandConfig config = IslandConfigLoader.loadOrCreate(worldPresetName);
            CONFIG.set(config);
            MASK.set(new IslandMask(config, worldSeed));
            enabled = effectiveEnabled();
            LOGGER.info("Loaded {} island entries from {} during {}", config.entries().size(), IslandConfigLoader.activeConfigPath(worldPresetName), phase);
        } catch (IslandConfigException exception) {
            if (keepPreviousOnFailure) {
                LOGGER.error("Keeping previous island config because {} load failed: {}", phase, exception.getMessage());
                return;
            }

            LOGGER.error("Failed to load island config during {}: {}", phase, exception.getMessage());
            CONFIG.set(IslandConfig.EMPTY);
            MASK.set(new IslandMask(IslandConfig.EMPTY, worldSeed));
            enabled = false;
        }
    }

    public static void loadForServer(MinecraftServer server) {
        worldSeed = server.overworld().getSeed();
        String detectedPreset = detectWorldPreset(server);
        if (detectedPreset != null) {
            worldPresetName = detectedPreset;
        }
        loadGlobalConfig("world start", false);
        worldStatePath = server.getWorldPath(LevelResource.ROOT).resolve(Worldgen_editor.MOD_ID).resolve("worldgen_editor.json");
        worldEnabled = loadOrCreateWorldState(worldStatePath);
        enabled = effectiveEnabled();
        Registry<Biome> biomes = server.registryAccess().registryOrThrow(Registries.BIOME);
        biomeRegistry = biomes;
        coldFallback1 = resolveBiome(biomes, "terralith:amethyst_canyon");
        coldFallback2 = resolveBiome(biomes, "terralith:amethyst_rainforest");
        coldFallback3 = resolveBiome(biomes, "terralith:mirage_isles");

        temperateFallback1 = resolveBiome(biomes, "terralith:moonlight_grove");
        temperateFallback2 = resolveBiome(biomes, "terralith:moonlight_valley");
        temperateFallback3 = resolveBiome(biomes, "terralith:mirage_isles");

        warmFallback1 = resolveBiome(biomes, "terralith:ashen_savanna");
        warmFallback2 = resolveBiome(biomes, "terralith:caldera");
        warmFallback3 = resolveBiome(biomes, "terralith:gravel_desert");
        deeperDarkerDeeplands = resolveBiome(biomes, "deeperdarker:deeplands");
        deeperDarkerEchoingForest = resolveBiome(biomes, "deeperdarker:echoing_forest");
        deeperDarkerBloomingCaverns = resolveBiome(biomes, "deeperdarker:blooming_caverns");
        deeperDarkerOvercastColumns = resolveBiome(biomes, "deeperdarker:overcast_columns");
        oceanBiome = biomes.getHolderOrThrow(Biomes.OCEAN);
        deepOceanBiome = biomes.getHolderOrThrow(Biomes.DEEP_OCEAN);
        frozenOceanBiome = biomes.getHolderOrThrow(Biomes.FROZEN_OCEAN);
        deepFrozenOceanBiome = biomes.getHolderOrThrow(Biomes.DEEP_FROZEN_OCEAN);
        coldOceanBiome = biomes.getHolderOrThrow(Biomes.COLD_OCEAN);
        deepColdOceanBiome = biomes.getHolderOrThrow(Biomes.DEEP_COLD_OCEAN);
        lukewarmOceanBiome = biomes.getHolderOrThrow(Biomes.LUKEWARM_OCEAN);
        deepLukewarmOceanBiome = biomes.getHolderOrThrow(Biomes.DEEP_LUKEWARM_OCEAN);
        warmOceanBiome = biomes.getHolderOrThrow(Biomes.WARM_OCEAN);
        beachBiome = biomes.getHolderOrThrow(Biomes.BEACH);
        plainsBiome = biomes.getHolderOrThrow(Biomes.PLAINS);
        forestBiome = biomes.getHolderOrThrow(Biomes.FOREST);
        meadowBiome = biomes.getHolderOrThrow(Biomes.MEADOW);
        snowyPlainsBiome = biomes.getHolderOrThrow(Biomes.SNOWY_PLAINS);
        taigaBiome = biomes.getHolderOrThrow(Biomes.TAIGA);
        savannaBiome = biomes.getHolderOrThrow(Biomes.SAVANNA);
        jungleBiome = biomes.getHolderOrThrow(Biomes.JUNGLE);
        desertBiome = biomes.getHolderOrThrow(Biomes.DESERT);
        refreshOuterOceanBiome();
        EXCLUSION_WARNINGS.clear();
        MASK.set(new IslandMask(CONFIG.get(), worldSeed));
        LOGGER.info("WorldGen Editor is {} for this world", enabled ? "enabled" : "disabled");
    }

    private static String detectWorldPreset(MinecraftServer server) {
        try {
            BiomeSource biomeSource = server.overworld().getChunkSource().getGenerator().getBiomeSource();
            if (biomeSource instanceof IslandBiomeSource islandBiomeSource) {
                return islandBiomeSource.presetName();
            }
        } catch (RuntimeException exception) {
            LOGGER.warn("Could not detect WorldGen Editor world preset from the loaded Overworld", exception);
        }
        return null;
    }

    public static boolean reloadConfig() {
        try {
            IslandConfig config = IslandConfigLoader.loadOrCreate(worldPresetName);
            CONFIG.set(config);
            MASK.set(new IslandMask(config, worldSeed));
            refreshOuterOceanBiome();
            enabled = effectiveEnabled();
            LOGGER.info("Loaded {} island entries from {} during reload", config.entries().size(), IslandConfigLoader.activeConfigPath(worldPresetName));
            return true;
        } catch (IslandConfigException exception) {
            LOGGER.error("Keeping previous island config because reload failed: {}", exception.getMessage());
            return false;
        }
    }

    public static void setWorldPreset(String presetName) {
        if (presetName == null || presetName.isBlank()) {
            return;
        }
        if (presetName.equals(worldPresetName)) {
            return;
        }

        worldPresetName = presetName;
        loadGlobalConfig("world preset '" + presetName + "'", true);
    }

    public static boolean hasWorldPreset() {
        return worldPresetName != null && !worldPresetName.isBlank();
    }

    public static String worldPresetName() {
        return worldPresetName;
    }

    public static String activePresetName() {
        return IslandConfigLoader.activePresetName(worldPresetName);
    }

    public static Path activeConfigPath() {
        return IslandConfigLoader.activeConfigPath(worldPresetName);
    }

    public static boolean setEnabled(boolean value) {
        worldEnabled = value;
        enabled = effectiveEnabled();
        return saveWorldState(value);
    }

    public static boolean isEnabled() {
        return enabled;
    }

    public static boolean isGlobalEnabled() {
        return CONFIG.get().enabled();
    }

    public static boolean isWorldEnabled() {
        return worldEnabled;
    }

    public static int islandCount() {
        return CONFIG.get().entries().size();
    }

    public static Path worldStatePath() {
        return worldStatePath;
    }

    public static Holder<Biome> oceanBiome(double islandMask) {
        return oceanBiome(islandMask, null, null, 0, 0);
    }

    public static Holder<Biome> oceanBiome(double islandMask, IslandMask.SourceInfo source, Holder<Biome> delegate, int blockX, int blockZ) {
        boolean deep = islandMask < IslandTerrainHooks.DEEP_OCEAN_MASK;
        ClimateBand climate = climateBand(source, delegate, blockX, blockZ);
        List<Holder<Biome>> candidates = switch (climate) {
            case COLD -> nonNullBiomes(frozenOceanBiome, deepFrozenOceanBiome, coldOceanBiome, deepColdOceanBiome);
            case WARM -> nonNullBiomes(warmOceanBiome, lukewarmOceanBiome, deepLukewarmOceanBiome);
            case TEMPERATE -> deep
                    ? nonNullBiomes(deepOceanBiome, oceanBiome)
                    : nonNullBiomes(oceanBiome, deepOceanBiome);
        };
        Holder<Biome> fallback = switch (climate) {
            case COLD -> firstNonNull(frozenOceanBiome, coldOceanBiome, oceanBiome);
            case WARM -> firstNonNull(warmOceanBiome, lukewarmOceanBiome, oceanBiome, deepOceanBiome);
            case TEMPERATE -> oceanBiome;
        };
        if (climate == ClimateBand.COLD || climate == ClimateBand.WARM) {
            Holder<Biome> picked = pickAllowed(candidates, source, blockX, blockZ);
            if (picked != null) {
                return picked;
            }
            warnAllExcluded(source);
            return firstNonNull(fallback, oceanBiome, deepOceanBiome);
        }
        return firstAllowed(candidates, source, fallback);
    }

    public static Holder<Biome> outerOceanBiome() {
        return firstNonNull(outerOceanBiome, deepOceanBiome, oceanBiome);
    }

    public static List<Holder<Biome>> oceanBiomes() {
        return nonNullBiomes(
                oceanBiome,
                deepOceanBiome,
                outerOceanBiome,
                frozenOceanBiome,
                deepFrozenOceanBiome,
                coldOceanBiome,
                deepColdOceanBiome,
                lukewarmOceanBiome,
                deepLukewarmOceanBiome,
                warmOceanBiome
        );
    }

    public static List<Holder<Biome>> fallbackLandBiomes() {
        return nonNullBiomes(
                coldFallback1,
                coldFallback2,
                coldFallback3,
                temperateFallback1,
                temperateFallback2,
                temperateFallback3,
                warmFallback1,
                warmFallback2,
                warmFallback3
        );
    }

    public static List<Holder<Biome>> deepDarkSubBiomes() {
        return nonNullBiomes(
                deeperDarkerDeeplands,
                deeperDarkerEchoingForest,
                deeperDarkerBloomingCaverns,
                deeperDarkerOvercastColumns
        );
    }

    /**
     * If the delegate resolved plain Deep Dark at this point, check it against Deeper and
     * Darker's own temperature/humidity windows (mirrored from its biome_placement.json) and
     * swap in the matching sub-biome directly. Points outside all four windows stay Deep Dark,
     * same as Biolith's own placement would leave them. No-ops if D&D isn't installed (the
     * resolved holders are all null) or the delegate isn't Deep Dark in the first place.
     */
    public static Holder<Biome> substituteDeepDarkSubBiome(Holder<Biome> delegate, double temperature, double humidity) {
        if (delegate == null || !delegate.is(Biomes.DEEP_DARK)) {
            return delegate;
        }

        for (DeepDarkSubBiome subBiome : DEEP_DARK_SUB_BIOMES) {
            if (subBiome.matches(temperature, humidity)) {
                Holder<Biome> resolved = subBiome.biome().get();
                if (resolved != null) {
                    return resolved;
                }
            }
        }
        return delegate;
    }

    private static Holder<Biome> resolveBiome(Registry<Biome> registry, String id) {
        try {
            ResourceKey<Biome> key = ResourceKey.create(
                    Registries.BIOME,
                    ResourceLocation.parse(id)
            );
            return registry.getHolder(key).orElse(null);
        } catch (IllegalArgumentException exception) {
            LOGGER.warn("Invalid biome '{}'", id);
            return null;
        }
    }

    public static Holder<Biome> beachBiome() {
        return beachBiome;
    }

    public static Holder<Biome> landBiome(double islandMask, int blockX, int blockZ) {
        Holder<Biome> plains = plainsBiome;
        Holder<Biome> forest = forestBiome;
        Holder<Biome> meadow = meadowBiome;
        if (plains == null || forest == null || meadow == null) {
            return null;
        }

        double inner = smoothstep(IslandTerrainHooks.LAND_MASK, 0.96D, islandMask);
        long mixed = mix(worldSeed, blockX * 341873128712L);
        mixed = mix(mixed, blockZ * 132897987541L);
        double choice = ((mixed >>> 11) * 0x1.0p-53D);

        if (inner > 0.70D && choice < 0.28D) {
            return forest;
        }
        if (inner > 0.56D && choice > 0.78D) {
            return meadow;
        }
        return plains;
    }

    public static Holder<Biome> landBiome(IslandMask.SourceInfo source, Holder<Biome> delegate, int blockX, int blockZ) {
        return landBiome(1.0D, source, delegate, blockX, blockZ);
    }

    public static Holder<Biome> landBiome(double islandMask, IslandMask.SourceInfo source, Holder<Biome> delegate, int blockX, int blockZ) {
        if (source == null) {
            Holder<Biome> coast = replaceOceanDelegateOnLand(islandMask, delegate);
            if (coast != null) {
                return coast;
            }
            return delegate;
        }

        ClimateBand configured = configuredClimateBand(source);
        Holder<Biome> coast = replaceOceanDelegateOnLand(islandMask, delegate);
        if (coast != null && !isExcluded(coast, source)) {
            return coast;
        }

        if (isLikelyCaveBiome(delegate) && !isExcluded(delegate, source)) {
            return delegate;
        }

        if (configured == null) {
            if (!isExcluded(delegate, source)) {
                return delegate;
            }
            configured = climateBand(null, delegate, blockX, blockZ);
        } else if (delegate != null && !isExcluded(delegate, source) && !isOceanBiome(delegate) && matchesLandClimate(delegate, configured)) {
            return delegate;
        }

        List<Holder<Biome>> candidates = switch (configured) {
            case COLD -> nonNullBiomes(
                    coldFallback1,
                    coldFallback2,
                    coldFallback3
            );
            case WARM -> nonNullBiomes(
                    warmFallback1,
                    warmFallback2,
                    warmFallback3
            );
            case TEMPERATE -> nonNullBiomes(
                    temperateFallback1,
                    temperateFallback2,
                    temperateFallback3
            );
        };
        Holder<Biome> fallback = pickAllowed(candidates, source, blockX, blockZ);
        if (fallback != null) {
            return fallback;
        }

        warnAllExcluded(source);
        return switch (configured) {
            case COLD -> firstNonNull(
                    coldFallback1,
                    coldFallback2,
                    coldFallback3,
                    plainsBiome,
                    delegate
            );
            case WARM -> firstNonNull(
                    warmFallback1,
                    warmFallback2,
                    warmFallback3,
                    plainsBiome,
                    delegate
            );
            case TEMPERATE -> firstNonNull(
                    temperateFallback1,
                    temperateFallback2,
                    temperateFallback3,
                    plainsBiome,
                    delegate
            );
        };
    }

    private static Holder<Biome> replaceOceanDelegateOnLand(double islandMask, Holder<Biome> delegate) {
        if (!isOceanBiome(delegate)) {
            return null;
        }
        if (islandMask < IslandTerrainHooks.LAND_MASK) {
            return firstNonNull(beachBiome, plainsBiome, forestBiome, meadowBiome);
        }
        return firstNonNull(plainsBiome, forestBiome, meadowBiome);
    }

    public static boolean isOceanBiome(Holder<Biome> biome) {
        return biome != null && (hasAnyTag(biome, OCEAN_TAGS)
                || biome.is(Biomes.OCEAN)
                || biome.is(Biomes.DEEP_OCEAN)
                || biome.is(Biomes.COLD_OCEAN)
                || biome.is(Biomes.DEEP_COLD_OCEAN)
                || biome.is(Biomes.FROZEN_OCEAN)
                || biome.is(Biomes.DEEP_FROZEN_OCEAN)
                || biome.is(Biomes.LUKEWARM_OCEAN)
                || biome.is(Biomes.DEEP_LUKEWARM_OCEAN)
                || biome.is(Biomes.WARM_OCEAN));
    }

    public static IslandMask mask() {
        if (!enabled) {
            return new IslandMask(IslandConfig.EMPTY, worldSeed);
        }
        return MASK.get();
    }

    public static boolean hasConfig() {
        return !CONFIG.get().entries().isEmpty();
    }

    public static long worldSeed() {
        return worldSeed;
    }

    private static Holder<Biome> pickAllowed(List<Holder<Biome>> candidates, IslandMask.SourceInfo source, int blockX, int blockZ) {
        List<Holder<Biome>> allowed = new ArrayList<>();
        for (Holder<Biome> candidate : candidates) {
            if (candidate != null && !isExcluded(candidate, source)) {
                allowed.add(candidate);
            }
        }
        if (allowed.isEmpty()) {
            return null;
        }

        int patchSize = biomePatchSize(source);
        long seed = source == null ? worldSeed : source.climateSeed();
        int cellX = Math.floorDiv(blockX, patchSize);
        int cellZ = Math.floorDiv(blockZ, patchSize);
        double localX = (double) Math.floorMod(blockX, patchSize) / patchSize;
        double localZ = (double) Math.floorMod(blockZ, patchSize) / patchSize;
        double bestDistance = Double.POSITIVE_INFINITY;
        int bestIndex = 0;

        for (int dz = -1; dz <= 1; dz++) {
            for (int dx = -1; dx <= 1; dx++) {
                int candidateCellX = cellX + dx;
                int candidateCellZ = cellZ + dz;
                long mixed = mix(seed, candidateCellX * 341873128712L);
                mixed = mix(mixed, candidateCellZ * 132897987541L);
                double jitterX = randomUnit(mixed, 1L);
                double jitterZ = randomUnit(mixed, 2L);
                double siteX = dx + jitterX;
                double siteZ = dz + jitterZ;
                double distanceX = localX - siteX;
                double distanceZ = localZ - siteZ;
                double distance = distanceX * distanceX + distanceZ * distanceZ;
                if (distance < bestDistance) {
                    bestDistance = distance;
                    bestIndex = Math.floorMod((int) (mix(mixed, 3L) >>> 32), allowed.size());
                }
            }
        }

        return allowed.get(bestIndex);
    }

    private static Holder<Biome> firstAllowed(List<Holder<Biome>> candidates, IslandMask.SourceInfo source, Holder<Biome> fallback) {
        for (Holder<Biome> candidate : candidates) {
            if (candidate != null && !isExcluded(candidate, source)) {
                return candidate;
            }
        }
        warnAllExcluded(source);
        return firstNonNull(fallback, oceanBiome, deepOceanBiome);
    }

    private static boolean isExcluded(Holder<Biome> biome, IslandMask.SourceInfo source) {
        if (biome == null || source == null || source.excludedBiomes().isEmpty()) {
            return false;
        }

        for (String selector : source.excludedBiomes()) {
            if (selector.startsWith("#")) {
                TagKey<Biome> tag = TagKey.create(Registries.BIOME, ResourceLocation.parse(selector.substring(1)));
                if (biome.is(tag)) {
                    return true;
                }
            } else {
                ResourceKey<Biome> key = ResourceKey.create(Registries.BIOME, ResourceLocation.parse(selector));
                if (biome.is(key)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static ClimateBand climateBand(IslandMask.SourceInfo source, Holder<Biome> delegate, int blockX, int blockZ) {
        ClimateBand configured = configuredClimateBand(source);
        if (configured != null) {
            return configured;
        }

        if (delegate != null) {
            if (isColdBiome(delegate)) {
                return ClimateBand.COLD;
            }
            if (isWarmBiome(delegate)) {
                return ClimateBand.WARM;
            }
            if (isTemperateBiome(delegate)) {
                return ClimateBand.TEMPERATE;
            }
        }

        return ClimateBand.TEMPERATE;
    }

    private static ClimateBand configuredClimateBand(IslandMask.SourceInfo source) {
        if (source == null || source.temperature() == null || source.temperature() == IslandTemperature.STANDARD) {
            return null;
        }
        return switch (source.temperature()) {
            case COLD -> ClimateBand.COLD;
            case TEMPERATE -> ClimateBand.TEMPERATE;
            case WARM -> ClimateBand.WARM;
            case STANDARD -> null;
        };
    }

    private static int biomePatchSize(IslandMask.SourceInfo source) {
        if (source == null || source.biomePatchSize() <= 0) {
            return 512;
        }
        return source.biomePatchSize();
    }

    private static void refreshOuterOceanBiome() {
        Registry<Biome> registry = biomeRegistry;
        Holder<Biome> fallback = firstNonNull(deepOceanBiome, oceanBiome);
        if (registry == null) {
            outerOceanBiome = fallback;
            return;
        }

        String configured = CONFIG.get().outerOcean();
        Holder<Biome> resolved = null;
        try {
            ResourceKey<Biome> key = ResourceKey.create(Registries.BIOME, ResourceLocation.parse(configured));
            resolved = registry.getHolder(key).orElse(null);
        } catch (IllegalArgumentException exception) {
            LOGGER.warn("Invalid outer_ocean '{}'; using minecraft:deep_ocean", configured);
        }

        if (resolved != null && isOceanBiome(resolved)) {
            outerOceanBiome = resolved;
            return;
        }

        if (resolved == null) {
            LOGGER.warn("outer_ocean '{}' is not registered; using minecraft:deep_ocean", configured);
        } else {
            LOGGER.warn("outer_ocean '{}' is not an ocean-like biome; using minecraft:deep_ocean", configured);
        }
        outerOceanBiome = fallback;
    }

    private static boolean matchesLandClimate(Holder<Biome> biome, ClimateBand climate) {
        return switch (climate) {
            case COLD -> isColdBiome(biome);
            case WARM -> isWarmBiome(biome) && !isColdBiome(biome);
            case TEMPERATE -> isTemperateBiome(biome) && !isColdBiome(biome) && !isWarmBiome(biome);
        };
    }

    private static boolean isColdBiome(Holder<Biome> biome) {
        return biome != null && (hasAnyTag(biome, COLD_TAGS)
                || hasAnyTag(biome, SNOWY_TAGS)
                || biome.is(Biomes.SNOWY_PLAINS)
                || biome.is(Biomes.ICE_SPIKES)
                || biome.is(Biomes.SNOWY_TAIGA)
                || biome.is(Biomes.GROVE)
                || biome.is(Biomes.SNOWY_SLOPES)
                || biome.is(Biomes.JAGGED_PEAKS)
                || biome.is(Biomes.FROZEN_PEAKS)
                || biome.is(Biomes.FROZEN_RIVER)
                || biome.is(Biomes.FROZEN_OCEAN)
                || biome.is(Biomes.DEEP_FROZEN_OCEAN)
                || biome.is(Biomes.COLD_OCEAN)
                || biome.is(Biomes.DEEP_COLD_OCEAN));
    }

    private static boolean isWarmBiome(Holder<Biome> biome) {
        return biome != null && (hasAnyTag(biome, HOT_TAGS)
                || biome.is(Biomes.DESERT)
                || biome.is(Biomes.SAVANNA)
                || biome.is(Biomes.SAVANNA_PLATEAU)
                || biome.is(Biomes.WINDSWEPT_SAVANNA)
                || biome.is(Biomes.JUNGLE)
                || biome.is(Biomes.SPARSE_JUNGLE)
                || biome.is(Biomes.BAMBOO_JUNGLE)
                || biome.is(Biomes.BADLANDS)
                || biome.is(Biomes.ERODED_BADLANDS)
                || biome.is(Biomes.WOODED_BADLANDS)
                || biome.is(Biomes.WARM_OCEAN)
                || biome.is(Biomes.LUKEWARM_OCEAN)
                || biome.is(Biomes.DEEP_LUKEWARM_OCEAN));
    }

    private static boolean isTemperateBiome(Holder<Biome> biome) {
        return biome != null && (hasAnyTag(biome, TEMPERATE_TAGS)
                || biome.is(Biomes.PLAINS)
                || biome.is(Biomes.SUNFLOWER_PLAINS)
                || biome.is(Biomes.FOREST)
                || biome.is(Biomes.FLOWER_FOREST)
                || biome.is(Biomes.BIRCH_FOREST)
                || biome.is(Biomes.OLD_GROWTH_BIRCH_FOREST)
                || biome.is(Biomes.DARK_FOREST)
                || biome.is(Biomes.MEADOW)
                || biome.is(Biomes.TAIGA)
                || biome.is(Biomes.OLD_GROWTH_PINE_TAIGA)
                || biome.is(Biomes.OLD_GROWTH_SPRUCE_TAIGA)
                || biome.is(Biomes.SWAMP)
                || biome.is(Biomes.MANGROVE_SWAMP)
                || biome.is(Biomes.RIVER)
                || biome.is(Biomes.OCEAN)
                || biome.is(Biomes.DEEP_OCEAN));
    }

    /**
     * Deep-underground biomes (vanilla Deep Dark, Dripstone/Lush Caves, and mods like
     * Deeper and Darker that add their own deep-underground biomes) have no meaningful
     * surface climate. They must never be swapped out by the COLD/WARM/TEMPERATE fallback
     * logic just because they don't carry a climate tag, or they get silently replaced by
     * an island's configured-temperature fallback biome instead of generating at all.
     */
    private static boolean isLikelyCaveBiome(Holder<Biome> biome) {
        if (biome == null) {
            return false;
        }
        if (hasAnyTag(biome, CAVE_TAGS)) {
            return true;
        }
        if (biome.is(Biomes.DEEP_DARK) || biome.is(Biomes.DRIPSTONE_CAVES) || biome.is(Biomes.LUSH_CAVES)) {
            return true;
        }
        return biome.unwrapKey()
                .map(key -> CAVE_BIOME_NAMESPACES.contains(key.location().getNamespace()))
                .orElse(false);
    }

    private static boolean hasAnyTag(Holder<Biome> biome, List<TagKey<Biome>> tags) {
        for (TagKey<Biome> tag : tags) {
            if (biome.is(tag)) {
                return true;
            }
        }
        return false;
    }

    private static TagKey<Biome> tag(String id) {
        return TagKey.create(Registries.BIOME, ResourceLocation.parse(id));
    }

    @SafeVarargs
    private static List<Holder<Biome>> nonNullBiomes(Holder<Biome>... biomes) {
        List<Holder<Biome>> holders = new ArrayList<>();
        for (Holder<Biome> biome : biomes) {
            if (biome != null) {
                holders.add(biome);
            }
        }
        return List.copyOf(holders);
    }

    @SafeVarargs
    private static Holder<Biome> firstNonNull(Holder<Biome>... biomes) {
        for (Holder<Biome> biome : biomes) {
            if (biome != null) {
                return biome;
            }
        }
        return null;
    }

    private static void warnAllExcluded(IslandMask.SourceInfo source) {
        if (source != null && EXCLUSION_WARNINGS.add(source.name())) {
            LOGGER.warn("All preferred biome fallbacks were excluded for '{}'; using safe vanilla fallback", source.name());
        }
    }

    private enum ClimateBand {
        COLD,
        TEMPERATE,
        WARM
    }

    private record DeepDarkSubBiome(Supplier<Holder<Biome>> biome, double minTemperature, double maxTemperature,
                                     double minHumidity, double maxHumidity) {
        boolean matches(double temperature, double humidity) {
            return temperature >= minTemperature && temperature <= maxTemperature
                    && humidity >= minHumidity && humidity <= maxHumidity;
        }
    }

    private static boolean loadOrCreateWorldState(Path path) {
        try {
            if (!Files.exists(path)) {
                Files.createDirectories(path.getParent());
                writeWorldState(path, true);
                return true;
            }

            try (Reader reader = Files.newBufferedReader(path)) {
                JsonObject object = GSON.fromJson(reader, JsonObject.class);
                if (object == null || !object.has("enabled") || !object.get("enabled").isJsonPrimitive()) {
                    writeWorldState(path, true);
                    return true;
                }
                return object.get("enabled").getAsBoolean();
            }
        } catch (IOException | JsonParseException | IllegalStateException exception) {
            LOGGER.error("Could not load world state from {}. Disabling generation for this world.", path, exception);
            return false;
        }
    }

    private static boolean saveWorldState(boolean value) {
        Path path = worldStatePath;
        if (path == null) {
            LOGGER.error("Cannot save worldgen state before a server world is loaded");
            return false;
        }

        try {
            Files.createDirectories(path.getParent());
            writeWorldState(path, value);
            return true;
        } catch (IOException exception) {
            LOGGER.error("Could not save world state to {}", path, exception);
            return false;
        }
    }

    private static void writeWorldState(Path path, boolean value) throws IOException {
        JsonObject object = new JsonObject();
        object.addProperty("enabled", value);
        try (Writer writer = Files.newBufferedWriter(path)) {
            GSON.toJson(object, writer);
        }
    }

    private static boolean effectiveEnabled() {
        return CONFIG.get().enabled() && worldEnabled;
    }

    private static double smoothstep(double min, double max, double value) {
        double x = (value - min) / (max - min);
        if (x < 0.0D) {
            x = 0.0D;
        } else if (x > 1.0D) {
            x = 1.0D;
        }
        return x * x * (3.0D - 2.0D * x);
    }

    private static long mix(long seed, long value) {
        long mixed = seed ^ value;
        mixed ^= mixed >>> 33;
        mixed *= 0xff51afd7ed558ccdL;
        mixed ^= mixed >>> 33;
        mixed *= 0xc4ceb9fe1a85ec53L;
        mixed ^= mixed >>> 33;
        return mixed;
    }

    private static double randomUnit(long seed, long salt) {
        long mixed = mix(seed, salt * 0x9e3779b97f4a7c15L);
        return (mixed >>> 11) * 0x1.0p-53D;
    }
}