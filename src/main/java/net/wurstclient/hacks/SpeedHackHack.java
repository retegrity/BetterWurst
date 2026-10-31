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
import net.wurstclient.events.FlyingSpeedListener;
import net.wurstclient.events.UpdateListener;
import net.wurstclient.hack.Hack;
import net.wurstclient.settings.CheckboxSetting;
import net.wurstclient.settings.SliderSetting;
import net.wurstclient.settings.SliderSetting.ValueDisplay;

@SearchTags({"speed hack", "speed", "strafe", "air strafe", "air control"})
public final class SpeedHackHack extends Hack
	implements UpdateListener, FlyingSpeedListener
{
	private final SliderSetting speed = new SliderSetting("Speed",
		"How many times faster than vanilla you move.\n"
			+ "1x is the normal vanilla speed.",
		1, 1, 5, 0.1, ValueDisplay.DECIMAL.withSuffix("x"));
	
	private final CheckboxSetting strafe = new CheckboxSetting("Strafe",
		"Removes the delay when changing direction, in the air and on the"
			+ " ground. You keep your speed but instantly move in the"
			+ " direction you press, and you stop completely when you let go"
			+ " of the movement keys.",
		false);
	
	public SpeedHackHack()
	{
		super("SpeedHack");
		setCategory(Category.MOVEMENT);
		addSetting(speed);
		addSetting(strafe);
	}
	
	@Override
	protected void onEnable()
	{
		EVENTS.add(UpdateListener.class, this);
		EVENTS.add(FlyingSpeedListener.class, this);
	}
	
	@Override
	protected void onDisable()
	{
		EVENTS.remove(UpdateListener.class, this);
		EVENTS.remove(FlyingSpeedListener.class, this);
	}
	
	/**
	 * Multiplier for the player's vanilla ground speed. Applied by
	 * LocalPlayerMixin.
	 */
	public float getSpeedMultiplier()
	{
		return isEnabled() ? speed.getValueF() : 1;
	}
	
	/**
	 * Vanilla's air acceleration is separate from the ground speed, so it
	 * has to be scaled too or the boost would vanish as soon as you jump.
	 */
	@Override
	public void onGetFlyingSpeed(FlyingSpeedEvent event)
	{
		if(MC.player != null && !MC.player.getAbilities().flying)
			event.setSpeed(event.getSpeed() * speed.getValueF());
	}
	
	@Override
	public void onUpdate()
	{
		if(strafe.isChecked())
			strafe(MC.player);
	}
	
	/**
	 * Instant direction changes, like LiquidBounce's Strafe: keeps your
	 * current horizontal speed but points it in the direction you are
	 * holding, and stops you completely when you let go of the keys.
	 */
	private void strafe(LocalPlayer player)
	{
		if(player.isInWater() || player.isInLava() || player.onClimbable()
			|| player.isFallFlying() || player.getAbilities().flying
			|| player.isPassenger())
			return;
		
		Vec3 v = player.getDeltaMovement();
		float forward = player.zza;
		float sideways = player.xxa;
		
		if(forward == 0 && sideways == 0)
		{
			player.setDeltaMovement(0, v.y, 0);
			return;
		}
		
		float yaw = player.getYRot() * Mth.DEG_TO_RAD;
		double sin = Mth.sin(yaw);
		double cos = Mth.cos(yaw);
		double wx = sideways * cos - forward * sin;
		double wz = forward * cos + sideways * sin;
		double length = Math.sqrt(wx * wx + wz * wz);
		wx /= length;
		wz /= length;
		
		double currentSpeed = Math.sqrt(v.x * v.x + v.z * v.z);
		player.setDeltaMovement(wx * currentSpeed, v.y, wz * currentSpeed);
	}
}
