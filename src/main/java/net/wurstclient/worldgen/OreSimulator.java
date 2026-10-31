/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.worldgen;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import it.unimi.dsi.fastutil.ints.IntArraySet;
import it.unimi.dsi.fastutil.ints.IntSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.QuartPos;
import net.minecraft.core.SectionPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.biome.FeatureSorter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.PalettedContainerFactory;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.UpgradeData;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.RandomSupport;
import net.minecraft.world.level.levelgen.WorldgenRandom;
import net.minecraft.world.level.levelgen.XoroshiroRandomSource;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;

/**
 * Predicts where ores are, by running the server's ore features for a chunk
 * the same way {@code ChunkGenerator.applyBiomeDecoration()} does: same
 * random seeds, same features in the same order.
 *
 * <p>
 * The features run on a copy of the chunks around it, as the client sees
 * them, but with all ores turned back into stone. That's what the world
 * looked like when the ores were generated (caves and all), and it also
 * removes the fake ores of anti-xray plugins. Chunks that aren't loaded are
 * assumed to be solid stone.
 */
public final class OreSimulator
{
	private static final Logger LOGGER =
		LoggerFactory.getLogger("Wurst OreSimulator");
	private static final Set<String> WARNED = ConcurrentHashMap.newKeySet();
	
	private final DimensionSim dim;
	private final Map<Long, LevelChunk> clientChunks;
	private final PalettedContainerFactory containers;
	private final BlockState defaultBlock;
	
	/**
	 * @param clientChunks
	 *            the chunks that the client has loaded around the area,
	 *            collected on the main thread
	 */
	public OreSimulator(DimensionSim dim, Map<Long, LevelChunk> clientChunks)
	{
		this.dim = dim;
		this.clientChunks = clientChunks;
		containers =
			PalettedContainerFactory.create(dim.getData().getRegistries());
		defaultBlock =
			dim.getGenerator() instanceof NoiseBasedChunkGenerator noise
				? noise.generatorSettings().value().defaultBlock()
				: Blocks.STONE.defaultBlockState();
	}
	
	/**
	 * Whether the chunk and all its neighbors are loaded. Only then the
	 * simulation knows the caves and the terrain height, and it's fast.
	 */
	public boolean isLoadedAround(ChunkPos center)
	{
		for(int x = center.x() - 1; x <= center.x() + 1; x++)
			for(int z = center.z() - 1; z <= center.z() + 1; z++)
				if(!clientChunks.containsKey(ChunkPos.pack(x, z)))
					return false;
				
		return true;
	}
	
	/**
	 * Runs the ore features of one chunk.
	 *
	 * @return the generated ores of the wanted kinds, which can also be in
	 *         the neighboring chunks
	 */
	public Map<BlockPos, Block> simulate(ChunkPos center, Set<Block> wanted)
	{
		SimLevel sim = new SimLevel(center);
		BlockPos origin = new BlockPos(center.getMinBlockX(),
			dim.getHeightAccessor().getMinY(), center.getMinBlockZ());
		
		WorldgenRandom random = new WorldgenRandom(
			new XoroshiroRandomSource(RandomSupport.generateUniqueSeed()));
		long decorationSeed = random.setDecorationSeed(dim.getSeed(),
			origin.getX(), origin.getZ());
		
		Set<Holder<Biome>> biomes = sim.getBiomesAround();
		biomes.retainAll(dim.getBiomeSource().possibleBiomes());
		
		List<FeatureSorter.StepFeatureData> steps = dim.getFeaturesPerStep();
		for(int step = 0; step < steps.size(); step++)
		{
			FeatureSorter.StepFeatureData stepData = steps.get(step);
			IntSet indexes = new IntArraySet();
			for(Holder<Biome> biome : biomes)
			{
				List<HolderSet<PlacedFeature>> features = dim.getGenerator()
					.getBiomeGenerationSettings(biome).features();
				if(step < features.size())
					features.get(step).stream().map(Holder::value)
						.forEach(f -> indexes
							.add(stepData.indexMapping().applyAsInt(f)));
			}
			
			int[] sorted = indexes.toIntArray();
			Arrays.sort(sorted);
			
			// Every feature gets its own random seed, so the other features
			// can be skipped without changing where the ores go. Ores still
			// all have to run, since they can block each other.
			for(int index : sorted)
			{
				PlacedFeature feature = stepData.features().get(index);
				if(!DimensionSim.isOreFeature(feature))
					continue;
				
				random.setFeatureSeed(decorationSeed, index, step);
				try
				{
					feature.placeWithBiomeCheck(sim.level, dim.getGenerator(),
						random, origin);
					
				}catch(RuntimeException e)
				{
					if(WARNED.add(feature.toString()))
						LOGGER.warn("Couldn't simulate feature {}", feature, e);
				}
			}
		}
		
		return sim.findBlocks(wanted);
	}
	
	/**
	 * The world that the features generate in: copies of the 3x3 chunks
	 * around the center, behind a {@link WorldGenLevel} proxy that only
	 * supports what ore features need.
	 */
	private final class SimLevel implements InvocationHandler
	{
		private final ChunkPos center;
		private final Map<Long, SimChunk> chunks = new HashMap<>();
		private final BiomeManager biomeManager;
		private final RandomSource random = RandomSource.create();
		private final WorldGenLevel level;
		
		private SimLevel(ChunkPos center)
		{
			this.center = center;
			biomeManager = new BiomeManager(this::getNoiseBiome,
				BiomeManager.obfuscateSeed(dim.getSeed()));
			level = (WorldGenLevel)Proxy.newProxyInstance(
				WorldGenLevel.class.getClassLoader(),
				new Class<?>[]{WorldGenLevel.class}, this);
		}
		
		@Override
		public Object invoke(Object proxy, Method method, Object[] args)
			throws Throwable
		{
			int argCount = args == null ? 0 : args.length;
			switch(method.getName())
			{
				case "getChunk":
				if(argCount == 4 && args[0] instanceof Integer x)
					return getChunk(x, (Integer)args[1]);
				break;
				
				case "getBlockState":
				if(argCount == 1 && args[0] instanceof BlockPos pos)
					return getBlockState(pos);
				break;
				
				case "getFluidState":
				if(argCount == 1 && args[0] instanceof BlockPos pos)
					return getBlockState(pos).getFluidState();
				break;
				
				case "setBlock":
				if(argCount == 4 && args[0] instanceof BlockPos pos)
					return setBlock(pos, (BlockState)args[1]);
				break;
				
				case "getHeight":
				if(argCount == 3 && args[0] instanceof Heightmap.Types type)
					return getHeight(type, (Integer)args[1], (Integer)args[2]);
				break;
				
				case "ensureCanWrite":
				return canWrite((BlockPos)args[0]);
				
				case "dimensionType":
				return dim.getDimensionType();
				
				case "getSeed":
				return dim.getSeed();
				
				case "registryAccess":
				return dim.getData().getRegistries();
				
				case "getBiomeManager":
				return biomeManager;
				
				case "getUncachedNoiseBiome":
				return getNoiseBiome((Integer)args[0], (Integer)args[1],
					(Integer)args[2]);
				
				case "getRandom":
				return random;
				
				case "isClientSide":
				return false;
				
				case "getLevel":
				return null;
				
				case "toString":
				return "OreSimulator.SimLevel" + center;
				
				case "hashCode":
				return System.identityHashCode(proxy);
				
				case "equals":
				return proxy == args[0];
			}
			
			if(method.isDefault())
				return InvocationHandler.invokeDefault(proxy, method, args);
			
			throw new UnsupportedOperationException(
				"OreSimulator doesn't support " + method);
		}
		
		private SimChunk getChunk(int x, int z)
		{
			return chunks.computeIfAbsent(ChunkPos.pack(x, z),
				key -> new SimChunk(new ChunkPos(x, z)));
		}
		
		private BlockState getBlockState(BlockPos pos)
		{
			if(dim.getHeightAccessor().isOutsideBuildHeight(pos.getY()))
				return Blocks.VOID_AIR.defaultBlockState();
			
			return getChunk(SectionPos.blockToSectionCoord(pos.getX()),
				SectionPos.blockToSectionCoord(pos.getZ())).getSimBlock(pos);
		}
		
		private boolean setBlock(BlockPos pos, BlockState state)
		{
			if(!canWrite(pos))
				return false;
			
			getChunk(SectionPos.blockToSectionCoord(pos.getX()),
				SectionPos.blockToSectionCoord(pos.getZ())).setSimBlock(pos,
					state);
			return true;
		}
		
		/**
		 * Like {@code WorldGenRegion}, features can only write into the
		 * chunk and its direct neighbors.
		 */
		private boolean canWrite(BlockPos pos)
		{
			int x = SectionPos.blockToSectionCoord(pos.getX());
			int z = SectionPos.blockToSectionCoord(pos.getZ());
			return Math.abs(x - center.x()) <= 1
				&& Math.abs(z - center.z()) <= 1
				&& !dim.getHeightAccessor().isOutsideBuildHeight(pos.getY());
		}
		
		private int getHeight(Heightmap.Types type, int x, int z)
		{
			SimChunk chunk = getChunk(SectionPos.blockToSectionCoord(x),
				SectionPos.blockToSectionCoord(z));
			if(chunk.client != null)
				return chunk.getSimHeight(type, x, z);
			
			// much slower, but there's nothing else to go by
			return dim.getBaseHeight(x, z, type);
		}
		
		/**
		 * The biome at a quart position: what the server sent for loaded
		 * chunks, otherwise what the world generator would put there.
		 */
		private Holder<Biome> getNoiseBiome(int quartX, int quartY, int quartZ)
		{
			LevelChunk client = clientChunks.get(ChunkPos
				.pack(QuartPos.toSection(quartX), QuartPos.toSection(quartZ)));
			if(client != null)
			{
				Holder<Biome> biome =
					dim.mapBiome(client.getNoiseBiome(quartX, quartY, quartZ));
				if(biome != null)
					return biome;
			}
			
			return dim.getNoiseBiome(quartX, quartY, quartZ);
		}
		
		/**
		 * All biomes in the 3x3 chunks around the center, which decide what
		 * features the center chunk gets.
		 */
		private Set<Holder<Biome>> getBiomesAround()
		{
			Set<Holder<Biome>> biomes = new HashSet<>();
			int minQuartY =
				QuartPos.fromBlock(dim.getHeightAccessor().getMinY());
			int quartHeight =
				QuartPos.fromBlock(dim.getHeightAccessor().getHeight());
			
			for(int cx = center.x() - 1; cx <= center.x() + 1; cx++)
				for(int cz = center.z() - 1; cz <= center.z() + 1; cz++)
				{
					LevelChunk client = clientChunks.get(ChunkPos.pack(cx, cz));
					if(client != null)
					{
						for(LevelChunkSection section : client.getSections())
							section.getBiomes().getAll(biome -> {
								Holder<Biome> mapped = dim.mapBiome(biome);
								if(mapped != null)
									biomes.add(mapped);
							});
						continue;
					}
					
					int quartX = QuartPos.fromSection(cx);
					int quartZ = QuartPos.fromSection(cz);
					for(int qx = 0; qx < 4; qx++)
						for(int qz = 0; qz < 4; qz++)
							for(int qy = 0; qy < quartHeight; qy++)
								biomes.add(dim.getNoiseBiome(quartX + qx,
									minQuartY + qy, quartZ + qz));
				}
			
			return biomes;
		}
		
		private Map<BlockPos, Block> findBlocks(Set<Block> wanted)
		{
			Map<BlockPos, Block> found = new HashMap<>();
			for(SimChunk chunk : chunks.values())
				chunk.findBlocks(wanted, found);
			
			return found;
		}
	}
	
	/**
	 * A copy of a chunk, made section by section when a feature first
	 * touches it.
	 */
	private final class SimChunk extends ProtoChunk
	{
		private final LevelChunk client;
		private final LevelChunkSection[] simSections;
		private final Map<Heightmap.Types, int[]> heights = new HashMap<>();
		
		private SimChunk(ChunkPos pos)
		{
			super(pos, UpgradeData.EMPTY, dim.getHeightAccessor(), containers,
				null);
			client = clientChunks.get(pos.pack());
			simSections = new LevelChunkSection[getSectionsCount()];
		}
		
		@Override
		public LevelChunkSection getSection(int sectionIndex)
		{
			LevelChunkSection section = simSections[sectionIndex];
			if(section == null)
				simSections[sectionIndex] =
					section = createSection(sectionIndex);
			
			return section;
		}
		
		private LevelChunkSection createSection(int sectionIndex)
		{
			if(client != null && sectionIndex < client.getSectionsCount())
			{
				LevelChunkSection copy = client.getSection(sectionIndex).copy();
				if(copy.maybeHas(OreBlocks::isOre))
					for(int x = 0; x < 16; x++)
						for(int y = 0; y < 16; y++)
							for(int z = 0; z < 16; z++)
							{
								BlockState state = copy.getBlockState(x, y, z);
								if(OreBlocks.isOre(state))
									copy.setBlockState(x, y, z,
										OreBlocks.getBaseState(state), false);
							}
						
				return copy;
			}
			
			// not loaded, so assume it's solid stone (deepslate below 0)
			int minY = SectionPos
				.sectionToBlockCoord(getSectionYFromSectionIndex(sectionIndex));
			BlockState base = defaultBlock.is(Blocks.STONE) && minY < 0
				? Blocks.DEEPSLATE.defaultBlockState() : defaultBlock;
			return new LevelChunkSection(
				new PalettedContainer<>(base, containers.blockStatesStrategy()),
				containers.createForBiomes());
		}
		
		/**
		 * The height of a column, the way heightmaps measure it: one above
		 * the highest block that counts for the given heightmap.
		 */
		private int getSimHeight(Heightmap.Types type, int x, int z)
		{
			int[] columns = heights.computeIfAbsent(type, t -> {
				int[] array = new int[256];
				Arrays.fill(array, Integer.MIN_VALUE);
				return array;
			});
			
			int index = (x & 15) | (z & 15) << 4;
			if(columns[index] != Integer.MIN_VALUE)
				return columns[index];
			
			int height = getMinY();
			for(int i = simSections.length - 1; i >= 0; i--)
			{
				LevelChunkSection section = getSection(i);
				if(section.hasOnlyAir())
					continue;
				
				int y = topOpaque(section, type, x & 15, z & 15);
				if(y >= 0)
				{
					height = SectionPos.sectionToBlockCoord(
						getSectionYFromSectionIndex(i)) + y + 1;
					break;
				}
			}
			
			columns[index] = height;
			return height;
		}
		
		private static int topOpaque(LevelChunkSection section,
			Heightmap.Types type, int x, int z)
		{
			for(int y = 15; y >= 0; y--)
				if(type.isOpaque().test(section.getBlockState(x, y, z)))
					return y;
				
			return -1;
		}
		
		private BlockState getSimBlock(BlockPos pos)
		{
			LevelChunkSection section = getSection(getSectionIndex(pos.getY()));
			return section.getBlockState(pos.getX() & 15, pos.getY() & 15,
				pos.getZ() & 15);
		}
		
		private void setSimBlock(BlockPos pos, BlockState state)
		{
			getSection(getSectionIndex(pos.getY())).setBlockState(
				pos.getX() & 15, pos.getY() & 15, pos.getZ() & 15, state,
				false);
		}
		
		/**
		 * The copies have no ores, so any ore in them was just generated.
		 */
		private void findBlocks(Set<Block> wanted, Map<BlockPos, Block> found)
		{
			for(int i = 0; i < simSections.length; i++)
			{
				LevelChunkSection section = simSections[i];
				if(section == null
					|| !section.maybeHas(s -> wanted.contains(s.getBlock())))
					continue;
				
				int minY = SectionPos
					.sectionToBlockCoord(getSectionYFromSectionIndex(i));
				for(int x = 0; x < 16; x++)
					for(int y = 0; y < 16; y++)
						for(int z = 0; z < 16; z++)
						{
							Block block =
								section.getBlockState(x, y, z).getBlock();
							if(wanted.contains(block))
								found.put(
									new BlockPos(getPos().getMinBlockX() + x,
										minY + y, getPos().getMinBlockZ() + z),
									block);
						}
			}
		}
	}
}
