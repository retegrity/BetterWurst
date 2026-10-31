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
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.BiPredicate;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.dimension.DimensionType;
import net.wurstclient.WurstClient;
import net.wurstclient.events.PacketInputListener;
import net.wurstclient.settings.ChunkAreaSetting;

public abstract class AbstractChunkCoordinator implements PacketInputListener
{
	protected final HashMap<ChunkPos, ChunkSearcher> searchers =
		new HashMap<>();
	protected final ChunkAreaSetting area;
	private BiPredicate<BlockPos, BlockState> query;
	
	protected final Set<ChunkPos> chunksToUpdate =
		Collections.synchronizedSet(new HashSet<>());
	
	// filled by the network thread, applied on the client thread
	private final ConcurrentLinkedQueue<PendingBlockUpdates> blockUpdates =
		new ConcurrentLinkedQueue<>();
	
	// changes whenever the available matches change
	private int matchesVersion;
	
	public AbstractChunkCoordinator(BiPredicate<BlockPos, BlockState> query,
		ChunkAreaSetting area)
	{
		this.query = Objects.requireNonNull(query);
		this.area = Objects.requireNonNull(area);
	}
	
	/**
	 * Adds and removes {@link ChunkSearcher}s, applies block updates and takes
	 * over finished results.
	 *
	 * @return true if searchers were added or removed, or if block updates
	 *         changed their results
	 */
	public boolean update()
	{
		DimensionType dimension = WurstClient.MC.level.dimensionType();
		HashSet<ChunkPos> chunkUpdates = clearChunksToUpdate();
		boolean searchersChanged = false;
		
		// remove outdated ChunkSearchers
		for(ChunkSearcher searcher : new ArrayList<>(searchers.values()))
		{
			boolean remove = false;
			ChunkPos searcherPos = searcher.getPos();
			
			// wrong dimension
			if(dimension != searcher.getDimension())
				remove = true;
			
			// out of range
			else if(!area.isInRange(searcherPos))
				remove = true;
			
			// chunk update
			else if(chunkUpdates.contains(searcherPos))
				remove = true;
			
			if(remove)
			{
				searchers.remove(searcherPos);
				searcher.cancel();
				onRemove(searcher);
				searchersChanged = true;
				matchesVersion++;
			}
		}
		
		// add new ChunkSearchers
		for(ChunkAccess chunk : area.getChunksInRange())
		{
			ChunkPos chunkPos = chunk.getPos();
			if(searchers.containsKey(chunkPos))
				continue;
			
			ChunkSearcher searcher = new ChunkSearcher(query, chunk, dimension);
			searchers.put(chunkPos, searcher);
			searcher.start();
			searchersChanged = true;
		}
		
		// apply block updates without searching the chunks again
		for(PendingBlockUpdates pending; (pending =
			blockUpdates.poll()) != null;)
		{
			ChunkSearcher searcher = searchers.get(pending.chunkPos());
			if(searcher != null
				&& searcher.applyBlockUpdates(pending.updates()))
			{
				searchersChanged = true;
				matchesVersion++;
			}
		}
		
		// take over finished results
		for(ChunkSearcher searcher : searchers.values())
			if(searcher.update())
				matchesVersion++;
			
		return searchersChanged;
	}
	
	/**
	 * Queues block updates for the chunk at the given position. Safe to call
	 * from the network thread.
	 */
	protected void enqueueBlockUpdates(ChunkPos chunkPos,
		List<ChunkSearcher.BlockUpdate> updates)
	{
		if(!updates.isEmpty())
			blockUpdates.add(new PendingBlockUpdates(chunkPos, updates));
	}
	
	/**
	 * Changes whenever searchers are added or removed, finish, or have their
	 * results changed by block updates.
	 */
	public int getMatchesVersion()
	{
		return matchesVersion;
	}
	
	protected void onRemove(ChunkSearcher searcher)
	{
		// Overridden in ChunkVertexBufferCoordinator
	}
	
	public void reset()
	{
		searchers.values().forEach(ChunkSearcher::cancel);
		searchers.clear();
		chunksToUpdate.clear();
		blockUpdates.clear();
		matchesVersion++;
	}
	
	public boolean isDone()
	{
		return searchers.values().stream().allMatch(ChunkSearcher::isDone);
	}
	
	public void setQuery(BiPredicate<BlockPos, BlockState> query)
	{
		this.query = Objects.requireNonNull(query);
		searchers.values().forEach(ChunkSearcher::cancel);
		searchers.clear();
		blockUpdates.clear();
		matchesVersion++;
	}
	
	public void setTargetBlock(Block block)
	{
		setQuery((pos, state) -> block == state.getBlock());
	}
	
	protected HashSet<ChunkPos> clearChunksToUpdate()
	{
		synchronized(chunksToUpdate)
		{
			HashSet<ChunkPos> chunks = new HashSet<>(chunksToUpdate);
			chunksToUpdate.clear();
			return chunks;
		}
	}
	
	private record PendingBlockUpdates(ChunkPos chunkPos,
		List<ChunkSearcher.BlockUpdate> updates)
	{}
}
