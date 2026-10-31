/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.function.ToDoubleFunction;
import java.util.stream.Stream;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.game.ServerboundClientTickEndPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.wurstclient.Category;
import net.wurstclient.SearchTags;
import net.wurstclient.events.PacketInputListener;
import net.wurstclient.events.UpdateListener;
import net.wurstclient.hack.Hack;
import net.wurstclient.hacks.tpaura.TeleportPathFinder;
import net.wurstclient.hacks.tpaura.TpAuraDebugger;
import net.wurstclient.mixin.LivingEntityAccessor;
import net.wurstclient.settings.AttackSpeedSliderSetting;
import net.wurstclient.settings.CheckboxSetting;
import net.wurstclient.settings.EnumSetting;
import net.wurstclient.settings.PauseAttackOnContainersSetting;
import net.wurstclient.settings.SliderSetting;
import net.wurstclient.settings.SliderSetting.ValueDisplay;
import net.wurstclient.settings.SwingHandSetting;
import net.wurstclient.settings.SwingHandSetting.SwingHand;
import net.wurstclient.settings.filterlists.EntityFilterList;
import net.wurstclient.util.AttackUtils;
import net.wurstclient.util.EntityUtils;
import net.wurstclient.util.PacketBatcher;
import net.wurstclient.util.Rotation;
import net.wurstclient.util.RotationUtils;

@SearchTags({"TpAura", "tp aura", "EnderAura", "Ender-Aura", "ender aura"})
public final class TpAuraHack extends Hack implements UpdateListener
{
	// how close the player teleports to the target in Path mode
	private static final double PATH_REACH = 3;
	
	// if the server moves the player after a trip, the player is sent back
	private static final int RETURN_WINDOW_TICKS = 20;
	private static final int RETURN_RETRY_DELAY_TICKS = 4;
	private static final double RETURN_BASE_DISTANCE = 4;
	private static final int MAX_RETURN_ATTEMPTS = 3;
	
	private final Random random = new Random();
	private double hitsOwed;
	
	// where the last trip started, and when
	private Vec3 lastHome;
	private int lastTripTick;
	private int returnAttempts;
	private int nextReturnTick;
	
	// targets without a path, and the tick when to look for one again
	private final HashMap<Integer, Integer> noPathUntil = new HashMap<>();
	
	private final TpAuraDebugger debugger = new TpAuraDebugger();
	
	private final EnumSetting<Mode> mode = new EnumSetting<>("Mode",
		"\u00a7lNormal\u00a7r mode teleports you next to the target and leaves"
			+ " you there.\n\n"
			+ "\u00a7lPath\u00a7r mode teleports you to the target along a path"
			+ " around walls, hits it and teleports you back, all at once."
			+ " The server handles it between two of its ticks, so other"
			+ " players never see you near the target and can't hit you back."
			+ " Doesn't work against anti-cheats that check movement.",
		Mode.values(), Mode.NORMAL);
	
	private final SliderSetting range = new SliderSetting("Range",
		"How far away targets can be in Normal mode.", 4.25, 1, 6, 0.05,
		ValueDisplay.DECIMAL);
	
	private final SliderSetting pathRange = new SliderSetting("Path range",
		"How far away targets can be in Path mode.\n\n"
			+ "Every 9 blocks of distance take one movement packet there and one"
			+ " back for each hit.",
		40, 6, 100, 1, ValueDisplay.INTEGER);
	
	private final CheckboxSetting ultraFast = new CheckboxSetting(
		"Ultra fast mode",
		"Only works in Path mode.\n\n"
			+ "Ignores the attack cooldown and hits as often as"
			+ " \"Ultra fast hits/s\" says, with every hit counting as fully"
			+ " charged on your side.\n\n"
			+ "Whether the extra hits do full damage is up to the server."
			+ " Vanilla servers scale the damage by their own cooldown, which"
			+ " Wurst can't speed up.\n\n"
			+ "Every trip takes a few movement packets, so lower the speed or"
			+ " fight closer if the server kicks you for sending too many"
			+ " packets.",
		false);
	
	private final SliderSetting ultraFastSpeed =
		new SliderSetting("Ultra fast hits/s",
			"How many hits per second Ultra fast mode does. Above 20, it hits"
				+ " several times per tick.",
			20, 1, 20, 1, ValueDisplay.INTEGER);
	
	private final SliderSetting ultraFastTicks = new SliderSetting(
		"Ultra fast fake ticks",
		"How many client ticks Ultra fast mode pretends to have before each"
			+ " hit, by sending the packets that the game sends at the end of"
			+ " every tick. This is what Timer does on the server's side, for"
			+ " servers that count the attack cooldown by these packets.\n\n"
			+ "\u00a7lAuto\u00a7r sends as many as a full attack cooldown with"
			+ " the held item takes.",
		0, 0, 40, 1, ValueDisplay.INTEGER.withLabel(0, "Auto"));
	
	private final EnumSetting<FakeTickPackets> fakeTickPackets =
		new EnumSetting<>("Fake tick packets",
			"What Ultra fast mode sends for every fake tick.\n\n"
				+ "\u00a7lTick end\u00a7r - Only the packet that the game sends"
				+ " at the end of every tick. This is all that Timer sends while"
				+ " you stand still.\n"
				+ "\u00a7lTick end + position\u00a7r - Also a movement packet"
				+ " at your position for each tick.",
			FakeTickPackets.values(), FakeTickPackets.TICK_END);
	
	private final AttackSpeedSliderSetting speed =
		new AttackSpeedSliderSetting();
	
	private final EnumSetting<Priority> priority = new EnumSetting<>("Priority",
		"Determines which entity will be attacked first.\n"
			+ "\u00a7lDistance\u00a7r - Attacks the closest entity.\n"
			+ "\u00a7lAngle\u00a7r - Attacks the entity that requires the least head movement.\n"
			+ "\u00a7lHealth\u00a7r - Attacks the weakest entity.",
		Priority.values(), Priority.ANGLE);
	
	private final SwingHandSetting swingHand = new SwingHandSetting(
		SwingHandSetting.genericCombatDescription(this), SwingHand.CLIENT);
	
	private final PauseAttackOnContainersSetting pauseOnContainers =
		new PauseAttackOnContainersSetting(true);
	
	private final CheckboxSetting debug = new CheckboxSetting("Debug log",
		"Writes every hit to the game log (latest.log), with the settings it"
			+ " was sent with and what the server made of it: crit, sweep,"
			+ " strong (fully charged), weak, no damage (the target was still"
			+ " protected from the last hit) or no response.\n\n"
			+ "Every 5 seconds, a summary is shown in chat. Useful for"
			+ " comparing settings, like Ultra fast mode against Timer.",
		false);
	
	private final EntityFilterList entityFilters =
		EntityFilterList.genericMeleeCombat();
	
	public TpAuraHack()
	{
		super("TP-Aura");
		setCategory(Category.COMBAT);
		
		addSetting(mode);
		addSetting(range);
		addSetting(pathRange);
		addSetting(ultraFast);
		addSetting(ultraFastSpeed);
		addSetting(ultraFastTicks);
		addSetting(fakeTickPackets);
		addSetting(speed);
		addSetting(priority);
		addSetting(swingHand);
		addSetting(pauseOnContainers);
		addSetting(debug);
		
		entityFilters.forEach(this::addSetting);
	}
	
	@Override
	protected void onEnable()
	{
		// disable other killauras
		WURST.getHax().aimAssistHack.setEnabled(false);
		WURST.getHax().clickAuraHack.setEnabled(false);
		WURST.getHax().crystalAuraHack.setEnabled(false);
		WURST.getHax().fightBotHack.setEnabled(false);
		WURST.getHax().killauraLegitHack.setEnabled(false);
		WURST.getHax().killauraHack.setEnabled(false);
		WURST.getHax().multiAuraHack.setEnabled(false);
		WURST.getHax().protectHack.setEnabled(false);
		WURST.getHax().triggerBotHack.setEnabled(false);
		
		speed.resetTimer();
		hitsOwed = 0;
		lastHome = null;
		noPathUntil.clear();
		EVENTS.add(UpdateListener.class, this);
		EVENTS.add(PacketInputListener.class, debugger);
	}
	
	@Override
	protected void onDisable()
	{
		EVENTS.remove(UpdateListener.class, this);
		EVENTS.remove(PacketInputListener.class, debugger);
		debugger.stop(getDebugSettings());
	}
	
	@Override
	public void onUpdate()
	{
		debugger.update(debug.isChecked(), getDebugSettings());
		
		if(isUltraFast())
		{
			// don't save up hits while there is nothing to hit
			double hitsPerTick = ultraFastSpeed.getValue() / 20;
			hitsOwed =
				Math.min(hitsOwed + hitsPerTick, Math.max(1, hitsPerTick));
			if(hitsOwed < 1 || pauseOnContainers.shouldPause())
				return;
			
			attackThroughPath();
			return;
		}
		
		speed.updateTimer();
		if(!speed.isTimeToAttack())
			return;
		
		if(pauseOnContainers.shouldPause())
			return;
		
		if(mode.getSelected() == Mode.PATH)
		{
			attackThroughPath();
			return;
		}
		
		LocalPlayer player = MC.player;
		
		// set entity
		Stream<Entity> stream = EntityUtils.getMeleeAttackableEntities();
		double rangeSq = Math.pow(range.getValue(), 2);
		stream =
			stream.filter(e -> EntityUtils.distanceToHitboxSq(e) <= rangeSq);
		
		stream = entityFilters.applyTo(stream);
		
		Entity entity =
			stream.min(priority.getSelected().comparator).orElse(null);
		if(entity == null)
			return;
		
		WURST.getHax().autoSwordHack.setSlot(entity);
		
		// the attack is sent before this tick's movement packet, so the server
		// still has the player where they were before teleporting
		Vec3 serverPos = player.position();
		
		// teleport
		player.setPos(entity.getX() + random.nextInt(3) * 2 - 2, entity.getY(),
			entity.getZ() + random.nextInt(3) * 2 - 2);
		
		// check cooldown
		if(player.getAttackStrengthScale(0) < 1)
			return;
		
		if(WURST.getHax().criticalsHack.shouldDelayAttack())
			return;
		
		if(AttackUtils.shouldDelayAttack())
			return;
		
		// attack entity
		RotationUtils.getNeededRotations(entity.getBoundingBox().getCenter())
			.sendPlayerLookPacket();
		
		debugger.onHit(entity, serverPos, serverPos, String.format(Locale.ROOT,
			"Normal mode, charge %.2f", player.getAttackStrengthScale(0.5F)));
		AttackUtils.attackAndSwing(entity,
			() -> swingHand.swing(InteractionHand.MAIN_HAND));
		speed.resetTimer();
	}
	
	private void attackThroughPath()
	{
		LocalPlayer player = MC.player;
		
		// the server would move a sneaking player differently
		if(player.isPassenger() || player.isShiftKeyDown()
			|| player.isSleeping())
			return;
		
		if(returnHomeIfMoved())
			return;
		
		Stream<Entity> stream = EntityUtils.getMeleeAttackableEntities();
		double rangeSq = Math.pow(pathRange.getValue(), 2);
		stream =
			stream.filter(e -> EntityUtils.distanceToHitboxSq(e) <= rangeSq);
		
		List<Entity> targets = entityFilters.applyTo(stream)
			.sorted(priority.getSelected().comparator).limit(3).toList();
		if(targets.isEmpty())
			return;
		
		WURST.getHax().autoSwordHack.setSlot(targets.get(0));
		
		// check cooldown
		boolean ultraFast = isUltraFast();
		if(!ultraFast && player.getAttackStrengthScale(0) < 1)
			return;
		
		if(!ultraFast && WURST.getHax().criticalsHack.shouldDelayAttack())
			return;
		
		if(AttackUtils.shouldDelayAttack())
			return;
		
		int hits = ultraFast ? (int)hitsOwed : 1;
		Vec3 home = player.position();
		int tick = player.tickCount;
		if(noPathUntil.size() > 100)
			noPathUntil.clear();
		
		// only one slow search around obstacles per tick
		boolean searched = false;
		for(Entity target : targets)
		{
			if(noPathUntil.getOrDefault(target.getId(), 0) > tick)
				continue;
			
			TeleportPathFinder finder =
				new TeleportPathFinder(target.getBoundingBox(), PATH_REACH);
			ArrayList<Vec3> path = finder.find(home, !searched);
			searched |= finder.hasSearched();
			if(path == null)
			{
				// searching every tick for a target that can't be reached
				// would make the game lag
				if(finder.hasSearched())
					noPathUntil.put(target.getId(),
						tick + (finder.hasTimedOut() ? 5 : 20));
				continue;
			}
			
			PacketBatcher
				.run(() -> attackThroughPath(target, home, path, hits));
			
			// remember where to go back to, if the server moves the player
			lastHome = path.isEmpty() ? null : home;
			lastTripTick = tick;
			returnAttempts = 0;
			nextReturnTick = 0;
			speed.resetTimer();
			hitsOwed -= hits;
			return;
		}
	}
	
	/**
	 * If the server moved the player right after a trip, like when it doesn't
	 * accept a teleport and puts the player back where it thinks they were,
	 * the player would stay next to the target, and the next trip would treat
	 * that as home. So the player is sent back to where the trip started.
	 *
	 * @return whether it sent the player back
	 */
	private boolean returnHomeIfMoved()
	{
		if(lastHome == null)
			return false;
		
		LocalPlayer player = MC.player;
		int ticksSince = player.tickCount - lastTripTick;
		if(ticksSince > RETURN_WINDOW_TICKS)
		{
			lastHome = null;
			return false;
		}
		
		// moving normally can't take the player this far from home so fast
		Vec3 from = player.position();
		double offset = from.distanceTo(lastHome);
		if(offset <= RETURN_BASE_DISTANCE + ticksSince)
			return false;
			
		// give the server time to settle before the next try, and don't attack
		// from where the server put the player in the meantime
		if(player.tickCount < nextReturnTick)
			return true;
		
		if(++returnAttempts > MAX_RETURN_ATTEMPTS)
		{
			debugger.note("The server moved you " + (int)offset
				+ " blocks from home, and it didn't work to go back");
			lastHome = null;
			return false;
		}
		
		double eyeHeight = player.getEyeHeight(player.getPose());
		AABB homeBox = new AABB(lastHome.x, lastHome.y, lastHome.z, lastHome.x,
			lastHome.y + eyeHeight, lastHome.z);
		ArrayList<Vec3> path =
			new TeleportPathFinder(homeBox, 1.5).find(from, true);
		if(path == null)
		{
			debugger.note("The server moved you " + (int)offset
				+ " blocks from home, and there is no path back");
			lastHome = null;
			return false;
		}
		
		Vec3 home = lastHome;
		path.add(home);
		debugger.note(String.format(Locale.ROOT,
			"The server moved you %.1f blocks from home, sending you back"
				+ " with %d teleports (attempt %d)",
			offset, path.size(), returnAttempts));
		
		PacketBatcher.run(() -> {
			Vec3 previous = from;
			for(int i = 0; i < path.size(); i++)
			{
				Vec3 pos = path.get(i);
				boolean onGround = isOnGround(previous, pos,
					i == path.size() - 1 && player.onGround());
				if(i < path.size() - 1)
					player.connection.send(new ServerboundMovePlayerPacket.Pos(
						pos, onGround, false));
				else
					player.connection
						.send(new ServerboundMovePlayerPacket.PosRot(pos,
							WURST.getRotationFaker().getSentYaw(),
							WURST.getRotationFaker().getSentPitch(), onGround,
							player.horizontalCollision));
				previous = pos;
			}
		});
		
		player.setPos(home);
		lastTripTick = player.tickCount;
		nextReturnTick = player.tickCount + RETURN_RETRY_DELAY_TICKS;
		return true;
	}
	
	/**
	 * Makes the attack count as fully charged: on this side by setting the
	 * cooldown, and on servers that count it by ticks the client says it had,
	 * by pretending to have had that many ticks.
	 */
	private int fakeFullCooldown(Vec3 spot, boolean onGround)
	{
		LocalPlayer player = MC.player;
		int cooldown =
			(int)Math.ceil(player.getCurrentItemAttackStrengthDelay());
		
		int ticks = ultraFastTicks.getValueI();
		if(ticks <= 0)
			ticks = cooldown;
		
		for(int i = 0; i < ticks; i++)
		{
			if(fakeTickPackets
				.getSelected() == FakeTickPackets.TICK_END_AND_POSITION)
				player.connection.send(
					new ServerboundMovePlayerPacket.Pos(spot, onGround, false));
			
			player.connection.send(ServerboundClientTickEndPacket.INSTANCE);
		}
		
		// so that Criticals and the hit effects work as usual
		((LivingEntityAccessor)player).wurst_setAttackStrengthTicker(cooldown);
		return ticks;
	}
	
	/**
	 * The settings that matter for the debug log's summary.
	 */
	private String getDebugSettings()
	{
		String settings = String.format(Locale.ROOT, "%s mode, timer %.1f",
			mode.getSelected(), WURST.getHax().timerHack.getTimerSpeed());
		
		if(isUltraFast())
			settings += String.format(Locale.ROOT,
				", ultra fast %d hits/s, %s fake ticks (%s)",
				ultraFastSpeed.getValueI(), ultraFastTicks.getValueString(),
				fakeTickPackets.getSelected());
		
		return settings;
	}
	
	private boolean isUltraFast()
	{
		return mode.getSelected() == Mode.PATH && ultraFast.isChecked();
	}
	
	/**
	 * Teleports to the target, hits it and teleports back. Only called inside
	 * PacketBatcher, so that the server handles all of it at once.
	 */
	private void attackThroughPath(Entity target, Vec3 home,
		ArrayList<Vec3> path, int hits)
	{
		LocalPlayer player = MC.player;
		boolean moved = !path.isEmpty();
		
		// stand at the attack spot, so that the rotation and anything that
		// sends packets when attacking, like Criticals, use it
		if(moved)
			player.setPos(path.get(path.size() - 1));
		
		try
		{
			Rotation rotation = RotationUtils
				.getNeededRotations(target.getBoundingBox().getCenter());
			
			for(int i = 0; i < path.size(); i++)
			{
				Vec3 pos = path.get(i);
				boolean onGround =
					isOnGround(i == 0 ? home : path.get(i - 1), pos, false);
				if(i < path.size() - 1)
					player.connection.send(new ServerboundMovePlayerPacket.Pos(
						pos, onGround, false));
				else
					player.connection
						.send(new ServerboundMovePlayerPacket.PosRot(pos,
							rotation.yaw(), rotation.pitch(), onGround, false));
			}
			
			if(!moved)
				rotation.sendPlayerLookPacket();
			
			Vec3 spot = moved ? path.get(path.size() - 1) : home;
			for(int i = 0; i < hits; i++)
			{
				int fakeTicks = 0;
				if(isUltraFast())
					fakeTicks = fakeFullCooldown(spot,
						moved ? TeleportPathFinder.isOnGround(spot)
							: player.onGround());
				
				if(debug.isChecked())
					debugger.onHit(target, spot, home, String.format(
						Locale.ROOT,
						"%d teleports, hit %d/%d, %d fake ticks, charge %.2f",
						path.size(), i + 1, hits, fakeTicks,
						player.getAttackStrengthScale(0.5F)));
				
				AttackUtils.attackAndSwing(target,
					() -> swingHand.swing(InteractionHand.MAIN_HAND));
			}
			
		}finally
		{
			player.setPos(home);
		}
		
		if(!moved)
			return;
		
		// back the same way
		for(int i = path.size() - 2; i >= 0; i--)
		{
			Vec3 pos = path.get(i);
			player.connection.send(new ServerboundMovePlayerPacket.Pos(pos,
				isOnGround(path.get(i + 1), pos, false), false));
		}
		
		player.connection.send(new ServerboundMovePlayerPacket.PosRot(home,
			WURST.getRotationFaker().getSentYaw(),
			WURST.getRotationFaker().getSentPitch(),
			isOnGround(path.get(0), home, player.onGround()),
			player.horizontalCollision));
	}
	
	/**
	 * The server adds every drop that a movement packet makes while not on
	 * the ground to the fall distance, and deals fall damage for all of it
	 * once a packet is on the ground again. Claiming to be on the ground
	 * when teleporting down keeps the teleports from adding up fall damage.
	 */
	private boolean isOnGround(Vec3 from, Vec3 to, boolean onGround)
	{
		return to.y < from.y || onGround || TeleportPathFinder.isOnGround(to);
	}
	
	private enum FakeTickPackets
	{
		TICK_END("Tick end"),
		TICK_END_AND_POSITION("Tick end + position");
		
		private final String name;
		
		private FakeTickPackets(String name)
		{
			this.name = name;
		}
		
		@Override
		public String toString()
		{
			return name;
		}
	}
	
	private enum Mode
	{
		NORMAL("Normal"),
		PATH("Path");
		
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
	
	private enum Priority
	{
		DISTANCE("Distance", EntityUtils::distanceToHitboxSq),
		
		ANGLE("Angle",
			e -> RotationUtils
				.getAngleToLookVec(e.getBoundingBox().getCenter())),
		
		HEALTH("Health", e -> e instanceof LivingEntity
			? ((LivingEntity)e).getHealth() : Integer.MAX_VALUE);
		
		private final String name;
		private final Comparator<Entity> comparator;
		
		private Priority(String name, ToDoubleFunction<Entity> keyExtractor)
		{
			this.name = name;
			comparator = Comparator.comparingDouble(keyExtractor);
		}
		
		@Override
		public String toString()
		{
			return name;
		}
	}
}
