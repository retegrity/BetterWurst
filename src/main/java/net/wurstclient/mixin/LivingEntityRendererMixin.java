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

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.wurstclient.WurstClient;
import net.wurstclient.hacks.RotationsHack;

@Mixin(LivingEntityRenderer.class)
public abstract class LivingEntityRendererMixin
{
	@Inject(at = @At("TAIL"),
		method = "extractRenderState(Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/client/renderer/entity/state/LivingEntityRenderState;F)V")
	private void wurst$applyServerRotations(LivingEntity entity,
		LivingEntityRenderState state, float partialTicks, CallbackInfo ci)
	{
		if(entity != Minecraft.getInstance().player)
			return;
		
		RotationsHack hack = WurstClient.INSTANCE.getHax().rotationsHack;
		if(hack == null || !hack.isReady())
			return;
		
		float yaw = hack.getRenderYaw(partialTicks);
		float pitch = hack.getRenderPitch(partialTicks);
		
		// keep the body within vanilla's 75° head-turn limit
		float diff = Mth.wrapDegrees(yaw - state.bodyRot);
		if(diff > 75F)
			state.bodyRot = yaw - 75F;
		else if(diff < -75F)
			state.bodyRot = yaw + 75F;
		
		// yRot in the render state is head yaw relative to the body
		state.yRot = Mth.wrapDegrees(yaw - state.bodyRot);
		state.xRot = pitch;
	}
}
