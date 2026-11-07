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

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import net.wurstclient.WurstClient;
import net.wurstclient.event.EventManager;
import net.wurstclient.events.FlyingSpeedListener.FlyingSpeedEvent;

@Mixin(Player.class)
public abstract class PlayerMixin
{
	@ModifyReturnValue(method = "getFlyingSpeed()F", at = @At("RETURN"))
	private float onGetFlyingSpeed(float original)
	{
		if((Object)this != WurstClient.MC.player)
			return original;
		
		FlyingSpeedEvent event = new FlyingSpeedEvent(original);
		EventManager.fire(event);
		return event.getSpeed();
	}
	
	/**
	 * Skips the slowdown of a knockback hit while KeepSprint is enabled.
	 */
	@WrapOperation(method = "causeExtraKnockback",
		at = @At(value = "INVOKE",
			target = "Lnet/minecraft/world/entity/player/Player;setDeltaMovement(Lnet/minecraft/world/phys/Vec3;)V"))
	private void wrapKnockbackSlowdown(Player player, Vec3 deltaMovement,
		Operation<Void> original)
	{
		if(!WurstClient.INSTANCE.getHax().keepSprintHack
			.shouldKeepSprint(player))
			original.call(player, deltaMovement);
	}
	
	/**
	 * Skips the sprint stop of a knockback hit while KeepSprint is enabled.
	 */
	@WrapOperation(method = "causeExtraKnockback",
		at = @At(value = "INVOKE",
			target = "Lnet/minecraft/world/entity/player/Player;setSprinting(Z)V"))
	private void wrapKnockbackSprintStop(Player player, boolean sprinting,
		Operation<Void> original)
	{
		if(!WurstClient.INSTANCE.getHax().keepSprintHack
			.shouldKeepSprint(player))
			original.call(player, sprinting);
	}
	
	/**
	 * Lets AirBreak mine as fast in the air as on the ground.
	 */
	@WrapOperation(
		method = "getDestroySpeed(Lnet/minecraft/world/level/block/state/BlockState;)F",
		at = @At(value = "INVOKE",
			target = "Lnet/minecraft/world/entity/player/Player;onGround()Z"))
	private boolean wrapDestroySpeedOnGround(Player player,
		Operation<Boolean> original)
	{
		if(player == WurstClient.MC.player
			&& WurstClient.INSTANCE.getHax().airBreakHack.isActive())
			return true;
		
		return original.call(player);
	}
}
