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
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.phys.Vec3;
import net.wurstclient.Category;
import net.wurstclient.SearchTags;
import net.wurstclient.events.FlyingSpeedListener;
import net.wurstclient.events.UpdateListener;
import net.wurstclient.hack.Hack;
import net.wurstclient.settings.CheckboxSetting;
import net.wurstclient.settings.EnumSetting;
import net.wurstclient.settings.SliderSetting;
import net.wurstclient.settings.SliderSetting.ValueDisplay;

@SearchTags({"speed hack", "speed", "strafe", "air strafe", "air control",
	"bhop", "bunny hop", "NCPHop", "NCP hop"})
public final class SpeedHackHack extends Hack
	implements UpdateListener, FlyingSpeedListener
{
	// NoCheatPlus' sprinting speed limit: 0.221 walking * 1.3 sprinting
	private static final double NCP_SPRINT_SPEED = 0.221 * 1.30000002;
	private static final double NCP_WALK_SPEED = 0.221;
	
	private final EnumSetting<Mode> mode = new EnumSetting<>("Mode",
		"\u00a7lVanilla\u00a7r makes you faster by the \"Speed\" setting.\n\n"
			+ "\u00a7lNCPHop\u00a7r bunny hops just within NoCheatPlus'"
			+ " limits. Made for Rewound (Hypixel 2020).",
		Mode.values(), Mode.VANILLA);
	
	private final SliderSetting speed = new SliderSetting("Speed",
		"How many times faster than vanilla you move.\n"
			+ "1x is the normal vanilla speed.",
		1, 1, 5, 0.1, ValueDisplay.DECIMAL.withSuffix("x"));
	
	private final SliderSetting hopBoost =
		new SliderSetting("Hop boost (NCPHop)",
			"How much faster than sprinting each hop starts out. NoCheatPlus"
				+ " allows up to 2.15x.",
			2, 1.4, 2.15, 0.01, ValueDisplay.DECIMAL.withSuffix("x"));
	
	private final CheckboxSetting strafe = new CheckboxSetting("Strafe",
		"Removes the delay when changing direction, in the air and on the"
			+ " ground. You keep your speed but instantly move in the"
			+ " direction you press, and you stop completely when you let go"
			+ " of the movement keys.",
		false);
	
	// NCPHop: 1 = just hopped, 2 = in the air after that, 0 = not hopping
	private int hopStage;
	private double lastDistance;
	
	public SpeedHackHack()
	{
		super("SpeedHack");
		setCategory(Category.MOVEMENT);
		addSetting(mode);
		addSetting(speed);
		addSetting(hopBoost);
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
		return isVanilla() ? speed.getValueF() : 1;
	}
	
	/**
	 * Vanilla's air acceleration is separate from the ground speed, so it
	 * has to be scaled too or the boost would vanish as soon as you jump.
	 */
	@Override
	public void onGetFlyingSpeed(FlyingSpeedEvent event)
	{
		if(isVanilla() && MC.player != null && !MC.player.getAbilities().flying)
			event.setSpeed(event.getSpeed() * speed.getValueF());
	}
	
	@Override
	public void onUpdate()
	{
		// NCPHop already moves in the direction you are holding
		if(strafe.isChecked() && mode.getSelected() == Mode.VANILLA)
			strafe(MC.player);
	}
	
	private boolean isVanilla()
	{
		return isEnabled() && mode.getSelected() == Mode.VANILLA;
	}
	
	/**
	 * Instant direction changes, like LiquidBounce's Strafe: keeps your
	 * current horizontal speed but points it in the direction you are
	 * holding, and stops you completely when you let go of the keys.
	 */
	private void strafe(LocalPlayer player)
	{
		if(!canControlMovement(player))
			return;
		
		Vec3 v = player.getDeltaMovement();
		Vec3 dir = getInputDirection(player);
		
		if(dir == null)
		{
			player.setDeltaMovement(0, v.y, 0);
			return;
		}
		
		double currentSpeed = Math.sqrt(v.x * v.x + v.z * v.z);
		player.setDeltaMovement(dir.x * currentSpeed, v.y,
			dir.z * currentSpeed);
	}
	
	/**
	 * NCPHop: replaces the movement of this tick. Called from
	 * LocalPlayerMixin right before the game moves you.
	 *
	 * <p>
	 * NoCheatPlus allows one move of up to 2.15x the sprinting speed when you
	 * jump off the ground. The next move has to lose at least 66% of the
	 * extra speed, and every move after that at least 1/160 of the speed,
	 * until you land and can hop again.
	 */
	public Vec3 modifyMove(LocalPlayer player, Vec3 delta)
	{
		if(!isEnabled() || mode.getSelected() != Mode.NCPHOP
			|| !canControlMovement(player) || player.isShiftKeyDown())
		{
			hopStage = 0;
			return delta;
		}
		
		Vec3 dir = getInputDirection(player);
		if(dir == null)
		{
			hopStage = 0;
			return delta;
		}
		
		double base = getNcpBaseSpeed(player);
		double distance;
		double y = delta.y;
		
		if(player.onGround())
		{
			player.jumpFromGround();
			y = player.getDeltaMovement().y;
			distance = base * hopBoost.getValue();
			hopStage = 1;
			
		}else if(hopStage == 1)
		{
			// a bit more than the 66% that NoCheatPlus needs
			distance = lastDistance - 0.67 * (lastDistance - base);
			hopStage = 2;
			
		}else if(hopStage == 2)
			// a bit more than the 1/160 that NoCheatPlus needs
			distance = lastDistance - lastDistance / 150;
		else
			return delta;
		
		Vec3 move = new Vec3(dir.x * distance, y, dir.z * distance);
		player.setDeltaMovement(move);
		return move;
	}
	
	/**
	 * Remembers how far you actually moved, since walls can stop you before
	 * the planned distance. Called from LocalPlayerMixin.
	 */
	public void onMoved(LocalPlayer player, double prevX, double prevZ)
	{
		lastDistance = Math.hypot(player.getX() - prevX, player.getZ() - prevZ);
	}
	
	/**
	 * The speed that NoCheatPlus allows without hopping. Speed potions
	 * raise it by 20% per level.
	 */
	public static double getNcpBaseSpeed(LocalPlayer player)
	{
		double base = player.isSprinting() ? NCP_SPRINT_SPEED : NCP_WALK_SPEED;
		
		MobEffectInstance speedEffect = player.getEffect(MobEffects.SPEED);
		if(speedEffect != null)
			base *= 1 + 0.2 * (speedEffect.getAmplifier() + 1);
		
		return base;
	}
	
	public static boolean canControlMovement(LocalPlayer player)
	{
		return !player.isInWater() && !player.isInLava()
			&& !player.onClimbable() && !player.isFallFlying()
			&& !player.getAbilities().flying && !player.isPassenger();
	}
	
	/**
	 * The direction you are holding, as a unit vector, or null if you aren't
	 * holding any movement keys.
	 */
	public static Vec3 getInputDirection(LocalPlayer player)
	{
		float forward = player.zza;
		float sideways = player.xxa;
		if(forward == 0 && sideways == 0)
			return null;
		
		float yaw = player.getYRot() * Mth.DEG_TO_RAD;
		double sin = Mth.sin(yaw);
		double cos = Mth.cos(yaw);
		double wx = sideways * cos - forward * sin;
		double wz = forward * cos + sideways * sin;
		double length = Math.sqrt(wx * wx + wz * wz);
		return new Vec3(wx / length, 0, wz / length);
	}
	
	private enum Mode
	{
		VANILLA("Vanilla"),
		NCPHOP("NCPHop");
		
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
