/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.mojang.authlib.GameProfile;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.Holder;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.phys.Vec2;
import net.wurstclient.InputFaker;
import net.wurstclient.InputFaker.TempRealInput;
import net.wurstclient.WurstClient;
import net.wurstclient.event.EventManager;
import net.wurstclient.events.IsPlayerInWaterListener.IsPlayerInWaterEvent;
import net.wurstclient.events.MobEffectListener.MobEffectEvent;
import net.wurstclient.events.PostMotionListener.PostMotionEvent;
import net.wurstclient.events.PreMotionListener.PreMotionEvent;
import net.wurstclient.events.UpdateListener.UpdateEvent;
import net.wurstclient.hack.HackList;
import net.wurstclient.mixinterface.ILocalPlayer;
import net.wurstclient.util.JumpUtils;

@Mixin(LocalPlayer.class)
public abstract class LocalPlayerMixin extends AbstractClientPlayer
	implements ILocalPlayer
{
	private LocalPlayerMixin(WurstClient wurst, ClientLevel world,
		GameProfile profile)
	{
		super(world, profile);
	}
	
	@Inject(method = {"tick()V", "rideTick()V"}, at = @At("HEAD"))
	private void onTickHead(CallbackInfo ci)
	{
		InputFaker.swapIfNeeded();
	}
	
	@Inject(method = {"tick()V", "rideTick()V"}, at = @At("RETURN"))
	private void onInputFakerReturn(CallbackInfo ci)
	{
		InputFaker.restoreIfNeeded();
	}
	
	@Inject(method = "tick()V",
		at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/player/AbstractClientPlayer;tick()V",
			ordinal = 0))
	private void onTick(CallbackInfo ci)
	{
		WurstClient.INSTANCE.getHax().antiKnockbackHack
			.releaseDelayedKnockback();
		
		try(TempRealInput _ = new TempRealInput())
		{
			EventManager.fire(UpdateEvent.INSTANCE);
		}
	}
	
	/**
	 * Changes the keys after the game reads them, so the server sees the
	 * changed input too: jumps from JumpUtils, Legit Sneak, and BoatFly
	 * hiding the sneak key while it uses it to fly down (so the server doesn't
	 * make you get out of the vehicle).
	 */
	@Inject(method = "aiStep()V",
		at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/player/ClientInput;tick()V",
			shift = At.Shift.AFTER))
	private void onInputTick(CallbackInfo ci)
	{
		LocalPlayer player = (LocalPlayer)(Object)this;
		Input keys = player.input.keyPresses;
		
		boolean jump = JumpUtils.modifyJumpKey(player, keys.jump());
		HackList hax = WurstClient.INSTANCE.getHax();
		boolean shift = (keys.shift() || hax.sneakHack.shouldForceSneak())
			&& !hax.boatFlyHack.shouldHideSneak();
		boolean releaseForward = hax.wTapHack.shouldReleaseForward();
		boolean forward = keys.forward() && !releaseForward;
		
		if(jump != keys.jump() || shift != keys.shift())
			player.input.keyPresses = new Input(keys.forward(), keys.backward(),
				keys.left(), keys.right(), jump, shift, keys.sprint());
		
		// W-Tap: the movement vector was already calculated from the keys
		if(forward != keys.forward())
		{
			keys = player.input.keyPresses;
			player.input.keyPresses =
				new Input(forward, keys.backward(), keys.left(), keys.right(),
					keys.jump(), keys.shift(), keys.sprint());
			
			float forwardImpulse = keys.backward() ? -1 : 0;
			float leftImpulse =
				keys.left() == keys.right() ? 0 : keys.left() ? 1 : -1;
			((ClientInputAccessor)player.input).wurst_setMoveVector(
				new Vec2(leftImpulse, forwardImpulse).normalized());
		}
	}
	
	@Inject(method = "sendPosition()V", at = @At("HEAD"))
	private void onSendPositionHEAD(CallbackInfo ci)
	{
		EventManager.fire(PreMotionEvent.INSTANCE);
		
		// Run RotationFaker after events so PreMotionListeners can use it
		if(WurstClient.INSTANCE.isEnabled())
			WurstClient.INSTANCE.getRotationFaker().onPreMotion();
	}
	
	@Inject(method = "sendPosition()V", at = @At("TAIL"))
	private void onSendPositionTAIL(CallbackInfo ci)
	{
		if(WurstClient.INSTANCE.isEnabled())
			WurstClient.INSTANCE.getRotationFaker().onPostMotion();
		
		EventManager.fire(PostMotionEvent.INSTANCE);
	}
	
	/**
	 * Prevents vanilla auto-jump from interfering with Step and .goto.
	 */
	@ModifyExpressionValue(method = "updateAutoJump(FF)V",
		at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/player/LocalPlayer;canAutoJump()Z"))
	private boolean modifyCanAutoJump(boolean original)
	{
		return original && !WurstClient.INSTANCE.getHax().stepHack.isEnabled()
			&& !WurstClient.INSTANCE.getCmds().goToCmd.isActive();
	}
	
	@Override
	public float getSpeed()
	{
		return super.getSpeed()
			* WurstClient.INSTANCE.getHax().speedHackHack.getSpeedMultiplier();
	}
	
	@Override
	public boolean isInWater()
	{
		boolean inWater = super.isInWater();
		IsPlayerInWaterEvent event = new IsPlayerInWaterEvent(inWater);
		EventManager.fire(event);
		
		return event.isInWater();
	}
	
	@Override
	public boolean isInWaterBypass()
	{
		return super.isInWater();
	}
	
	@Override
	public boolean hasEffect(Holder<MobEffect> effect)
	{
		return getEffect(effect) != null;
	}
	
	@Override
	public MobEffectInstance getEffect(Holder<MobEffect> effect)
	{
		MobEffectEvent event =
			new MobEffectEvent(effect, super.getEffect(effect));
		EventManager.fire(event);
		return event.getInstance();
	}
}
