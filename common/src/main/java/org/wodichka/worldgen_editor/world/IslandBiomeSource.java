package org.wodichka.worldgen_editor.world;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Climate;
import org.wodichka.worldgen_editor.Worldgen_editor;

import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public final class IslandBiomeSource extends BiomeSource {
    public static final ResourceLocation ID = ResourceLocation.fromNamespaceAndPath(Worldgen_editor.MOD_ID, "island_biome_source");
    public static final String DEFAULT_PRESET = "default";
    public static final MapCodec<IslandBiomeSource> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
            BiomeSource.CODEC.fieldOf("delegate").forGetter(source -> source.delegate),
            Codec.STRING.optionalFieldOf("preset", DEFAULT_PRESET).forGetter(source -> source.presetName)
    ).apply(instance, IslandBiomeSource::new));

    private static boolean registered;

    private final BiomeSource delegate;
    private final String presetName;

    private IslandBiomeSource(BiomeSource delegate, String presetName) {
        this.delegate = delegate;
        this.presetName = presetName;
    }

    public String presetName() {
        return presetName;
    }

    public static void register() {
        if (registered) {
            return;
        }

        registered = true;
        Registry.register(BuiltInRegistries.BIOME_SOURCE, ID, CODEC);
    }

    public static BiomeSource wrap(BiomeSource delegate) {
        if (delegate instanceof IslandBiomeSource) {
            return delegate;
        }
        return new IslandBiomeSource(delegate, DEFAULT_PRESET);
    }

    @Override
    protected MapCodec<? extends BiomeSource> codec() {
        return CODEC;
    }

    @Override
    protected Stream<Holder<Biome>> collectPossibleBiomes() {
        return Stream.of(
                        delegate.possibleBiomes().stream(),
                        IslandWorldState.oceanBiomes().stream(),
                        IslandWorldState.fallbackLandBiomes().stream(),
                        IslandWorldState.deepDarkSubBiomes().stream(),
                        IslandWorldState.caveBiomePoolBiomes().stream()
                )
                .flatMap(stream -> stream)
                .distinct();
    }

    /**
     * BiomeSource normally memoizes possibleBiomes() in its superclass. WGE's cave-biome
     * pool is loaded from the world configuration after the BiomeSource itself has been
     * constructed, so the inherited memoized set can be missing pool biomes when the
     * ChunkGenerator builds its placed-feature index. Recompute from collectPossibleBiomes()
     * so a subsequent refreshFeaturesPerStep() sees the currently configured pool.
     */
    @Override
    public Set<Holder<Biome>> possibleBiomes() {
        return collectPossibleBiomes().collect(Collectors.toUnmodifiableSet());
    }

    @Override
    public Holder<Biome> getNoiseBiome(int quartX, int quartY, int quartZ, Climate.Sampler sampler) {
        IslandMask mask = IslandWorldState.mask();
        Climate.TargetPoint climate = sampler.sample(quartX, quartY, quartZ);
        double temperature = Climate.unquantizeCoord(climate.temperature());
        double humidity = Climate.unquantizeCoord(climate.humidity());

        if (mask.isEmpty()) {
            Holder<Biome> plainBiome = delegate.getNoiseBiome(quartX, quartY, quartZ, sampler);
            return IslandWorldState.substituteDeepDarkSubBiome(plainBiome, temperature, humidity);
        }

        int blockX = QuartPos.toBlock(quartX);
        int blockY = QuartPos.toBlock(quartY);
        int blockZ = QuartPos.toBlock(quartZ);
        IslandMask.SampleInfo sample = mask.sampleInfo(blockX, blockZ);
        Holder<Biome> delegateBiome = delegate.getNoiseBiome(quartX, quartY, quartZ, sampler);
        IslandMask.SourceInfo caveSource = sample.landSource() != null
                ? sample.landSource()
                : sample.archipelagoSource();
        Holder<Biome> pooledCaveBiome = IslandWorldState.caveBiomeFromPool(
                delegateBiome, caveSource, blockX, blockZ);
        if (pooledCaveBiome != null) {
            // A configured cave pool is authoritative. Return the selected biome before
            // WGE's surface/ocean replacement logic can substitute it. Minecraft will then
            // use this biome's own generation settings, including its carvers and placed features.
            return pooledCaveBiome;
        }

        delegateBiome = IslandWorldState.substituteDeepDarkSubBiome(delegateBiome, temperature, humidity);
        if (sample.value() < IslandTerrainHooks.FULL_OCEAN_MASK) {
            if (sample.oceanSource() == null && sample.archipelagoSource() == null && sample.landSource() == null) {
                Holder<Biome> outerOcean = IslandWorldState.outerOceanBiome();
                if (outerOcean != null) {
                    return outerOcean;
                }
            }
            IslandMask.SourceInfo source = sample.oceanSource() != null
                    ? sample.oceanSource()
                    : sample.archipelagoSource() != null ? sample.archipelagoSource() : sample.landSource();
            Holder<Biome> ocean = IslandWorldState.oceanBiome(sample.value(), source, delegateBiome, blockX, blockZ);
            if (ocean != null) {
                return ocean;
            }
        }

        IslandMask.SourceInfo source = sample.landSource() != null ? sample.landSource() : sample.archipelagoSource();
        return IslandWorldState.landBiome(sample.value(), source, delegateBiome, blockX, blockZ);
    }
}