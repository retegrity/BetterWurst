/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.wurstclient.Category;
import net.wurstclient.RotationFaker;
import net.wurstclient.SearchTags;
import net.wurstclient.WurstClient;
import net.wurstclient.ai.PathFinder;
import net.wurstclient.ai.PathPos;
import net.wurstclient.ai.PathProcessor;
import net.wurstclient.ai.SmoothWalkPathProcessor;
import net.wurstclient.commands.PathCmd;
import net.wurstclient.events.RenderListener;
import net.wurstclient.events.UpdateListener;
import net.wurstclient.hack.DontSaveState;
import net.wurstclient.hack.Hack;
import net.wurstclient.hacks.treebot.Tree;
import net.wurstclient.hacks.treebot.TreeBotUtils;
import net.wurstclient.settings.EnumSetting;
import net.wurstclient.settings.FaceTargetSetting;
import net.wurstclient.settings.FaceTargetSetting.FaceTarget;
import net.wurstclient.settings.SliderSetting;
import net.wurstclient.settings.SliderSetting.ValueDisplay;
import net.wurstclient.settings.SwingHandSetting;
import net.wurstclient.settings.SwingHandSetting.SwingHand;
import net.wurstclient.util.BlockBreaker;
import net.wurstclient.util.BlockBreaker.BlockBreakingParams;
import net.wurstclient.util.BlockUtils;
import net.wurstclient.util.OverlayRenderer;
import net.wurstclient.util.Rotation;
import net.wurstclient.util.RotationUtils;

@SearchTags({"tree bot"})
@DontSaveState
public final class TreeBotHack extends Hack
	implements UpdateListener, RenderListener
{
	private final SliderSetting range = new SliderSetting("Range",
		"How far TreeBot will reach to break blocks.", 4.5, 1, 6, 0.05,
		ValueDisplay.DECIMAL);
	
	private final EnumSetting<Mode> mode = new EnumSetting<>(
		"Rotations/Pathfinding",
		"\u00a7lSmooth\u00a7r turns smoothly like a human, both when breaking"
			+ " blocks and when walking, and walks straight towards the tree"
			+ " instead of from block to block. Blocks are only broken once the"
			+ " rotation that the server sees is actually looking at them.\n\n"
			+ "\u00a7lOld\u00a7r snaps to each block and walks from block"
			+ " center to block center, like TreeBot always did. Only this mode"
			+ " uses the Face target setting.",
		Mode.values(), Mode.SMOOTH);
	
	private final FaceTargetSetting faceTarget =
		FaceTargetSetting.withoutPacketSpam(this, FaceTarget.SERVER);
	
	private final SwingHandSetting swingHand =
		new SwingHandSetting(this, SwingHand.SERVER);
	
	private TreeFinder treeFinder;
	private AngleFinder angleFinder;
	private TreeBotPathProcessor processor;
	private Tree tree;
	
	private BlockPos currentBlock;
	private final OverlayRenderer overlay = new OverlayRenderer();
	
	// after a tree is chopped, waits for its logs to be picked up
	private static final int COLLECT_TICKS = 100;
	private BlockPos collectCenter;
	private int collectTicks;
	
	// where on the block Smooth mode is aiming
	private BlockPos aimBlock;
	private Vec3 aimPoint;
	
	public TreeBotHack()
	{
		super("TreeBot");
		setCategory(Category.BLOCKS);
		addSetting(range);
		addSetting(mode);
		addSetting(faceTarget);
		addSetting(swingHand);
	}
	
	@Override
	public String getRenderName()
	{
		if(treeFinder != null && !treeFinder.isDone() && !treeFinder.isFailed())
			return getName() + " [Searching]";
		
		if(processor != null && !processor.isDone())
			return getName() + " [Going]";
		
		if(tree != null && !tree.getLogs().isEmpty())
			return getName() + " [Chopping]";
		
		if(collectCenter != null)
			return getName() + " [Collecting]";
		
		return getName();
	}
	
	@Override
	protected void onEnable()
	{
		treeFinder = new TreeFinder();
		
		EVENTS.add(UpdateListener.class, this);
		EVENTS.add(RenderListener.class, this);
	}
	
	@Override
	protected void onDisable()
	{
		EVENTS.remove(UpdateListener.class, this);
		EVENTS.remove(RenderListener.class, this);
		
		PathProcessor.releaseControls();
		treeFinder = null;
		angleFinder = null;
		processor = null;
		tree = null;
		collectCenter = null;
		aimBlock = null;
		aimPoint = null;
		
		if(mode.getSelected() == Mode.SMOOTH)
			WURST.getRotationFaker().releaseManagedRotation();
		
		if(currentBlock != null)
		{
			MC.gameMode.isDestroying = true;
			MC.gameMode.stopDestroyBlock();
			currentBlock = null;
		}
		
		overlay.resetProgress();
	}
	
	@Override
	public void onUpdate()
	{
		if(collectCenter != null)
		{
			collectLogs();
			return;
		}
		
		if(treeFinder != null)
		{
			goToTree();
			return;
		}
		
		if(tree == null)
		{
			treeFinder = new TreeFinder();
			return;
		}
		
		tree.getLogs().removeIf(Predicate.not(TreeBotUtils::isLog));
		
		if(tree.getLogs().isEmpty())
		{
			// wait for the logs to be picked up before moving on
			collectCenter = tree.getStump();
			collectTicks = COLLECT_TICKS;
			tree = null;
			return;
		}
		
		if(angleFinder != null)
		{
			goToAngle();
			return;
		}
		
		if(breakBlocks(tree.getLogs()))
			return;
		
		if(angleFinder == null)
			angleFinder = new AngleFinder();
	}
	
	private void goToTree()
	{
		// find path
		if(!treeFinder.isDoneOrFailed())
		{
			PathProcessor.lockControls();
			treeFinder.findPath();
			return;
		}
		
		// process path
		if(processor != null && !processor.isDone())
		{
			processor.goToGoal();
			return;
		}
		
		PathProcessor.releaseControls();
		treeFinder = null;
	}
	
	private void goToAngle()
	{
		// find path
		if(!angleFinder.isDone() && !angleFinder.isFailed())
		{
			PathProcessor.lockControls();
			angleFinder.findPath();
			return;
		}
		
		// process path
		if(processor != null && !processor.isDone())
		{
			processor.goToGoal();
			return;
		}
		
		PathProcessor.releaseControls();
		angleFinder = null;
	}
	
	private boolean breakBlocks(ArrayList<BlockPos> blocks)
	{
		for(BlockPos pos : blocks)
			if(breakBlock(pos))
			{
				currentBlock = pos;
				return true;
			}
		
		return false;
	}
	
	private boolean breakBlock(BlockPos pos)
	{
		BlockBreakingParams params = BlockBreaker.getBlockBreakingParams(pos);
		if(params == null || !params.lineOfSight()
			|| params.distanceSq() > range.getValueSq())
			return false;
		
		// select tool
		WURST.getHax().autoToolHack.equipBestTool(pos, false, true, 0);
		
		if(mode.getSelected() == Mode.SMOOTH)
		{
			breakBlockSmoothly(pos, params);
			return true;
		}
		
		// face block
		faceTarget.face(params.hitVec());
		
		// damage block and swing hand
		if(MC.gameMode.continueDestroyBlock(pos, params.side()))
			swingHand.swing(InteractionHand.MAIN_HAND);
		
		// update progress
		overlay.updateProgress();
		
		return true;
	}
	
	/**
	 * Turns towards the block like a human and only damages it once the
	 * rotation that the server got with the last movement packet is actually
	 * looking at it. So it never breaks blocks it isn't looking at, or
	 * through other blocks.
	 */
	private void breakBlockSmoothly(BlockPos pos, BlockBreakingParams params)
	{
		RotationFaker rotationFaker = WURST.getRotationFaker();
		rotationFaker.faceVectorHumanly(getAimPoint(pos, params), 30, 12, 5,
			true);
		
		BlockHitResult hit =
			raycast(rotationFaker.getSentYaw(), rotationFaker.getSentPitch());
		if(hit == null)
			return;
			
		// a plant (grass, flowers, etc.) is in the way, so break it, like a
		// real click would
		BlockPos hitPos = hit.getBlockPos();
		if(!pos.equals(hitPos))
		{
			if(isInstantlyBreakable(hitPos)
				&& MC.gameMode.continueDestroyBlock(hitPos, hit.getDirection()))
				swingHand.swing(InteractionHand.MAIN_HAND);
			return;
		}
		
		if(MC.gameMode.continueDestroyBlock(pos, hit.getDirection()))
			swingHand.swing(InteractionHand.MAIN_HAND);
		
		overlay.updateProgress();
	}
	
	/**
	 * Where on the block to aim. Plants like grass don't block the line of
	 * sight that BlockBreaker checks, but they do block the crosshair, so
	 * this looks for a point on the side that the crosshair can actually
	 * reach, as close to the middle of it as possible. The point is kept
	 * while it still works, so the aim doesn't jump around.
	 */
	private Vec3 getAimPoint(BlockPos pos, BlockBreakingParams params)
	{
		if(pos.equals(aimBlock) && aimPoint != null && canSee(pos, aimPoint))
			return aimPoint;
		
		aimBlock = pos;
		aimPoint = findVisiblePoint(pos, params);
		return aimPoint;
	}
	
	private Vec3 findVisiblePoint(BlockPos pos, BlockBreakingParams params)
	{
		Vec3 center = params.hitVec();
		Direction.Axis axis = params.side().getAxis();
		Vec3 u =
			axis == Direction.Axis.X ? new Vec3(0, 1, 0) : new Vec3(1, 0, 0);
		Vec3 v =
			axis == Direction.Axis.Z ? new Vec3(0, 1, 0) : new Vec3(0, 0, 1);
		
		Vec3 best = null;
		int bestDistance = Integer.MAX_VALUE;
		for(int a = -3; a <= 3; a++)
			for(int b = -3; b <= 3; b++)
			{
				int distance = a * a + b * b;
				if(distance >= bestDistance)
					continue;
				
				Vec3 point =
					center.add(u.scale(a * 0.12)).add(v.scale(b * 0.12));
				if(!canSee(pos, point))
					continue;
				
				best = point;
				bestDistance = distance;
			}
		
		return best != null ? best : center;
	}
	
	/**
	 * Whether the crosshair would hit the block when looking at this point.
	 */
	private boolean canSee(BlockPos pos, Vec3 point)
	{
		Vec3 eyes = RotationUtils.getEyesPos();
		Vec3 end = point.add(point.subtract(eyes).normalize().scale(0.1));
		BlockHitResult hit = MC.level.clip(new ClipContext(eyes, end,
			ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, MC.player));
		return hit.getType() == HitResult.Type.BLOCK
			&& pos.equals(hit.getBlockPos());
	}
	
	private boolean isInstantlyBreakable(BlockPos pos)
	{
		return BlockUtils.getState(pos).getDestroySpeed(MC.level, pos) == 0;
	}
	
	private BlockHitResult raycast(float yaw, float pitch)
	{
		Vec3 eyes = RotationUtils.getEyesPos();
		Vec3 end = eyes
			.add(new Rotation(yaw, pitch).toLookVec().scale(range.getValue()));
		BlockHitResult hit = MC.level.clip(new ClipContext(eyes, end,
			ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, MC.player));
		return hit.getType() == HitResult.Type.BLOCK ? hit : null;
	}
	
	/**
	 * Walks to the logs that dropped from the tree, so they are picked up.
	 * Gives up after a few seconds, e.g. if a log landed somewhere it can't
	 * get to.
	 */
	private void collectLogs()
	{
		ItemEntity item = collectTicks-- > 0 ? findDroppedLog() : null;
		if(item == null)
		{
			PathProcessor.releaseControls();
			collectCenter = null;
			return;
		}
		
		// no room for it in the inventory, so don't wait for it
		LocalPlayer player = MC.player;
		Inventory inventory = player.getInventory();
		if(inventory.getFreeSlot() == -1
			&& inventory.getSlotWithRemainingSpace(item.getItem()) == -1)
		{
			collectTicks = 0;
			return;
		}
		
		PathProcessor.lockControls();
		
		// close enough, it gets picked up as soon as it can be
		if(player.getBoundingBox().inflate(1, 0.5, 1)
			.intersects(item.getBoundingBox()))
			return;
		
		if(mode.getSelected() == Mode.SMOOTH)
		{
			float yawLeft =
				SmoothWalkPathProcessor.turnCameraTowards(item.position());
			if(Math.abs(yawLeft) > 45)
				return;
			
		}else
			WURST.getRotationFaker()
				.faceVectorClientIgnorePitch(item.position());
		
		MC.options.keyUp.setDown(true);
		if(player.horizontalCollision && player.onGround())
			MC.options.keyJump.setDown(true);
	}
	
	private ItemEntity findDroppedLog()
	{
		AABB area = new AABB(collectCenter).inflate(6, 4, 6);
		return MC.level
			.getEntitiesOfClass(ItemEntity.class, area,
				e -> e.isAlive() && e.getItem().is(ItemTags.LOGS))
			.stream()
			.min(Comparator.comparingDouble(e -> e.distanceToSqr(MC.player)))
			.orElse(null);
	}
	
	@Override
	public void onRender(PoseStack matrixStack, float partialTicks)
	{
		PathCmd pathCmd = WURST.getCmds().pathCmd;
		
		if(treeFinder != null)
			treeFinder.renderPath(matrixStack, pathCmd.isDebugMode(),
				pathCmd.isDepthTest());
		
		if(angleFinder != null)
			angleFinder.renderPath(matrixStack, pathCmd.isDebugMode(),
				pathCmd.isDepthTest());
		
		if(tree != null)
			tree.draw(matrixStack);
		
		overlay.render(matrixStack, partialTicks, currentBlock);
	}
	
	private ArrayList<BlockPos> getNeighbors(BlockPos pos)
	{
		return BlockUtils
			.getAllInBoxStream(pos.offset(-1, -1, -1), pos.offset(1, 1, 1))
			.filter(TreeBotUtils::isLog)
			.collect(Collectors.toCollection(ArrayList::new));
	}
	
	private abstract class TreeBotPathFinder extends PathFinder
	{
		public TreeBotPathFinder(BlockPos goal)
		{
			super(goal);
		}
		
		public TreeBotPathFinder(TreeBotPathFinder pathFinder)
		{
			super(pathFinder);
		}
		
		public void findPath()
		{
			think();
			
			if(isDoneOrFailed())
			{
				// set processor
				formatPath();
				processor = new TreeBotPathProcessor(this);
			}
		}
		
		public boolean isDoneOrFailed()
		{
			return isDone() || isFailed();
		}
		
		public abstract void reset();
	}
	
	private class TreeBotPathProcessor
	{
		private final TreeBotPathFinder pathFinder;
		private final PathProcessor processor;
		
		public TreeBotPathProcessor(TreeBotPathFinder pathFinder)
		{
			this.pathFinder = pathFinder;
			processor = mode.getSelected() == Mode.SMOOTH
				? pathFinder.getSmoothProcessor() : pathFinder.getProcessor();
		}
		
		public void goToGoal()
		{
			if(!pathFinder.isPathStillValid(processor.getIndex())
				|| processor.getTicksOffPath() > 20)
			{
				pathFinder.reset();
				return;
			}
			
			if(processor.canBreakBlocks() && breakBlocks(getLeavesOnPath()))
				return;
			
			processor.process();
		}
		
		private ArrayList<BlockPos> getLeavesOnPath()
		{
			List<PathPos> path = pathFinder.getPath();
			path = path.subList(processor.getIndex(), path.size());
			
			return path.stream().flatMap(pos -> Stream.of(pos, pos.above()))
				.distinct().filter(TreeBotUtils::isLeaves)
				.collect(Collectors.toCollection(ArrayList::new));
		}
		
		public final boolean isDone()
		{
			return processor.isDone();
		}
	}
	
	private class TreeFinder extends TreeBotPathFinder
	{
		public TreeFinder()
		{
			super(BlockPos.containing(WurstClient.MC.player.position()));
		}
		
		public TreeFinder(TreeBotPathFinder pathFinder)
		{
			super(pathFinder);
		}
		
		@Override
		protected boolean isMineable(BlockPos pos)
		{
			return TreeBotUtils.isLeaves(pos);
		}
		
		@Override
		protected boolean checkDone()
		{
			return done = isNextToTreeStump(current);
		}
		
		private boolean isNextToTreeStump(PathPos pos)
		{
			return isTreeStump(pos.north()) || isTreeStump(pos.east())
				|| isTreeStump(pos.south()) || isTreeStump(pos.west());
		}
		
		private boolean isTreeStump(BlockPos pos)
		{
			if(!TreeBotUtils.isLog(pos))
				return false;
			
			if(TreeBotUtils.isLog(pos.below()))
				return false;
			
			analyzeTree(pos);
			
			// ignore large trees (for now)
			if(tree.getLogs().size() > 6)
				return false;
			
			return true;
		}
		
		private void analyzeTree(BlockPos stump)
		{
			ArrayList<BlockPos> logs = new ArrayList<>(Arrays.asList(stump));
			ArrayDeque<BlockPos> queue = new ArrayDeque<>(Arrays.asList(stump));
			
			for(int i = 0; i < 1024; i++)
			{
				if(queue.isEmpty())
					break;
				
				BlockPos current = queue.pollFirst();
				
				for(BlockPos next : getNeighbors(current))
				{
					if(logs.contains(next))
						continue;
					
					logs.add(next);
					queue.add(next);
				}
			}
			
			tree = new Tree(stump, logs);
		}
		
		@Override
		public void reset()
		{
			treeFinder = new TreeFinder(treeFinder);
		}
	}
	
	private class AngleFinder extends TreeBotPathFinder
	{
		public AngleFinder()
		{
			super(BlockPos.containing(WurstClient.MC.player.position()));
			setThinkSpeed(512);
			setThinkTime(1);
		}
		
		public AngleFinder(TreeBotPathFinder pathFinder)
		{
			super(pathFinder);
		}
		
		@Override
		protected boolean isMineable(BlockPos pos)
		{
			return TreeBotUtils.isLeaves(pos);
		}
		
		@Override
		protected boolean checkDone()
		{
			return done = hasAngle(current);
		}
		
		private boolean hasAngle(PathPos pos)
		{
			double rangeSq = range.getValueSq();
			LocalPlayer player = WurstClient.MC.player;
			Vec3 eyes = Vec3.atBottomCenterOf(pos).add(0,
				player.getEyeHeight(player.getPose()), 0);
			
			for(BlockPos log : tree.getLogs())
			{
				BlockBreakingParams params =
					BlockBreaker.getBlockBreakingParams(eyes, log);
				
				if(params != null && params.lineOfSight()
					&& params.distanceSq() <= rangeSq)
					return true;
			}
			
			return false;
		}
		
		@Override
		public void reset()
		{
			angleFinder = new AngleFinder(angleFinder);
		}
	}
	
	private enum Mode
	{
		SMOOTH("Smooth"),
		OLD("Old");
		
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
}
