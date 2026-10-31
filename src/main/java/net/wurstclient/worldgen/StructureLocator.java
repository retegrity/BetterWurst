/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.worldgen;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.BooleanSupplier;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.levelgen.LegacyRandomSource;
import net.minecraft.world.level.levelgen.WorldgenRandom;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.pieces.PiecesContainer;
import net.minecraft.world.level.levelgen.structure.StructureSet;
import net.minecraft.world.level.levelgen.structure.placement.ConcentricRingsStructurePlacement;
import net.minecraft.world.level.levelgen.structure.structures.JigsawStructure;
import net.minecraft.world.level.levelgen.structure.placement.RandomSpreadStructurePlacement;
import net.minecraft.world.level.levelgen.structure.placement.StructurePlacement;

/**
 * Finds structures from the seed, by deciding for each possible chunk what
 * the server would generate there, like {@code ChunkGenerator
 * .createStructures()} does.
 */
public final class StructureLocator
{
	// same as vanilla's /locate
	private static final int MAX_RADIUS = 100;
	
	private final DimensionSim dim;
	
	public StructureLocator(DimensionSim dim)
	{
		this.dim = dim;
	}
	
	/**
	 * A found structure.
	 *
	 * @param pos
	 *            where vanilla's /locate would say it is, with the height of
	 *            the middle of the structure
	 */
	public record Result(BlockPos pos, double distance)
	{}
	
	/**
	 * Finds the nearest structures of the given kind.
	 */
	public List<Result> locate(Holder<Structure> wanted, BlockPos from,
		int count, BooleanSupplier cancelled)
	{
		ChunkGeneratorStructureState state = dim.getStructureState();
		List<Result> results = new ArrayList<>();
		
		for(Holder<StructureSet> set : state.possibleStructureSets())
		{
			if(set.value().structures().stream()
				.noneMatch(e -> e.structure().equals(wanted)))
				continue;
			
			StructurePlacement placement = set.value().placement();
			if(placement instanceof ConcentricRingsStructurePlacement rings)
				for(ChunkPos chunk : state.getRingPositionsFor(rings))
				{
					if(cancelled.getAsBoolean())
						return results;
					addIfGenerates(set.value(), wanted, chunk, from, results);
				}
			else if(placement instanceof RandomSpreadStructurePlacement spread)
				searchSpread(set.value(), spread, wanted, from, count, results,
					cancelled);
		}
		
		results.sort(Comparator.comparingDouble(Result::distance));
		return results.size() > count ? results.subList(0, count) : results;
	}
	
	/**
	 * Goes through the regions around the player in growing squares, like
	 * vanilla's /locate.
	 */
	private void searchSpread(StructureSet set,
		RandomSpreadStructurePlacement placement, Holder<Structure> wanted,
		BlockPos from, int count, List<Result> results,
		BooleanSupplier cancelled)
	{
		int spacing = placement.spacing();
		int originX = SectionPos.blockToSectionCoord(from.getX());
		int originZ = SectionPos.blockToSectionCoord(from.getZ());
		Set<ChunkPos> checked = new HashSet<>();
		
		for(int radius = 0; radius <= MAX_RADIUS; radius++)
		{
			// everything further out is at least this far away
			if(results.size() >= count)
			{
				results.sort(Comparator.comparingDouble(Result::distance));
				double minDistance = (radius - 1) * spacing * 16.0;
				if(results.get(count - 1).distance() < minDistance)
					return;
			}
			
			for(int x = -radius; x <= radius; x++)
				for(int z = -radius; z <= radius; z++)
				{
					if(Math.abs(x) != radius && Math.abs(z) != radius)
						continue;
					
					if(cancelled.getAsBoolean())
						return;
					
					ChunkPos chunk =
						placement.getPotentialStructureChunk(dim.getSeed(),
							originX + spacing * x, originZ + spacing * z);
					if(checked.add(chunk))
						addIfGenerates(set, wanted, chunk, from, results);
				}
		}
	}
	
	private void addIfGenerates(StructureSet set, Holder<Structure> wanted,
		ChunkPos chunk, BlockPos from, List<Result> results)
	{
		ChunkGeneratorStructureState state = dim.getStructureState();
		if(!set.placement().isStructureChunk(state, chunk.x(), chunk.z()))
			return;
		
		// quick check first, most chunks fail here
		Structure.GenerationStub stub = findGenerationPoint(wanted, chunk);
		if(stub == null || !isGenerated(set, chunk, wanted))
			return;
		
		BlockPos locatePos = set.placement().getLocatePos(chunk);
		BlockPos pos = new BlockPos(locatePos.getX(),
			getHeight(wanted, chunk, stub), locatePos.getZ());
		double distance =
			Math.hypot(pos.getX() - from.getX(), pos.getZ() - from.getZ());
		results.add(new Result(pos, distance));
	}
	
	/**
	 * Whether the wanted structure is the one of the set that the server
	 * picks for the given chunk. Sets like villages have several structures,
	 * and the server tries them in a random order until one fits.
	 */
	private boolean isGenerated(StructureSet set, ChunkPos chunk,
		Holder<Structure> wanted)
	{
		List<StructureSet.StructureSelectionEntry> structures =
			set.structures();
		if(structures.size() == 1)
			return true;
		
		List<StructureSet.StructureSelectionEntry> options =
			new ArrayList<>(structures);
		WorldgenRandom random = new WorldgenRandom(new LegacyRandomSource(0));
		random.setLargeFeatureSeed(dim.getSeed(), chunk.x(), chunk.z());
		int total = 0;
		for(StructureSet.StructureSelectionEntry option : options)
			total += option.weight();
		
		while(!options.isEmpty())
		{
			int choice = random.nextInt(total);
			int index = 0;
			for(StructureSet.StructureSelectionEntry option : options)
			{
				choice -= option.weight();
				if(choice < 0)
					break;
				
				index++;
			}
			
			StructureSet.StructureSelectionEntry selected = options.get(index);
			if(wanted.equals(selected.structure()))
				return true;
			
			if(findGenerationPoint(selected.structure(), chunk) != null)
				return false;
			
			options.remove(index);
			total -= selected.weight();
		}
		
		return false;
	}
	
	/**
	 * A useful height for the structure. Jigsaw structures (villages,
	 * ancient cities, etc.) start at a real height. The others don't, so
	 * they are built to find their middle, which is slower but they are
	 * rare.
	 */
	private int getHeight(Holder<Structure> holder, ChunkPos chunk,
		Structure.GenerationStub stub)
	{
		if(holder.value() instanceof JigsawStructure)
			return stub.position().getY();
		
		PiecesContainer pieces = stub.getPiecesBuilder().build();
		if(pieces.isEmpty())
			return stub.position().getY();
		
		BoundingBox box = pieces.calculateBoundingBox();
		return box.getCenter().getY();
	}
	
	/**
	 * Where the structure would start in the given chunk, or null if it
	 * can't generate there (wrong biome, terrain, etc.). This is the check
	 * that vanilla's /locate does. It doesn't build the whole structure,
	 * which would be much slower.
	 */
	private Structure.GenerationStub findGenerationPoint(
		Holder<Structure> holder, ChunkPos chunk)
	{
		Structure structure = holder.value();
		return structure
			.findValidGenerationPoint(new Structure.GenerationContext(
				dim.getData().getRegistries(), dim.getGenerator(),
				dim.getBiomeSource(), dim.getRandomState(),
				dim.getData().getTemplates(), dim.getSeed(), chunk,
				dim.getHeightAccessor(), structure.biomes()::contains))
			.orElse(null);
	}
}
