/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks;

import java.util.Random;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

import com.mojang.blaze3d.vertex.PoseStack;

import io.netty.channel.EventLoop;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.Connection;
import net.minecraft.network.PacketListener;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.common.ClientboundDisconnectPacket;
import net.minecraft.network.protocol.game.ClientboundBundlePacket;
import net.minecraft.network.protocol.game.ClientboundEntityPositionSyncPacket;
import net.minecraft.network.protocol.game.ClientboundLoginPacket;
import net.minecraft.network.protocol.game.ClientboundMoveEntityPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.network.protocol.game.ClientboundRespawnPacket;
import net.minecraft.network.protocol.game.ClientboundStartConfigurationPacket;
import net.minecraft.network.protocol.game.ClientboundTeleportEntityPacket;
import net.minecraft.network.protocol.game.VecDeltaCodec;
import net.minecraft.server.RunningOnDifferentThreadException;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.PositionMoveRotation;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.wurstclient.Category;
import net.wurstclient.SearchTags;
import net.wurstclient.events.PacketInputListener;
import net.wurstclient.events.PlayerAttacksEntityListener;
import net.wurstclient.events.RenderListener;
import net.wurstclient.events.UpdateListener;
import net.wurstclient.hack.Hack;
import net.wurstclient.mixin.ClientboundMoveEntityPacketAccessor;
import net.wurstclient.mixin.ConnectionAccessor;
import net.wurstclient.settings.CheckboxSetting;
import net.wurstclient.settings.SliderSetting;
import net.wurstclient.settings.SliderSetting.ValueDisplay;
import net.wurstclient.util.RenderUtils;

/**
 * Delays all incoming packets while you fight something, so it shows up
 * where it was a moment ago. To the server (and to Grim, whose reach check
 * follows the transaction pings that are delayed too), this is just lag.
 *
 * Packets are held and released on the connection's netty thread, in the
 * same order as they arrived. Released packets are handled the same way
 * vanilla handles them, which schedules them onto the main thread.
 */
@SearchTags({"back track", "FakeLag", "fake lag"})
public final class BacktrackHack extends Hack implements PacketInputListener,
	PlayerAttacksEntityListener, UpdateListener, RenderListener
{
	private final SliderSetting minDelay = new SliderSetting("Min delay",
		"How long to delay the target's position, at least.", 100, 10, 1000, 10,
		ValueDisplay.INTEGER.withSuffix("ms"));
	
	private final SliderSetting maxDelay = new SliderSetting("Max delay",
		"How long to delay the target's position, at most.", 200, 10, 1000, 10,
		ValueDisplay.INTEGER.withSuffix("ms"));
	
	private final SliderSetting range =
		new SliderSetting("Range", "Only backtracks targets this close to you.",
			5, 3, 8, 0.1, ValueDisplay.DECIMAL.withSuffix(" blocks"));
	
	private final CheckboxSetting smart = new CheckboxSetting("Smart",
		"Only delays while it brings the target closer to you.", true);
	
	private final CheckboxSetting showRealPos = new CheckboxSetting(
		"Show real position", "Shows where the target really is.", true);
	
	// stops backtracking once you haven't attacked for this long
	private static final long ATTACK_TIMEOUT_MS = 1000;
	
	private final ConcurrentLinkedQueue<Held> queue =
		new ConcurrentLinkedQueue<>();
	private final Random random = new Random();
	
	// written on the main thread, read on the netty thread
	private volatile boolean holding;
	private volatile long delayNanos;
	private volatile Connection connection;
	private volatile ClientPacketListener listener;
	
	private LivingEntity target;
	private long lastAttackTime;
	private boolean listening;
	
	public BacktrackHack()
	{
		super("Backtrack");
		setCategory(Category.COMBAT);
		addSetting(minDelay);
		addSetting(maxDelay);
		addSetting(range);
		addSetting(smart);
		addSetting(showRealPos);
	}
	
	@Override
	protected void onEnable()
	{
		target = null;
		if(!listening)
		{
			EVENTS.add(PacketInputListener.class, this);
			listening = true;
		}
		EVENTS.add(PlayerAttacksEntityListener.class, this);
		EVENTS.add(UpdateListener.class, this);
		EVENTS.add(RenderListener.class, this);
	}
	
	@Override
	protected void onDisable()
	{
		EVENTS.remove(PlayerAttacksEntityListener.class, this);
		EVENTS.remove(UpdateListener.class, this);
		EVENTS.remove(RenderListener.class, this);
		stop();
		
		// keep listening until the queue is empty, so nothing overtakes it
		runOnNettyThread(() -> {
			releaseAll();
			MC.execute(this::stopListening);
		});
	}
	
	private void stopListening()
	{
		if(isEnabled() || !listening)
			return;
		
		EVENTS.remove(PacketInputListener.class, this);
		listening = false;
	}
	
	@Override
	public void onPlayerAttacksEntity(Entity entity)
	{
		if(!(entity instanceof LivingEntity living)
			|| MC.player.distanceTo(living) > range.getValue())
			return;
		
		lastAttackTime = System.currentTimeMillis();
		if(target == living)
			return;
		
		stop();
		target = living;
		listener = MC.getConnection();
		connection = listener.getConnection();
		
		// typed-in values can make min > max
		int min = minDelay.getValueI();
		int max = Math.max(maxDelay.getValueI(), min);
		delayNanos =
			TimeUnit.MILLISECONDS.toNanos(min + random.nextInt(max - min + 1));
	}
	
	@Override
	public void onUpdate()
	{
		if(target == null)
			return;
		
		Vec3 realPos = getRealPosition();
		boolean keepGoing = MC.getConnection() == listener && target.isAlive()
			&& !target.isRemoved() && target.level() == MC.level
			&& MC.player.position().distanceTo(realPos) <= range.getValue()
			&& System.currentTimeMillis() - lastAttackTime <= ATTACK_TIMEOUT_MS;
		
		if(!keepGoing)
		{
			stop();
			return;
		}
		
		// only worth it while the target is really further away than shown
		Vec3 pos = MC.player.position();
		setHolding(!smart.isChecked() || pos.distanceToSqr(realPos) > pos
			.distanceToSqr(target.position()));
	}
	
	@Override
	public void onRender(PoseStack matrixStack, float partialTicks)
	{
		if(!showRealPos.isChecked() || !holding || target == null)
			return;
		
		Vec3 realPos = getRealPosition();
		AABB box =
			target.getBoundingBox().move(realPos.subtract(target.position()));
		RenderUtils.drawOutlinedBox(matrixStack, box, 0xC0FF0000, false);
	}
	
	private void stop()
	{
		target = null;
		setHolding(false);
	}
	
	private void setHolding(boolean holding)
	{
		if(this.holding == holding)
			return;
		
		this.holding = holding;
		if(!holding)
			runOnNettyThread(this::releaseAll);
	}
	
	/**
	 * Runs on the netty thread for every packet that the client receives.
	 */
	@Override
	public void onReceivedPacket(PacketInputEvent event)
	{
		Packet<?> packet = event.getPacket();
		
		// the integrated server's connection fires this too
		if(packet.type().flow() != PacketFlow.CLIENTBOUND)
			return;
		
		if(!holding && queue.isEmpty())
			return;
			
		// Teleports must come after everything before them (see
		// AntiKnockback's Delay mode), and the others start a new world or
		// connection.
		if(!holding || packet instanceof ClientboundPlayerPositionPacket
			|| packet instanceof ClientboundLoginPacket
			|| packet instanceof ClientboundRespawnPacket
			|| packet instanceof ClientboundStartConfigurationPacket
			|| packet instanceof ClientboundDisconnectPacket)
		{
			releaseAll();
			return;
		}
		
		EventLoop eventLoop = getEventLoop();
		if(eventLoop == null)
			return;
		
		event.cancel();
		long delay = delayNanos;
		queue.add(new Held(packet, System.nanoTime() + delay));
		eventLoop.schedule(this::releaseDue, delay, TimeUnit.NANOSECONDS);
	}
	
	private void releaseDue()
	{
		long now = System.nanoTime();
		Held next;
		while((next = queue.peek()) != null
			&& (!holding || next.releaseTime() <= now))
		{
			queue.poll();
			handle(next.packet());
		}
	}
	
	private void releaseAll()
	{
		Held next;
		while((next = queue.poll()) != null)
			handle(next.packet());
	}
	
	/**
	 * Handles the packet like Connection.channelRead0() would have.
	 */
	private void handle(Packet<?> packet)
	{
		// drop packets from an old connection or protocol
		Connection connection = this.connection;
		ClientPacketListener listener = this.listener;
		if(connection == null || !connection.isConnected()
			|| connection.getPacketListener() != listener)
			return;
		
		try
		{
			genericsFtw(packet, listener);
			
		}catch(RunningOnDifferentThreadException | RejectedExecutionException
			| ClassCastException e)
		{
			// same as vanilla, which handles the packet on the main thread
		}
	}
	
	@SuppressWarnings("unchecked")
	private static <T extends PacketListener> void genericsFtw(Packet<T> packet,
		PacketListener listener)
	{
		packet.handle((T)listener);
	}
	
	private void runOnNettyThread(Runnable task)
	{
		EventLoop eventLoop = getEventLoop();
		if(eventLoop == null)
		{
			queue.clear();
			task.run();
			return;
		}
		
		eventLoop.execute(task);
	}
	
	private EventLoop getEventLoop()
	{
		Connection connection = this.connection;
		if(connection == null)
			return null;
		
		var channel = ((ConnectionAccessor)connection).wurst_getChannel();
		return channel == null ? null : channel.eventLoop();
	}
	
	/**
	 * Where the server says the target is, by applying the target's queued
	 * movement packets to its current position.
	 */
	private Vec3 getRealPosition()
	{
		VecDeltaCodec codec = new VecDeltaCodec();
		Vec3 pos = target.getPositionCodec().getBase();
		codec.setBase(pos);
		
		for(Held held : queue)
			pos = applyMovement(held.packet(), target.getId(), codec, pos);
		
		return pos;
	}
	
	private Vec3 applyMovement(Packet<?> packet, int id, VecDeltaCodec codec,
		Vec3 pos)
	{
		if(packet instanceof ClientboundBundlePacket bundle)
		{
			for(Packet<?> subPacket : bundle.subPackets())
				pos = applyMovement(subPacket, id, codec, pos);
			return pos;
		}
		
		// like ClientPacketListener.handleMoveEntity() and the others
		if(packet instanceof ClientboundMoveEntityPacket move
			&& ((ClientboundMoveEntityPacketAccessor)move)
				.wurst_getEntityId() == id
			&& move.hasPosition())
			pos = codec.decode(move.getXa(), move.getYa(), move.getZa());
		else if(packet instanceof ClientboundEntityPositionSyncPacket sync
			&& sync.id() == id)
			pos = sync.values().position();
		else if(packet instanceof ClientboundTeleportEntityPacket teleport
			&& teleport.id() == id)
			pos = PositionMoveRotation.calculateAbsolute(
				new PositionMoveRotation(pos, Vec3.ZERO, 0, 0),
				teleport.change(), teleport.relatives()).position();
		else
			return pos;
		
		codec.setBase(pos);
		return pos;
	}
	
	private record Held(Packet<?> packet, long releaseTime)
	{}
}
