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
import java.util.List;
import java.util.Random;
import java.util.function.ToDoubleFunction;
import java.util.stream.Stream;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import net.wurstclient.Category;
import net.wurstclient.SearchTags;
import net.wurstclient.events.UpdateListener;
import net.wurstclient.hack.Hack;
import net.wurstclient.hacks.tpaura.TeleportPathFinder;
import net.wurstclient.settings.AttackSpeedSliderSetting;
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
	
	private final Random random = new Random();
	
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
	
	private final EntityFilterList entityFilters =
		EntityFilterList.genericMeleeCombat();
	
	public TpAuraHack()
	{
		super("TP-Aura");
		setCategory(Category.COMBAT);
		
		addSetting(mode);
		addSetting(range);
		addSetting(pathRange);
		addSetting(speed);
		addSetting(priority);
		addSetting(swingHand);
		addSetting(pauseOnContainers);
		
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
		EVENTS.add(UpdateListener.class, this);
	}
	
	@Override
	protected void onDisable()
	{
		EVENTS.remove(UpdateListener.class, this);
	}
	
	@Override
	public void onUpdate()
	{
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
		if(player.getAttackStrengthScale(0) < 1)
			return;
		
		if(WURST.getHax().criticalsHack.shouldDelayAttack())
			return;
		
		if(AttackUtils.shouldDelayAttack())
			return;
		
		Vec3 home = player.position();
		for(Entity target : targets)
		{
			ArrayList<Vec3> path =
				new TeleportPathFinder(target.getBoundingBox(), PATH_REACH)
					.find(home);
			if(path == null)
				continue;
			
			PacketBatcher.run(() -> attackThroughPath(target, home, path));
			speed.resetTimer();
			return;
		}
	}
	
	/**
	 * Teleports to the target, hits it and teleports back. Only called inside
	 * PacketBatcher, so that the server handles all of it at once.
	 */
	private void attackThroughPath(Entity target, Vec3 home,
		ArrayList<Vec3> path)
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
			
			AttackUtils.attackAndSwing(target,
				() -> swingHand.swing(InteractionHand.MAIN_HAND));
			
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
