/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks;

import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.vehicle.boat.AbstractBoat;
import net.minecraft.world.phys.Vec3;
import net.wurstclient.Category;
import net.wurstclient.SearchTags;
import net.wurstclient.events.UpdateListener;
import net.wurstclient.hack.Hack;
import net.wurstclient.settings.CheckboxSetting;
import net.wurstclient.settings.SliderSetting;

@SearchTags({"boat fly", "BoatFlight", "boat flight", "EntitySpeed",
	"entity speed"})
public final class BoatFlyHack extends Hack implements UpdateListener
{
	private final CheckboxSetting changeForwardSpeed = new CheckboxSetting(
		"Change Forward Speed",
		"Allows \u00a7eForward Speed\u00a7r to be changed, disables smooth acceleration.",
		false);
	
	private final SliderSetting forwardSpeed = new SliderSetting(
		"Forward Speed", 1, 0.05, 5, 0.05, SliderSetting.ValueDisplay.DECIMAL);
	
	private final SliderSetting upwardSpeed = new SliderSetting("Upward Speed",
		0.3, 0, 5, 0.05, SliderSetting.ValueDisplay.DECIMAL);
	
	private final SliderSetting downwardSpeed =
		new SliderSetting("Downward Speed",
			"How fast you fly down while holding the sneak key, when"
				+ " \u00a7eAllow Downward Flight\u00a7r is enabled.",
			0.3, 0.05, 5, 0.05, SliderSetting.ValueDisplay.DECIMAL);
	
	private final CheckboxSetting allowDownward = new CheckboxSetting(
		"Allow Downward Flight",
		"Lets you fly down by holding the sneak key, which normally makes you"
			+ " get out of the vehicle. Your vehicle also stops falling and"
			+ " stays in the air when you don't press anything (hold the"
			+ " sprint key to let it fall).\n\n"
			+ "To get out, double-tap the sneak key.",
		false);
	
	private final CheckboxSetting noClip = new CheckboxSetting("NoClip",
		"Lets your vehicle fly through blocks.\n\n"
			+ "Vanilla servers (including singleplayer) don't let vehicles move"
			+ " into blocks and pull them back, so this only works on servers"
			+ " that don't check it.",
		false);
	
	private final CheckboxSetting followHead =
		new CheckboxSetting("Boat follows head rotation",
			"Makes your vehicle always face the direction you are looking, so"
				+ " you steer it with your mouse.\n\n"
				+ "Boats then move in the direction of the keys you press, like"
				+ " walking: left and right move sideways instead of turning,"
				+ " and back moves backwards as fast as forward.",
			false);
	
	// how much a boat speeds up per tick while paddling forward in vanilla
	private static final double BOAT_ACCELERATION = 0.04;
	
	// double-tapping sneak gets you out of the vehicle, like double-tapping
	// jump toggles creative flight
	private static final int DOUBLE_TAP_TICKS = 7;
	private boolean wasSneaking;
	private int doubleTapTimer;
	private boolean dismounting;
	
	private Entity noClipVehicle;
	private Entity hoveringVehicle;
	
	public BoatFlyHack()
	{
		super("BoatFly");
		setCategory(Category.MOVEMENT);
		addSetting(changeForwardSpeed);
		addSetting(forwardSpeed);
		addSetting(upwardSpeed);
		addSetting(downwardSpeed);
		addSetting(allowDownward);
		addSetting(noClip);
		addSetting(followHead);
	}
	
	@Override
	protected void onEnable()
	{
		wasSneaking = false;
		doubleTapTimer = 0;
		dismounting = false;
		EVENTS.add(UpdateListener.class, this);
	}
	
	@Override
	protected void onDisable()
	{
		EVENTS.remove(UpdateListener.class, this);
		setNoClipVehicle(null);
		hoveringVehicle = null;
	}
	
	@Override
	public void onUpdate()
	{
		updateDoubleTap();
		
		// check if riding
		if(!MC.player.isPassenger())
		{
			setNoClipVehicle(null);
			hoveringVehicle = null;
			return;
		}
		
		Entity vehicle = MC.player.getVehicle();
		setNoClipVehicle(noClip.isChecked() ? vehicle : null);
		
		// no gravity, so it stays in the air (and doesn't fall through the
		// floor with NoClip), unless you hold sprint to let it fall
		hoveringVehicle =
			allowDownward.isChecked() && !MC.options.keySprint.isDown()
				? vehicle : null;
		
		// turned the shortest way, so its rotation doesn't spin around
		if(followHead.isChecked())
			vehicle.setYRot(vehicle.getYRot()
				+ Mth.wrapDegrees(MC.player.getYRot() - vehicle.getYRot()));
		
		Vec3 velocity = vehicle.getDeltaMovement();
		
		// default motion
		double motionX = velocity.x;
		double motionY = 0;
		double motionZ = velocity.z;
		
		// up/down
		if(MC.options.keyJump.isDown())
			motionY = upwardSpeed.getValue();
		else if(shouldHideSneak() && MC.options.keyShift.isDown())
			motionY = -downwardSpeed.getValue();
		else if(MC.options.keySprint.isDown())
			motionY = velocity.y;
		
		// in the direction of the movement keys, like walking
		if(followHead.isChecked() && vehicle instanceof AbstractBoat)
		{
			Vec3 direction = getInputDirection();
			if(direction.lengthSqr() > 0)
				if(changeForwardSpeed.isChecked())
				{
					motionX = direction.x * forwardSpeed.getValue();
					motionZ = direction.z * forwardSpeed.getValue();
					
				}else
				{
					motionX += direction.x * BOAT_ACCELERATION;
					motionZ += direction.z * BOAT_ACCELERATION;
				}
			
		}else if(MC.options.keyUp.isDown() && changeForwardSpeed.isChecked())
		{
			// forward
			double speed = forwardSpeed.getValue();
			float yawRad = vehicle.getYRot() * Mth.DEG_TO_RAD;
			
			motionX = Mth.sin(-yawRad) * speed;
			motionZ = Mth.cos(yawRad) * speed;
		}
		
		// apply motion
		vehicle.setDeltaMovement(motionX, motionY, motionZ);
	}
	
	private Vec3 getInputDirection()
	{
		double forward = (MC.options.keyUp.isDown() ? 1 : 0)
			- (MC.options.keyDown.isDown() ? 1 : 0);
		double left = (MC.options.keyLeft.isDown() ? 1 : 0)
			- (MC.options.keyRight.isDown() ? 1 : 0);
		
		return new Vec3(left, 0, forward).normalize()
			.yRot(-MC.player.getYRot() * Mth.DEG_TO_RAD);
	}
	
	/**
	 * Whether the boat's own controls (paddling forward, turning with left
	 * and right) should be turned off, because BoatFly moves it instead.
	 *
	 * <p>
	 * Called from boatfly.LocalPlayerMixin.
	 */
	public boolean shouldOverrideBoatControls()
	{
		return isEnabled() && followHead.isChecked();
	}
	
	private void updateDoubleTap()
	{
		boolean sneaking = MC.options.keyShift.isDown();
		if(doubleTapTimer > 0)
			doubleTapTimer--;
		
		if(sneaking && !wasSneaking)
			if(doubleTapTimer > 0)
				dismounting = true;
			else
				doubleTapTimer = DOUBLE_TAP_TICKS;
			
		if(!sneaking)
			dismounting = false;
		
		wasSneaking = sneaking;
	}
	
	private void setNoClipVehicle(Entity vehicle)
	{
		if(noClipVehicle == vehicle)
			return;
		
		if(noClipVehicle != null)
			noClipVehicle.noPhysics = false;
		
		noClipVehicle = vehicle;
		if(vehicle != null)
			vehicle.noPhysics = true;
	}
	
	/**
	 * Whether the given entity is the vehicle that BoatFly is keeping in the
	 * air. Called from boatfly.EntityMixin for every entity, so it must be
	 * fast.
	 */
	public boolean isHovering(Entity entity)
	{
		return entity == hoveringVehicle;
	}
	
	/**
	 * Whether the sneak key should be hidden from the game, so that it flies
	 * down instead of getting you out of the vehicle. The server makes you
	 * get out when it sees you sneaking, so it can't just be ignored on the
	 * client.
	 *
	 * <p>
	 * Called from LocalPlayerMixin right after the keys are read.
	 */
	public boolean shouldHideSneak()
	{
		return isEnabled() && allowDownward.isChecked()
			&& MC.player.isPassenger() && !dismounting;
	}
}
