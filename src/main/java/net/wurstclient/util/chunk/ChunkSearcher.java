/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.util.chunk;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.function.BiPredicate;
import java.util.stream.Stream;

import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.dimension.DimensionType;
import net.wurstclient.util.MinPriorityThreadFactory;

/**
 * Searches the given {@link ChunkAccess} for blocks matching the given query.
 *
 * <p>
 * The search runs on a background thread. Its results are only taken over on
 * the client thread, in {@link #update()}, and block updates are applied to
 * them there too, instead of searching the whole chunk again.
 */
public final class ChunkSearcher
{
	private static final ExecutorService BACKGROUND_THREAD_POOL =
		MinPriorityThreadFactory.newFixedThreadPool();
	
	private final BiPredicate<BlockPos, BlockState> query;
	private final ChunkAccess chunk;
	private final DimensionType dimension;
	
	private CompletableFuture<ArrayList<Result>> future;
	private volatile boolean interrupted;
	
	// client thread only
	private ArrayList<Result> results;
	private LinkedHashMap<BlockPos, Result> resultsByPos;
	private ArrayList<BlockUpdate> pendingUpdates;
	
	// read by other threads, never changed after it's set
	private volatile List<Result> snapshot;
	
	public ChunkSearcher(BiPredicate<BlockPos, BlockState> query,
		ChunkAccess chunk, DimensionType dimension)
	{
		this.query = query;
		this.chunk = chunk;
		this.dimension = dimension;
	}
	
	public void start()
	{
		if(future != null || interrupted)
			throw new IllegalStateException();
		
		future = CompletableFuture.supplyAsync(this::searchNow,
			BACKGROUND_THREAD_POOL);
	}
	
	/**
	 * Reads the blocks straight from the chunk's sections, which is much
	 * faster than calling {@link ChunkAccess#getBlockState(BlockPos)} with a
	 * new {@link BlockPos} for every block.
	 */
	private ArrayList<Result> searchNow()
	{
		ArrayList<Result> results = new ArrayList<>();
		ChunkPos chunkPos = chunk.getPos();
		int minX = chunkPos.getMinBlockX();
		int minZ = chunkPos.getMinBlockZ();
		
		BlockState air = Blocks.AIR.defaultBlockState();
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		LevelChunkSection[] sections = chunk.getSections();
		
		// everything above the highest non-empty section is air
		int highestSection = chunk.getHighestFilledSectionIndex();
		for(int i = 0; i <= highestSection && i < sections.length; i++)
		{
			LevelChunkSection section = sections[i];
			PalettedContainer<BlockState> states =
				section == null || section.hasOnlyAir() ? null
					: section.getStates();
			int minY = SectionPos
				.sectionToBlockCoord(chunk.getSectionYFromSectionIndex(i));
			
			for(int y = 0; y < 16; y++)
				for(int z = 0; z < 16; z++)
				{
					if(interrupted)
						return results;
					
					for(int x = 0; x < 16; x++)
					{
						BlockState state =
							states == null ? air : states.get(x, y, z);
						pos.set(minX + x, minY + y, minZ + z);
						if(query.test(pos, state))
							results.add(new Result(pos.immutable(), state));
					}
				}
		}
		
		return results;
	}
	
	/**
	 * Takes over the results once the search is done. Must be called on the
	 * client thread.
	 *
	 * @return true if the results just became available
	 */
	public boolean update()
	{
		if(results != null || future == null || !future.isDone()
			|| future.isCancelled())
			return false;
		
		results = future.join();
		snapshot = Collections.unmodifiableList(results);
		
		if(pendingUpdates != null)
		{
			applyUpdates(pendingUpdates);
			pendingUpdates = null;
		}
		
		return true;
	}
	
	/**
	 * Applies block updates to the results, so the chunk doesn't have to be
	 * searched again. Updates that arrive before the search is done are
	 * applied once it is. Must be called on the client thread.
	 *
	 * @return true if the results changed
	 */
	public boolean applyBlockUpdates(List<BlockUpdate> updates)
	{
		if(results == null)
		{
			if(pendingUpdates == null)
				pendingUpdates = new ArrayList<>();
			
			pendingUpdates.addAll(updates);
			return false;
		}
		
		return applyUpdates(updates);
	}
	
	private boolean applyUpdates(List<BlockUpdate> updates)
	{
		// only chunks that actually get block updates need the lookup map
		if(resultsByPos == null)
		{
			resultsByPos = new LinkedHashMap<>();
			for(Result result : results)
				resultsByPos.put(result.pos(), result);
		}
		
		boolean changed = false;
		for(BlockUpdate update : updates)
		{
			BlockPos pos = update.pos();
			BlockState state = update.state();
			
			if(!query.test(pos, state))
			{
				changed |= resultsByPos.remove(pos) != null;
				continue;
			}
			
			Result old = resultsByPos.put(pos, new Result(pos, state));
			changed |= old == null || old.state() != state;
		}
		
		if(changed)
			snapshot = Collections
				.unmodifiableList(new ArrayList<>(resultsByPos.values()));
		
		return changed;
	}
	
	public void cancel()
	{
		if(future == null || future.isDone())
			return;
		
		interrupted = true;
		future.cancel(false);
	}
	
	public boolean isInterrupted()
	{
		return interrupted;
	}
	
	public ChunkPos getPos()
	{
		return chunk.getPos();
	}
	
	public DimensionType getDimension()
	{
		return dimension;
	}
	
	/**
	 * Returns the results, or nothing if they aren't available yet. Safe to
	 * call from any thread.
	 */
	public Stream<Result> getMatches()
	{
		return getMatchesList().stream();
	}
	
	/**
	 * Returns the results, or an empty list if they aren't available yet.
	 * The list never changes, so it's safe to use from any thread.
	 */
	public List<Result> getMatchesList()
	{
		List<Result> matches = snapshot;
		return matches == null ? List.of() : matches;
	}
	
	/**
	 * Whether the results are available, see {@link #update()}.
	 */
	public boolean isDone()
	{
		return snapshot != null;
	}
	
	public record Result(BlockPos pos, BlockState state)
	{}
	
	public record BlockUpdate(BlockPos pos, BlockState state)
	{}
}
