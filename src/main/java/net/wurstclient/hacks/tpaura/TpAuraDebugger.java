/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks.tpaura;

import java.util.ArrayDeque;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Locale;
import java.util.concurrent.ConcurrentLinkedQueue;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.minecraft.client.Minecraft;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundAnimatePacket;
import net.minecraft.network.protocol.game.ClientboundDamageEventPacket;
import net.minecraft.network.protocol.game.ClientboundEntityEventPacket;
import net.minecraft.network.protocol.game.ClientboundHurtAnimationPacket;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import net.wurstclient.WurstClient;
import net.wurstclient.events.PacketInputListener;
import net.wurstclient.util.ChatUtils;

/**
 * Logs every TP-Aura hit and what the server made of it, so that different
 * settings can be compared. The server plays an attack sound where it has the
 * attacker (crit, sweep, strong, weak or no damage) and sends a damage event
 * for every hit that hurts.
 */
public final class TpAuraDebugger implements PacketInputListener
{
	private static final Minecraft MC = WurstClient.MC;
	private static final Logger LOGGER =
		LoggerFactory.getLogger("TP-Aura debug");
	
	private static final long SUMMARY_INTERVAL_MS = 5000;
	private static final long RESPONSE_TIMEOUT_MS = 2000;
	private static final long TRACK_HEALTH_MS = 5000;
	
	// the attack sound is played where the server has the attacker
	private static final double MAX_SOUND_DISTANCE = 0.6;
	
	private final ConcurrentLinkedQueue<Packet<?>> received =
		new ConcurrentLinkedQueue<>();
	private final ArrayDeque<Hit> pending = new ArrayDeque<>();
	
	// damage events from this player that no hit has claimed yet
	private final HashMap<Integer, Integer> damageEvents = new HashMap<>();
	private final HashMap<Integer, TrackedHealth> health = new HashMap<>();
	
	private volatile boolean active;
	private int nextId;
	private Stats stats = new Stats();
	private long periodStart;
	
	/**
	 * Called every tick, before TP-Aura attacks.
	 */
	public void update(boolean enabled, String settings)
	{
		if(enabled && !active)
			reset();
		else if(!enabled && active)
			finish(settings);
		
		active = enabled;
		if(!enabled)
			return;
		
		long now = System.currentTimeMillis();
		processPackets(now);
		removeTimedOutHits(now);
		updateHealth(now);
		
		if(now - periodStart >= SUMMARY_INTERVAL_MS)
		{
			printSummary(now, settings);
			stats = new Stats();
			periodStart = now;
		}
	}
	
	/**
	 * Called when TP-Aura is turned off.
	 */
	public void stop(String settings)
	{
		if(active)
			finish(settings);
		
		active = false;
	}
	
	/**
	 * Called right before each attack.
	 */
	public void onHit(Entity target, Vec3 spot, Vec3 home, String info)
	{
		if(!active)
			return;
		
		long now = System.currentTimeMillis();
		Hit hit =
			new Hit(++nextId, target.getId(), getName(target), spot, home, now);
		pending.add(hit);
		stats.sent++;
		
		log("#%d sent to %s, %.1f blocks from home, %.1f from the spot it"
			+ " hits from: %s", hit.id, hit.targetName,
			home.distanceTo(target.position()),
			spot.distanceTo(target.position()), info);
		
		if(target instanceof LivingEntity living)
			health.computeIfAbsent(target.getId(),
				id -> new TrackedHealth(living)).until = now + TRACK_HEALTH_MS;
	}
	
	@Override
	public void onReceivedPacket(PacketInputEvent event)
	{
		if(!active)
			return;
		
		Packet<?> packet = event.getPacket();
		if(packet instanceof ClientboundSoundPacket
			|| packet instanceof ClientboundDamageEventPacket
			|| packet instanceof ClientboundAnimatePacket
			|| packet instanceof ClientboundHurtAnimationPacket
			|| packet instanceof ClientboundEntityEventPacket)
			received.add(packet);
	}
	
	private void processPackets(long now)
	{
		for(Packet<?> packet; (packet = received.poll()) != null;)
			if(packet instanceof ClientboundDamageEventPacket damage)
				onDamageEvent(damage);
			else if(packet instanceof ClientboundSoundPacket sound)
				onSound(sound, now);
			else if(packet instanceof ClientboundAnimatePacket animate)
				onAnimate(animate);
			else if(packet instanceof ClientboundHurtAnimationPacket hurt)
				onHurt(hurt.id(), "hurt animation");
			else if(packet instanceof ClientboundEntityEventPacket event)
				onEntityEvent(event);
	}
	
	/**
	 * Damage events don't say where they came from, but the server tells every
	 * client about all of them, so all are logged.
	 */
	private void onDamageEvent(ClientboundDamageEventPacket packet)
	{
		boolean mine = packet.sourceCauseId() == MC.player.getId();
		log("damage event on %s, caused by %s%s", getName(packet.entityId()),
			getName(packet.sourceCauseId()), mine ? " (you)" : "");
		if(!mine)
			return;
		
		damageEvents.merge(packet.entityId(), 1, Integer::sum);
		stats.damageEvents++;
	}
	
	/**
	 * The server sends this for every crit that it counts, unlike the particles
	 * that you see, which your own game makes when you attack.
	 */
	private void onAnimate(ClientboundAnimatePacket packet)
	{
		int action = packet.getAction();
		if(action != ClientboundAnimatePacket.CRITICAL_HIT
			&& action != ClientboundAnimatePacket.MAGIC_CRITICAL_HIT)
			return;
		
		stats.critAnimations++;
		log("server crit animation on %s", getName(packet.getId()));
	}
	
	private void onHurt(int entityId, String what)
	{
		if(!health.containsKey(entityId))
			return;
		
		stats.hurtAnimations++;
		log("%s on %s", what, getName(entityId));
	}
	
	private void onEntityEvent(ClientboundEntityEventPacket packet)
	{
		Entity entity = packet.getEntity(MC.level);
		if(entity == null)
			return;
		
		// 2 is hurt, 3 is death
		if(packet.getEventId() == 2)
			onHurt(entity.getId(), "hurt event");
		else if(packet.getEventId() == 3 && health.containsKey(entity.getId()))
			log("death event on %s", getName(entity));
	}
	
	private void onSound(ClientboundSoundPacket packet, long now)
	{
		Result result = Result.of(packet.getSound().value());
		if(result == null)
			return;
		
		Vec3 pos = new Vec3(packet.getX(), packet.getY(), packet.getZ());
		stats.heard(result);
		
		// the server plays it where it has the attacker, which is the spot the
		// hit was sent from, or still at home if it hadn't moved the player
		boolean atHome = false;
		Hit hit = pending.stream().filter(h -> h.isAtSpot(pos)).findFirst()
			.orElse(null);
		if(hit == null)
		{
			hit = pending.stream().filter(h -> h.isAtHome(pos)).findFirst()
				.orElse(null);
			atHome = hit != null;
		}
		
		if(hit == null)
		{
			Hit oldest = pending.peek();
			stats.unmatched++;
			log("%s sound at %.1f %.1f %.1f, %.1f blocks from you, %s",
				result.name, pos.x, pos.y, pos.z,
				pos.distanceTo(MC.player.position()),
				oldest == null ? "no hit waiting"
					: String.format(Locale.ROOT,
						"%.1f blocks from the spot of #%d and %.1f from home",
						pos.distanceTo(oldest.spot), oldest.id,
						pos.distanceTo(oldest.home)));
			return;
		}
		
		// the knockback sound comes before the one that says how it went
		if(result == Result.KNOCKBACK)
		{
			hit.knockback = true;
			return;
		}
		
		pending.remove(hit);
		stats.count(result);
		if(atHome)
			stats.atHome++;
		if(hit.knockback)
			stats.knockback++;
		
		// the damage event is sent before the sound
		boolean damaged = damageEvents.getOrDefault(hit.targetId, 0) > 0;
		if(damaged)
			damageEvents.merge(hit.targetId, -1, Integer::sum);
		
		log("#%d: %s%s%s, %s, after %d ms", hit.id, result.name,
			hit.knockback ? " + knockback" : "",
			atHome ? ", heard at home, not at the spot" : "",
			damaged ? "damage event" : "no damage event", now - hit.sentMs);
	}
	
	private void removeTimedOutHits(long now)
	{
		while(!pending.isEmpty()
			&& now - pending.peek().sentMs > RESPONSE_TIMEOUT_MS)
		{
			Hit hit = pending.poll();
			stats.noResponse++;
			log("#%d: no response from the server", hit.id);
		}
	}
	
	private void updateHealth(long now)
	{
		Iterator<TrackedHealth> iterator = health.values().iterator();
		while(iterator.hasNext())
		{
			TrackedHealth tracked = iterator.next();
			LivingEntity entity = tracked.entity;
			if(entity.isRemoved() || now > tracked.until)
			{
				iterator.remove();
				continue;
			}
			
			float newHealth = entity.getHealth();
			if(newHealth == tracked.health)
				continue;
			
			log("%s health %.1f -> %.1f", getName(entity), tracked.health,
				newHealth);
			if(newHealth < tracked.health)
				stats.healthLost += tracked.health - newHealth;
			
			tracked.health = newHealth;
		}
	}
	
	private void printSummary(long now, String settings)
	{
		if(stats.sent == 0 && stats.damageEvents == 0)
			return;
		
		double seconds = (now - periodStart) / 1000.0;
		String summary = String.format(Locale.ROOT,
			"Last %.1fs (%s): %d hits sent, %d crit, %d sweep, %d strong,"
				+ " %d weak, %d no damage, %d no response (%d heard at home),"
				+ " %d with knockback, %d damage events, %d crit animations,"
				+ " %d hurt animations, %.1f health lost (%.1f/s). All attack"
				+ " sounds heard: %s, %d of them not matching any hit",
			seconds, settings, stats.sent, stats.crit, stats.sweep,
			stats.strong, stats.weak, stats.noDamage, stats.noResponse,
			stats.atHome, stats.knockback, stats.damageEvents,
			stats.critAnimations, stats.hurtAnimations, stats.healthLost,
			stats.healthLost / seconds, stats.getHeard(), stats.unmatched);
		
		LOGGER.info(summary);
		ChatUtils.message("TP-Aura debug: " + summary);
	}
	
	private void finish(String settings)
	{
		long now = System.currentTimeMillis();
		processPackets(now);
		printSummary(now, settings);
		reset();
	}
	
	private void reset()
	{
		received.clear();
		pending.clear();
		damageEvents.clear();
		health.clear();
		stats = new Stats();
		periodStart = System.currentTimeMillis();
	}
	
	private void log(String format, Object... args)
	{
		LOGGER.info(String.format(Locale.ROOT, format, args));
	}
	
	private static String getName(Entity entity)
	{
		return entity.getName().getString();
	}
	
	private static String getName(int entityId)
	{
		Entity entity = MC.level.getEntity(entityId);
		return entity == null ? "entity " + entityId : getName(entity);
	}
	
	private enum Result
	{
		CRIT("crit", SoundEvents.PLAYER_ATTACK_CRIT),
		SWEEP("sweep", SoundEvents.PLAYER_ATTACK_SWEEP),
		STRONG("strong", SoundEvents.PLAYER_ATTACK_STRONG),
		WEAK("weak", SoundEvents.PLAYER_ATTACK_WEAK),
		NO_DAMAGE("no damage", SoundEvents.PLAYER_ATTACK_NODAMAGE),
		KNOCKBACK("knockback", SoundEvents.PLAYER_ATTACK_KNOCKBACK);
		
		private final String name;
		private final SoundEvent sound;
		
		private Result(String name, SoundEvent sound)
		{
			this.name = name;
			this.sound = sound;
		}
		
		private static Result of(SoundEvent sound)
		{
			for(Result result : values())
				if(result.sound == sound)
					return result;
				
			return null;
		}
	}
	
	private static final class Hit
	{
		private final int id;
		private final int targetId;
		private final String targetName;
		private final Vec3 spot;
		private final Vec3 home;
		private final long sentMs;
		private boolean knockback;
		
		private Hit(int id, int targetId, String targetName, Vec3 spot,
			Vec3 home, long sentMs)
		{
			this.id = id;
			this.targetId = targetId;
			this.targetName = targetName;
			this.spot = spot;
			this.home = home;
			this.sentMs = sentMs;
		}
		
		private boolean isAtSpot(Vec3 pos)
		{
			return isNear(pos, spot);
		}
		
		private boolean isAtHome(Vec3 pos)
		{
			return isNear(pos, home);
		}
		
		private static boolean isNear(Vec3 pos, Vec3 other)
		{
			double dx = pos.x - other.x;
			double dz = pos.z - other.z;
			return dx * dx + dz * dz <= MAX_SOUND_DISTANCE * MAX_SOUND_DISTANCE
				&& Math.abs(pos.y - other.y) <= 1.5;
		}
	}
	
	private static final class TrackedHealth
	{
		private final LivingEntity entity;
		private float health;
		private long until;
		
		private TrackedHealth(LivingEntity entity)
		{
			this.entity = entity;
			health = entity.getHealth();
		}
	}
	
	private static final class Stats
	{
		private int sent;
		private int crit;
		private int sweep;
		private int strong;
		private int weak;
		private int noDamage;
		private int noResponse;
		private int knockback;
		private int damageEvents;
		private int critAnimations;
		private int hurtAnimations;
		private int atHome;
		private int unmatched;
		private float healthLost;
		private final EnumMap<Result, Integer> heard =
			new EnumMap<>(Result.class);
		
		private void heard(Result result)
		{
			heard.merge(result, 1, Integer::sum);
		}
		
		private String getHeard()
		{
			if(heard.isEmpty())
				return "none";
			
			StringBuilder builder = new StringBuilder();
			heard.forEach(
				(result, count) -> builder.append(builder.isEmpty() ? "" : ", ")
					.append(count).append(" ").append(result.name));
			return builder.toString();
		}
		
		private void count(Result result)
		{
			switch(result)
			{
				case CRIT -> crit++;
				case SWEEP -> sweep++;
				case STRONG -> strong++;
				case WEAK -> weak++;
				case NO_DAMAGE -> noDamage++;
				default ->
					{
					}
			}
		}
	}
}
