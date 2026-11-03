/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks;

import java.awt.Color;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinTask;
import java.util.function.BiPredicate;

import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.wurstclient.Category;
import net.wurstclient.SearchTags;
import net.wurstclient.WurstRenderLayers;
import net.wurstclient.events.CameraTransformViewBobbingListener;
import net.wurstclient.events.PacketInputListener;
import net.wurstclient.events.RenderListener;
import net.wurstclient.events.UpdateListener;
import net.wurstclient.hack.Hack;
import net.wurstclient.hacks.nukers.NukerMultiIdListSetting;
import net.wurstclient.settings.BlockSetting;
import net.wurstclient.settings.CheckboxSetting;
import net.wurstclient.settings.ChunkAreaSetting;
import net.wurstclient.settings.ColorSetting;
import net.wurstclient.settings.EnumSetting;
import net.wurstclient.settings.SliderSetting;
import net.wurstclient.settings.SliderSetting.ValueDisplay;
import net.wurstclient.util.BlockUtils;
import net.wurstclient.util.BlockVertexCompiler;
import net.wurstclient.util.ChatUtils;
import net.wurstclient.util.EasyVertexBuffer;
import net.wurstclient.util.RegionPos;
import net.wurstclient.util.RenderUtils;
import net.wurstclient.util.chunk.ChunkSearcher.Result;
import net.wurstclient.util.chunk.ChunkSearcherCoordinator;

@SearchTags({"Search", "block esp", "BlockFinder", "block finder", "OreESP",
	"ore esp", "XRay ESP"})
public final class BlockEspHack extends Hack implements UpdateListener,
	CameraTransformViewBobbingListener, RenderListener
{
	private final EnumSetting<Mode> mode = new EnumSetting<>("Mode",
		"\u00a7lID\u00a7r mode only highlights the selected block type.\n\n"
			+ "\u00a7lMultiID\u00a7r mode highlights all of the block types in"
			+ " your MultiID List.",
		Mode.values(), Mode.ID);
	
	private final BlockSetting id =
		new BlockSetting("ID", "The type of block to highlight in ID mode.",
			"minecraft:diamond_ore", false);
	
	private final NukerMultiIdListSetting multiIdList =
		new NukerMultiIdListSetting(
			"The types of blocks to highlight in MultiID mode.");
	
	private final EnumSetting<Style> style = new EnumSetting<>("Style",
		"How the blocks are highlighted, in addition to the colored boxes.",
		Style.values(), Style.NONE);
	
	private final CheckboxSetting coloredBoxes =
		new CheckboxSetting("Colored boxes",
			"Fills the blocks with the same color as the tracers and outlines."
				+ " Also works when the style is set to \u00a7lNone\u00a7r or"
				+ " \u00a7lLines only\u00a7r.",
			true);
	
	private final SliderSetting opacity =
		new SliderSetting("Opacity", "How opaque the colored boxes should be.",
			0.5, 0.05, 1, 0.01, ValueDisplay.PERCENTAGE);
	
	private final CheckboxSetting rainbow = new CheckboxSetting("Rainbow",
		"Makes the colored boxes, outlines and tracers change color in sync."
			+ " When off, they use the color below.",
		true);
	
	private final ColorSetting color = new ColorSetting("Color",
		"The color of the colored boxes, outlines and tracers when"
			+ " \u00a7lRainbow\u00a7r is off.",
		Color.RED);
	
	private final ChunkAreaSetting area = new ChunkAreaSetting("Area",
		"The area around the player to search in.\n"
			+ "Higher values require a faster computer.");
	
	private final SliderSetting limit = new SliderSetting("Limit",
		"The maximum number of blocks to display.\n"
			+ "Higher values require a faster computer.",
		4, 3, 6, 1, ValueDisplay.LOGARITHMIC);
	private int prevLimit;
	private boolean notify;
	
	private final ChunkSearcherCoordinator coordinator =
		new ChunkSearcherCoordinator(area);
	
	// what the current query was made from
	private Mode lastMode;
	private Block lastId;
	private List<String> lastMultiIdList;
	
	private ForkJoinPool forkJoinPool;
	private ForkJoinTask<Highlights> buildTask;
	private int builtVersion;
	
	private EasyVertexBuffer vertexBuffer;
	private RegionPos bufferRegion;
	private List<AABB> boxes = List.of();
	private List<Vec3> tracerEnds = List.of();
	
	public BlockEspHack()
	{
		super("BlockESP");
		setCategory(Category.RENDER);
		addSetting(mode);
		addSetting(id);
		addSetting(multiIdList);
		addSetting(style);
		addSetting(coloredBoxes);
		addSetting(opacity);
		addSetting(rainbow);
		addSetting(color);
		addSetting(area);
		addSetting(limit);
	}
	
	@Override
	public String getRenderName()
	{
		return getName() + switch(mode.getSelected())
		{
			case ID -> " [ID:" + id.getShortBlockName() + "]";
			case MULTI_ID -> " [MultiID:" + multiIdList.size() + "]";
		};
	}
	
	@Override
	protected void onEnable()
	{
		prevLimit = limit.getValueI();
		notify = true;
		forkJoinPool = new ForkJoinPool();
		updateQuery();
		builtVersion = coordinator.getMatchesVersion() - 1;
		
		EVENTS.add(UpdateListener.class, this);
		EVENTS.add(PacketInputListener.class, coordinator);
		EVENTS.add(CameraTransformViewBobbingListener.class, this);
		EVENTS.add(RenderListener.class, this);
	}
	
	@Override
	protected void onDisable()
	{
		EVENTS.remove(UpdateListener.class, this);
		EVENTS.remove(PacketInputListener.class, coordinator);
		EVENTS.remove(CameraTransformViewBobbingListener.class, this);
		EVENTS.remove(RenderListener.class, this);
		
		cancelBuild();
		coordinator.reset();
		forkJoinPool.shutdownNow();
		clearHighlights();
	}
	
	@Override
	public void onUpdate()
	{
		if(hasQueryChanged())
		{
			updateQuery();
			cancelBuild();
			clearHighlights();
		}
		
		coordinator.update();
		
		if(limit.getValueI() != prevLimit)
		{
			prevLimit = limit.getValueI();
			notify = true;
			builtVersion = coordinator.getMatchesVersion() - 1;
		}
		
		// show what has been found so far, without waiting for all chunks
		if(buildTask != null)
		{
			if(!buildTask.isDone())
				return;
			
			setHighlights(buildTask.join());
			buildTask = null;
		}
		
		int version = coordinator.getMatchesVersion();
		if(version != builtVersion)
			startBuild(version);
	}
	
	@Override
	public void onCameraTransformViewBobbing(
		CameraTransformViewBobbingEvent event)
	{
		if(style.getSelected().lines)
			event.cancel();
	}
	
	@Override
	public void onRender(PoseStack matrixStack, float partialTicks)
	{
		float[] rgb = rainbow.isChecked() ? RenderUtils.getRainbowColor()
			: color.getColorF();
		
		if(coloredBoxes.isChecked() && vertexBuffer != null
			&& bufferRegion != null)
		{
			matrixStack.pushPose();
			RenderUtils.applyRegionalRenderOffset(matrixStack, bufferRegion);
			vertexBuffer.draw(matrixStack, WurstRenderLayers.ESP_QUADS, rgb,
				opacity.getValueF());
			matrixStack.popPose();
		}
		
		int lineColor = RenderUtils.toIntColor(rgb, 0.5F);
		
		if(style.getSelected().boxes && !boxes.isEmpty())
			RenderUtils.drawOutlinedBoxes(matrixStack, boxes, lineColor, false);
		
		if(style.getSelected().lines && !tracerEnds.isEmpty())
			RenderUtils.drawTracers(matrixStack, partialTicks, tracerEnds,
				lineColor, false);
	}
	
	private boolean hasQueryChanged()
	{
		if(mode.getSelected() != lastMode)
			return true;
		
		return switch(mode.getSelected())
		{
			case ID -> id.getBlock() != lastId;
			case MULTI_ID -> !multiIdList.getBlockNames()
				.equals(lastMultiIdList);
		};
	}
	
	private void updateQuery()
	{
		lastMode = mode.getSelected();
		lastId = id.getBlock();
		lastMultiIdList = new ArrayList<>(multiIdList.getBlockNames());
		
		BiPredicate<BlockPos, BlockState> query = switch(lastMode)
		{
			case ID ->
			{
				Block block = lastId;
				yield (pos, state) -> state.getBlock() == block;
			}
			
			// a set of blocks is much faster than looking up block names
			case MULTI_ID ->
			{
				Set<Block> blocks =
					Collections.newSetFromMap(new IdentityHashMap<>());
				for(String name : lastMultiIdList)
					blocks.add(BlockUtils.getBlockFromName(name));
				yield (pos, state) -> blocks.contains(state.getBlock());
			}
		};
		
		coordinator.setQuery(query);
		notify = true;
	}
	
	private void startBuild(int version)
	{
		builtVersion = version;
		BlockPos eyesPos = BlockPos.containing(MC.player.getEyePosition());
		int maxBlocks = limit.getValueLog();
		List<List<Result>> matches = coordinator.getMatchLists();
		
		buildTask = forkJoinPool
			.submit(() -> buildHighlights(matches, eyesPos, maxBlocks));
	}
	
	/**
	 * Finds the closest matches and compiles everything needed to draw them.
	 * Runs on a background thread.
	 */
	private static Highlights buildHighlights(List<List<Result>> matches,
		BlockPos eyesPos, int maxBlocks)
	{
		// keeps only the closest blocks, without sorting all of them
		Comparator<BlockPos> furthestFirst =
			Comparator.comparingInt(pos -> -pos.distManhattan(eyesPos));
		PriorityQueue<BlockPos> closest =
			new PriorityQueue<>(maxBlocks + 1, furthestFirst);
		int total = 0;
		
		for(List<Result> list : matches)
			for(Result result : list)
			{
				total++;
				BlockPos pos = result.pos();
				if(closest.size() < maxBlocks)
					closest.add(pos);
				else if(pos.distManhattan(eyesPos) < closest.peek()
					.distManhattan(eyesPos))
				{
					closest.poll();
					closest.add(pos);
				}
			}
		
		HashSet<BlockPos> blocks = new HashSet<>(closest);
		ArrayList<int[]> vertices = BlockVertexCompiler.compile(blocks);
		List<AABB> boxes = blocks.stream().map(AABB::new).toList();
		List<Vec3> tracerEnds = blocks.stream().map(Vec3::atCenterOf).toList();
		
		return new Highlights(vertices, boxes, tracerEnds, total > maxBlocks);
	}
	
	private void setHighlights(Highlights highlights)
	{
		if(!highlights.limited())
			notify = true;
		else if(notify)
		{
			ChatUtils.warning("BlockESP found \u00a7lA LOT\u00a7r of blocks!"
				+ " To prevent lag, it will only show the closest \u00a76"
				+ limit.getValueString() + "\u00a7r results.");
			notify = false;
		}
		
		RegionPos region = RenderUtils.getCameraRegion();
		if(vertexBuffer != null)
			vertexBuffer.close();
		
		vertexBuffer = EasyVertexBuffer.createAndUpload(PrimitiveTopology.QUADS,
			DefaultVertexFormat.POSITION_COLOR, buffer -> {
				for(int[] vertex : highlights.vertices())
					buffer.addVertex(vertex[0] - region.x(), vertex[1],
						vertex[2] - region.z()).setColor(0xFFFFFFFF);
			});
		
		bufferRegion = region;
		boxes = highlights.boxes();
		tracerEnds = highlights.tracerEnds();
	}
	
	private void cancelBuild()
	{
		if(buildTask != null)
			buildTask.cancel(true);
		buildTask = null;
		builtVersion = coordinator.getMatchesVersion() - 1;
	}
	
	private void clearHighlights()
	{
		if(vertexBuffer != null)
			vertexBuffer.close();
		vertexBuffer = null;
		bufferRegion = null;
		boxes = List.of();
		tracerEnds = List.of();
	}
	
	private record Highlights(ArrayList<int[]> vertices, List<AABB> boxes,
		List<Vec3> tracerEnds, boolean limited)
	{}
	
	private enum Mode
	{
		ID("ID"),
		MULTI_ID("MultiID");
		
		private final String name;
		
		private Mode(String name)
		{
			this.name = name;
		}
		
		@Override
		public String toString()
		{
			return name;
		}
	}
	
	private enum Style
	{
		NONE("None", false, false),
		LINES("Lines only", false, true),
		BOXES("Boxes only", true, false),
		LINES_AND_BOXES("Lines and boxes", true, true);
		
		private final String name;
		private final boolean boxes;
		private final boolean lines;
		
		private Style(String name, boolean boxes, boolean lines)
		{
			this.name = name;
			this.boxes = boxes;
			this.lines = lines;
		}
		
		@Override
		public String toString()
		{
			return name;
		}
	}
}
