/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.phys.Vec3;
import net.wurstclient.Category;
import net.wurstclient.SearchTags;
import net.wurstclient.events.FlyingSpeedListener;
import net.wurstclient.events.IsPlayerInWaterListener;
import net.wurstclient.events.MouseScrollListener;
import net.wurstclient.events.UpdateListener;
import net.wurstclient.hack.Hack;
import net.wurstclient.hacks.flight.RewoundFlight;
import net.wurstclient.mixinterface.IKeyMapping;
import net.wurstclient.settings.CheckboxSetting;
import net.wurstclient.settings.EnumSetting;
import net.wurstclient.settings.SliderSetting;
import net.wurstclient.settings.SliderSetting.ValueDisplay;

@SearchTags({"FlyHack", "fly hack", "flying", "NCP glide", "Rewound"})
public final class FlightHack extends Hack implements UpdateListener,
	IsPlayerInWaterListener, FlyingSpeedListener, MouseScrollListener
{
	private final EnumSetting<Mode> mode = new EnumSetting<>("Mode",
		"\u00a7lVanilla\u00a7r lets you fly freely.\n\n"
			+ "\u00a7lRewound\u00a7r glides just within NoCheatPlus' limits."
			+ " Jump to take off, sneak to sink faster. Made for Rewound"
			+ " (Hypixel 2020).",
		Mode.values(), Mode.VANILLA);
	
	private final SliderSetting glideSpeed =
		new SliderSetting("Glide speed (Rewound)",
			"How fast Rewound mode sinks. You can glide until you are 1 block"
				+ " below where you left the ground.",
			0.001, RewoundFlight.MIN_GLIDE_SPEED, 0.05, 0.001,
			ValueDisplay.DECIMAL.withSuffix(" blocks/tick"));
	
	private final SliderSetting horizontalSpeed = new SliderSetting(
		"Horizontal speed", "description.wurst.setting.flight.horizontal_speed",
		1, 0.05, 10, 0.05, ValueDisplay.DECIMAL);
	
	private final SliderSetting verticalSpeed =
		new SliderSetting("Vertical speed",
			"description.wurst.setting.flight.vertical_speed", 1, 0.05, 5, 0.05,
			v -> ValueDisplay.DECIMAL.getValueString(getActualVerticalSpeed()));
	
	private final CheckboxSetting allowUnsafeVerticalSpeed =
		new CheckboxSetting("Allow unsafe vertical speed",
			"description.wurst.setting.flight.allow_unsafe_vertical_speed",
			false);
	
	private final CheckboxSetting scrollToChangeSpeed =
		new CheckboxSetting("Scroll to change speed",
			"description.wurst.setting.flight.scroll_to_change_speed", true);
	
	private final CheckboxSetting renderSpeed =
		new CheckboxSetting("Show speed in HackList",
			"description.wurst.setting.flight.show_speed_in_hacklist", true);
	
	private final CheckboxSetting antiKick = new CheckboxSetting("Anti-Kick",
		"description.wurst.setting.flight.anti-kick", false);
	
	private final SliderSetting antiKickInterval =
		new SliderSetting("Anti-Kick Interval",
			"description.wurst.setting.flight.anti-kick_interval", 70, 5, 80, 1,
			ValueDisplay.INTEGER.withSuffix(" ticks").withLabel(1, "1 tick"));
	
	private final SliderSetting antiKickDistance =
		new SliderSetting("Anti-Kick Distance",
			"description.wurst.setting.flight.anti-kick_distance", 0.035, 0.01,
			0.2, 0.001, ValueDisplay.DECIMAL.withSuffix("m"));
	
	private final RewoundFlight rewound = new RewoundFlight();
	
	private int tickCounter = 0;
	private boolean sinkFaster;
	
	public FlightHack()
	{
		super("Flight");
		setCategory(Category.MOVEMENT);
		addSetting(mode);
		addSetting(glideSpeed);
		addSetting(horizontalSpeed);
		addSetting(verticalSpeed);
		addSetting(allowUnsafeVerticalSpeed);
		addSetting(scrollToChangeSpeed);
		addSetting(renderSpeed);
		addSetting(antiKick);
		addSetting(antiKickInterval);
		addSetting(antiKickDistance);
	}
	
	@Override
	public String getRenderName()
	{
		if(mode.getSelected() == Mode.REWOUND)
		{
			// how much height is left to glide
			LocalPlayer player = MC.player;
			if(player == null || player.onGround())
				return getName() + " [Rewound]";
			
			return getName() + " [Rewound, "
				+ String.format("%.2f", rewound.getGlideLeft(player)) + "]";
		}
		
		if(!renderSpeed.isChecked())
			return getName();
		
		return getName() + " [" + horizontalSpeed.getValueString() + ", "
			+ verticalSpeed.getValueString() + "]";
	}
	
	@Override
	protected void onEnable()
	{
		tickCounter = 0;
		rewound.reset();
		
		WURST.getHax().creativeFlightHack.setEnabled(false);
		WURST.getHax().jetpackHack.setEnabled(false);
		
		EVENTS.add(UpdateListener.class, this);
		EVENTS.add(IsPlayerInWaterListener.class, this);
		EVENTS.add(FlyingSpeedListener.class, this);
		EVENTS.add(MouseScrollListener.class, this);
	}
	
	@Override
	protected void onDisable()
	{
		EVENTS.remove(UpdateListener.class, this);
		EVENTS.remove(IsPlayerInWaterListener.class, this);
		EVENTS.remove(FlyingSpeedListener.class, this);
		EVENTS.remove(MouseScrollListener.class, this);
		rewound.reset();
	}
	
	@Override
	public void onUpdate()
	{
		if(!isVanilla())
		{
			updateSinkFaster();
			return;
		}
		
		LocalPlayer player = MC.player;
		player.setDeltaMovement(Vec3.ZERO);
		player.getAbilities().flying = false;
		
		if(WURST.getHax().freecamHack.isMovingCamera())
			return;
		
		double vSpeed = getActualVerticalSpeed();
		
		if(MC.options.keyJump.isDown())
			player.addDeltaMovement(new Vec3(0, vSpeed, 0));
		
		if(IKeyMapping.get(MC.options.keyShift).isActuallyDown())
		{
			MC.options.keyShift.setDown(false);
			
			// isActuallyDown() ignores open screens, so check for those here
			if(MC.screen == null)
				player.addDeltaMovement(new Vec3(0, -vSpeed, 0));
		}
		
		if(antiKick.isChecked())
			doAntiKick();
	}
	
	@Override
	public void onGetFlyingSpeed(FlyingSpeedEvent event)
	{
		if(!isVanilla() || WURST.getHax().freecamHack.isMovingCamera())
			return;
		
		event.setSpeed(horizontalSpeed.getValueF());
	}
	
	@Override
	public void onMouseScroll(double amount)
	{
		if(!isControllingScrollEvents())
			return;
		
		if(amount > 0)
			horizontalSpeed.increaseValue();
		else if(amount < 0)
			horizontalSpeed.decreaseValue();
	}
	
	public boolean isControllingScrollEvents()
	{
		return isVanilla() && scrollToChangeSpeed.isChecked()
			&& MC.screen == null
			&& !WURST.getOtfs().zoomOtf.isControllingScrollEvents()
			&& !WURST.getHax().freecamHack.isMovingCamera();
	}
	
	private void doAntiKick()
	{
		if(tickCounter > antiKickInterval.getValueI() + 1)
			tickCounter = 0;
		
		Vec3 velocity = MC.player.getDeltaMovement();
		
		switch(tickCounter)
		{
			case 0 ->
			{
				if(velocity.y <= -antiKickDistance.getValue())
					tickCounter = 2;
				else
					MC.player.setDeltaMovement(velocity.x,
						-antiKickDistance.getValue(), velocity.z);
			}
			
			case 1 -> MC.player.setDeltaMovement(velocity.x,
				antiKickDistance.getValue(), velocity.z);
		}
		
		tickCounter++;
	}
	
	@Override
	public void onIsPlayerInWater(IsPlayerInWaterEvent event)
	{
		if(isVanilla())
			event.setInWater(false);
	}
	
	/**
	 * Whether Flight is making you fly freely. Rewound mode doesn't, so
	 * other hacks should treat you as walking.
	 */
	public boolean isFlying()
	{
		return isVanilla();
	}
	
	private boolean isVanilla()
	{
		return isEnabled() && mode.getSelected() == Mode.VANILLA;
	}
	
	private boolean isRewound()
	{
		return isEnabled() && mode.getSelected() == Mode.REWOUND;
	}
	
	/**
	 * In Rewound mode, sneaking in the air sinks faster instead.
	 */
	private void updateSinkFaster()
	{
		sinkFaster = false;
		if(MC.player.onGround()
			|| !IKeyMapping.get(MC.options.keyShift).isActuallyDown())
			return;
		
		MC.options.keyShift.setDown(false);
		
		// isActuallyDown() ignores open screens, so check for those here
		sinkFaster = MC.screen == null;
	}
	
	/**
	 * Lets Rewound mode replace the movement of this tick. Called by
	 * LocalPlayerMixin right before you move.
	 */
	public Vec3 modifyMove(LocalPlayer player, Vec3 delta)
	{
		if(!isRewound())
		{
			rewound.reset();
			return delta;
		}
		
		return rewound.modifyMove(player, delta, glideSpeed.getValue(),
			sinkFaster);
	}
	
	/**
	 * Called by LocalPlayerMixin right after you moved.
	 */
	public void onMoved(LocalPlayer player)
	{
		if(isRewound())
			rewound.onMoved(player);
	}
	
	/**
	 * Whether Rewound mode is controlling your movement, in which case
	 * SpeedHack shouldn't.
	 */
	public boolean isControllingMovement()
	{
		return isRewound() && rewound.isControlling();
	}
	
	public double getHorizontalSpeed()
	{
		return horizontalSpeed.getValue();
	}
	
	public double getActualVerticalSpeed()
	{
		boolean limitVerticalSpeed = !allowUnsafeVerticalSpeed.isChecked()
			&& !MC.player.getAbilities().invulnerable;
		
		return Math.clamp(horizontalSpeed.getValue() * verticalSpeed.getValue(),
			0.05, limitVerticalSpeed ? 3.95 : 10);
	}
	
	private enum Mode
	{
		VANILLA("Vanilla"),
		REWOUND("Rewound");
		
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
