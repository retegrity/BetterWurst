/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import net.wurstclient.Category;
import net.wurstclient.SearchTags;
import net.wurstclient.events.UpdateListener;
import net.wurstclient.hack.Hack;
import net.wurstclient.settings.SliderSetting;
import net.wurstclient.settings.SliderSetting.ValueDisplay;
import net.wurstclient.util.JumpUtils;

/**
 * Jumping while sprinting adds 0.2 to your velocity in the direction you're
 * facing. If you jump on the exact tick that you receive knockback, that
 * boost goes against the knockback, while the jump height is about the same
 * as the knockback's own upward push.
 */
@SearchTags({"jump reset", "JumpResetting", "jump resetting", "KnockbackReduce",
	"knockback reduce"})
public final class JumpResetHack extends Hack implements UpdateListener
{
	private static final double SPRINT_JUMP_BOOST = 0.2;
	
	private final SliderSetting chance = new SliderSetting("Chance",
		"How often to jump reset when it would help. Hitting every single one"
			+ " with perfect timing is something a human can't do.",
		1, 0, 1, 0.01, ValueDisplay.PERCENTAGE);
	
	private Vec3 knockback;
	private boolean resettingThisTick;
	
	public JumpResetHack()
	{
		super("JumpReset");
		setCategory(Category.COMBAT);
		addSetting(chance);
	}
	
	@Override
	protected void onEnable()
	{
		knockback = null;
		EVENTS.add(UpdateListener.class, this);
	}
	
	@Override
	protected void onDisable()
	{
		EVENTS.remove(UpdateListener.class, this);
		knockback = null;
		resettingThisTick = false;
	}
	
	/**
	 * Called when the server sets the player's velocity, before the tick in
	 * which the player moves with it.
	 */
	public void onVelocity(LocalPlayer player, Vec3 velocity)
	{
		// only knockback from being hurt, which comes right after the damage
		// (or one tick later if the network split the two packets)
		if(isEnabled() && player.hurtDuration > 0
			&& player.hurtTime >= player.hurtDuration - 1)
			knockback = velocity;
	}
	
	@Override
	public void onUpdate()
	{
		Vec3 velocity = knockback;
		knockback = null;
		resettingThisTick = velocity != null && shouldJump(MC.player, velocity);
		if(resettingThisTick)
			JumpUtils.jump();
	}
	
	private boolean shouldJump(LocalPlayer player, Vec3 velocity)
	{
		// the jump has to happen before the knockback lifts the player
		if(!player.onGround() || player.isPassenger())
			return false;
		
		// no sprint means no boost, just a slightly longer flight
		if(!player.isSprinting() || !MC.options.keyUp.isDown())
			return false;
		
		// facing away, the boost would add to the knockback instead
		float yaw = player.getYRot() * Mth.DEG_TO_RAD;
		double boostX = -Mth.sin(yaw) * SPRINT_JUMP_BOOST;
		double boostZ = Mth.cos(yaw) * SPRINT_JUMP_BOOST;
		double before = velocity.horizontalDistanceSqr();
		double after =
			Mth.square(velocity.x + boostX) + Mth.square(velocity.z + boostZ);
		if(after >= before)
			return false;
		
		return player.getRandom().nextDouble() < chance.getValue();
	}
	
	/**
	 * Whether JumpReset jumps in this tick, which needs the player to keep
	 * sprinting.
	 */
	public boolean isResettingThisTick()
	{
		return isEnabled() && resettingThisTick;
	}
}
