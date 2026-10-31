/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.wurstclient.Category;
import net.wurstclient.SearchTags;
import net.wurstclient.events.UpdateListener;
import net.wurstclient.hack.Hack;
import net.wurstclient.mixinterface.IKeyMapping;
import net.wurstclient.settings.CheckboxSetting;
import net.wurstclient.settings.SliderSetting;
import net.wurstclient.settings.SliderSetting.ValueDisplay;
import net.wurstclient.util.JumpUtils;

@SearchTags({"elytra recast", "ElytraBounce", "elytra bounce"})
public final class ElytraRecastHack extends Hack implements UpdateListener
{
	private final CheckboxSetting requireJump = new CheckboxSetting(
		"Require jump key",
		"Only bounces and re-opens your elytra while you are holding the jump"
			+ " key. When disabled, it happens whenever you have an elytra"
			+ " equipped.",
		true);
	
	private final CheckboxSetting lockPitch = new CheckboxSetting("Lock pitch",
		"Locks your pitch to the angle below while bouncing.", false);
	
	private final SliderSetting pitch = new SliderSetting("Pitch",
		"The pitch that \u00a7lLock pitch\u00a7r keeps you at.", 85, 0, 90, 1,
		ValueDisplay.DEGREES);
	
	private boolean controllingJump;
	
	public ElytraRecastHack()
	{
		super("ElytraRecast");
		setCategory(Category.MOVEMENT);
		addSetting(requireJump);
		addSetting(lockPitch);
		addSetting(pitch);
	}
	
	@Override
	protected void onEnable()
	{
		controllingJump = false;
		EVENTS.add(UpdateListener.class, this);
	}
	
	@Override
	protected void onDisable()
	{
		EVENTS.remove(UpdateListener.class, this);
		releaseJumpKey();
	}
	
	/*
	 * Everything here only changes the jump key, which vanilla reads later in
	 * this tick. Vanilla then does the jumping and the elytra opening itself,
	 * so the packets are sent in the normal order (no Post / PacketOrder
	 * flags) and every recast comes from a fresh jump press (no ElytraB
	 * "no release" flag).
	 */
	@Override
	public void onUpdate()
	{
		LocalPlayer player = MC.player;
		if(!isActive(player)
			|| WURST.getHax().extraElytraHack.isInstantFlyActive())
		{
			releaseJumpKey();
			return;
		}
		
		if(player.onGround())
		{
			// keep the sprint-jump speed boost
			if(MC.options.keyUp.isDown())
				player.setSprinting(true);
			
			// hold jump and remove the jump delay so vanilla jumps right away
			JumpUtils.skipJumpDelay();
			setJumpKey(true);
			return;
		}
		
		if(lockPitch.isChecked())
			player.setXRot(pitch.getValueF());
			
		// Keep jump released while gliding, even if you hold space. When you
		// hit the ground, the server stops the glide, but its packet only
		// arrives after the jump above, when you are in the air again. Then
		// the next tick can press jump fresh and reopen the elytra right
		// away, so the flight doesn't stop.
		if(player.isFallFlying())
		{
			setJumpKey(false);
			return;
		}
		
		if(player.onClimbable() || !player.canGlide())
		{
			releaseJumpKey();
			return;
		}
		
		// in the air but not flying: release jump for one tick, then press it
		// again, so vanilla sees a fresh press and opens the elytra
		boolean jumpedLastTick = player.input.keyPresses.jump();
		setJumpKey(!jumpedLastTick);
	}
	
	private void setJumpKey(boolean down)
	{
		MC.options.keyJump.setDown(down);
		controllingJump = true;
	}
	
	private void releaseJumpKey()
	{
		if(!controllingJump)
			return;
		
		IKeyMapping.get(MC.options.keyJump).resetDownState();
		controllingJump = false;
	}
	
	private boolean isActive(LocalPlayer player)
	{
		if(player == null)
			return false;
		
		// don't jump while you are typing in chat, in your inventory, etc.
		if(!WURST.getHax().invWalkHack.canPressKey(MC.options.keyJump))
			return false;
		
		if(requireJump.isChecked()
			&& !IKeyMapping.get(MC.options.keyJump).isActuallyDown())
			return false;
		
		if(player.isInWater() || player.isInLava() || player.isPassenger()
			|| player.getAbilities().flying
			|| player.hasEffect(MobEffects.LEVITATION))
			return false;
		
		return hasGlider(player);
	}
	
	private boolean hasGlider(LocalPlayer player)
	{
		for(EquipmentSlot slot : EquipmentSlot.VALUES)
			if(LivingEntity.canGlideUsing(player.getItemBySlot(slot), slot))
				return true;
			
		return false;
	}
}
