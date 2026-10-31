/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.mixin.boatfly;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.vehicle.boat.AbstractBoat;
import net.wurstclient.WurstClient;
import net.wurstclient.hacks.BoatFlyHack;

@Mixin(LocalPlayer.class)
public abstract class LocalPlayerMixin
{
	/**
	 * Turns off the boat's own controls when BoatFly moves it in the
	 * direction of the movement keys instead.
	 */
	@WrapOperation(method = "rideTick()V",
		at = @At(value = "INVOKE",
			target = "Lnet/minecraft/world/entity/vehicle/boat/AbstractBoat;setInput(ZZZZ)V"))
	private void onSetBoatInput(AbstractBoat boat, boolean left, boolean right,
		boolean forward, boolean backward, Operation<Void> original)
	{
		if(WurstClient.INSTANCE.getHax().boatFlyHack
			.shouldOverrideBoatControls())
			original.call(boat, false, false, false, false);
		else
			original.call(boat, left, right, forward, backward);
	}
	
	/**
	 * Lets you eat, attack and use items while moving a boat when BoatFly is
	 * enabled. Vanilla only blocks it on the client, the server allows it.
	 */
	@Inject(method = "isHandsBusy()Z", at = @At("HEAD"), cancellable = true)
	private void onIsHandsBusy(CallbackInfoReturnable<Boolean> cir)
	{
		BoatFlyHack boatFly = WurstClient.INSTANCE.getHax().boatFlyHack;
		if(boatFly.isEnabled())
			cir.setReturnValue(false);
	}
}
