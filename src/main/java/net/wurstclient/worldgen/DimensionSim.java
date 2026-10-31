/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.worldgen;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.FeatureSorter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.feature.configurations.OreConfiguration;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;

/**
 * The world generation of one dimension, set up the same way the server does
 * it in {@code ChunkMap}.
 */
public final class DimensionSim
{
	private final WorldgenData data;
	private final ResourceKey<Level> level;
	private final ChunkGenerator generator;
	private final DimensionType dimensionType;
	private final LevelHeightAccessor heightAccessor;
	private final RandomState randomState;
	private final ChunkGeneratorStructureState structureState;
	private final Registry<Biome> biomes;
	private final long seed;
	
	private List<FeatureSorter.StepFeatureData> featuresPerStep;
	private Set<Block> oreBlocks;
	
	// terrain heights are expensive, and ore placement asks for the same
	// columns over and over
	private final Map<Long, Integer> heightCache = new ConcurrentHashMap<>();
	
	DimensionSim(WorldgenData data, ResourceKey<Level> level, LevelStem stem,
		RegistryAccess registries, long seed)
	{
		this.data = data;
		this.level = level;
		this.seed = seed;
		generator = stem.generator();
		dimensionType = stem.type().value();
		heightAccessor = LevelHeightAccessor.create(dimensionType.minY(),
			dimensionType.height());
		biomes = registries.lookupOrThrow(Registries.BIOME);
		
		NoiseGeneratorSettings settings =
			generator instanceof NoiseBasedChunkGenerator noise
				? noise.generatorSettings().value()
				: NoiseGeneratorSettings.dummy();
		randomState = RandomState.create(settings,
			registries.lookupOrThrow(Registries.NOISE), seed);
		structureState = generator.createState(
			registries.lookupOrThrow(Registries.STRUCTURE_SET), randomState,
			seed);
	}
	
	/**
	 * All placed features, in the order the server places them. This is
	 * what Terralith changes, since its biomes have more features.
	 */
	public synchronized List<FeatureSorter.StepFeatureData> getFeaturesPerStep()
	{
		if(featuresPerStep == null)
			featuresPerStep = FeatureSorter.buildFeaturesPerStep(
				List.copyOf(generator.getBiomeSource().possibleBiomes()),
				biome -> generator.getBiomeGenerationSettings(biome).features(),
				true);
		
		return featuresPerStep;
	}
	
	/**
	 * The ore blocks that ore features can generate in this dimension.
	 */
	public synchronized Set<Block> getOreBlocks()
	{
		if(oreBlocks != null)
			return oreBlocks;
		
		Set<Block> blocks = new LinkedHashSet<>();
		for(FeatureSorter.StepFeatureData step : getFeaturesPerStep())
			for(PlacedFeature feature : step.features())
				if(isOreFeature(feature))
					for(OreConfiguration.TargetBlockState target : ((OreConfiguration)feature
						.feature().value().config()).targetStates)
						if(OreBlocks.isOre(target.state.getBlock()))
							blocks.add(target.state.getBlock());
						
		oreBlocks = Collections.unmodifiableSet(blocks);
		return oreBlocks;
	}
	
	public static boolean isOreFeature(PlacedFeature feature)
	{
		return feature.feature().value().config() instanceof OreConfiguration;
	}
	
	/**
	 * The biome that the world generator puts at the given quart position.
	 */
	public Holder<Biome> getNoiseBiome(int quartX, int quartY, int quartZ)
	{
		return generator.getBiomeSource().getNoiseBiome(quartX, quartY, quartZ,
			randomState.sampler());
	}
	
	/**
	 * The same biome from this world generation's registry, for a biome from
	 * the client's registry, or null if it doesn't exist here.
	 */
	public Holder<Biome> mapBiome(Holder<Biome> clientBiome)
	{
		return clientBiome.unwrapKey().flatMap(biomes::get).orElse(null);
	}
	
	/**
	 * The height of the terrain that the world generator creates, before
	 * caves and features.
	 */
	public int getBaseHeight(int x, int z, Heightmap.Types type)
	{
		long key = (long)type.ordinal() << 60 | (long)(x & 0x0FFFFFFF) << 32
			| z & 0xFFFFFFFFL;
		Integer cached = heightCache.get(key);
		if(cached != null)
			return cached;
		
		int height =
			generator.getBaseHeight(x, z, type, heightAccessor, randomState);
		if(heightCache.size() > 500_000)
			heightCache.clear();
		heightCache.put(key, height);
		return height;
	}
	
	public WorldgenData getData()
	{
		return data;
	}
	
	public ResourceKey<Level> getLevel()
	{
		return level;
	}
	
	public ChunkGenerator getGenerator()
	{
		return generator;
	}
	
	public BiomeSource getBiomeSource()
	{
		return generator.getBiomeSource();
	}
	
	public DimensionType getDimensionType()
	{
		return dimensionType;
	}
	
	public LevelHeightAccessor getHeightAccessor()
	{
		return heightAccessor;
	}
	
	public RandomState getRandomState()
	{
		return randomState;
	}
	
	public ChunkGeneratorStructureState getStructureState()
	{
		return structureState;
	}
	
	public long getSeed()
	{
		return seed;
	}
}
