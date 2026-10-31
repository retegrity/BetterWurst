/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.util.chunk;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiPredicate;
import java.util.stream.Stream;

import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundSectionBlocksUpdatePacket;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import net.wurstclient.settings.ChunkAreaSetting;
import net.wurstclient.util.chunk.ChunkSearcher.Result;

public final class ChunkSearcherCoordinator extends AbstractChunkCoordinator
{
	public ChunkSearcherCoordinator(ChunkAreaSetting area)
	{
		this((pos, state) -> false, area);
	}
	
	public ChunkSearcherCoordinator(BiPredicate<BlockPos, BlockState> query,
		ChunkAreaSetting area)
	{
		super(query, area);
	}
	
	@Override
	public void onReceivedPacket(PacketInputEvent event)
	{
		Packet<?> packet = event.getPacket();
		
		// single blocks are updated in place
		if(packet instanceof ClientboundBlockUpdatePacket update)
		{
			BlockPos pos = update.getPos().immutable();
			enqueueBlockUpdates(ChunkPos.containing(pos), List.of(
				new ChunkSearcher.BlockUpdate(pos, update.getBlockState())));
			return;
		}
		
		if(packet instanceof ClientboundSectionBlocksUpdatePacket update)
		{
			ArrayList<ChunkSearcher.BlockUpdate> updates = new ArrayList<>();
			update.runUpdates((pos, state) -> updates
				.add(new ChunkSearcher.BlockUpdate(pos.immutable(), state)));
			enqueueBlockUpdates(update.sectionPos.chunk(), updates);
			return;
		}
		
		// whole chunks are searched again
		ChunkPos chunkPos = ChunkUtils.getAffectedChunk(packet);
		if(chunkPos != null)
			chunksToUpdate.add(chunkPos);
	}
	
	public Stream<Result> getMatches()
	{
		return searchers.values().stream().flatMap(ChunkSearcher::getMatches);
	}
	
	/**
	 * Returns the results of all finished searchers. Must be called on the
	 * client thread, but the returned lists never change, so they can be used
	 * from any thread.
	 */
	public List<List<Result>> getMatchLists()
	{
		ArrayList<List<Result>> lists = new ArrayList<>();
		for(ChunkSearcher searcher : searchers.values())
		{
			List<Result> matches = searcher.getMatchesList();
			if(!matches.isEmpty())
				lists.add(matches);
		}
		
		return lists;
	}
}
