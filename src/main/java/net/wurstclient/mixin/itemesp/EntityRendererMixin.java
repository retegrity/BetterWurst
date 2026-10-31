/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.mixin.itemesp;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.entity.state.ItemEntityRenderState;

import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.wurstclient.WurstClient;
import net.wurstclient.hacks.ItemEspHack;

@Mixin(EntityRenderer.class)
public abstract class EntityRendererMixin<T extends Entity, S extends EntityRenderState>
{
	/**
	 * Makes dropped items show a nameplate if enabled in ItemESP.
	 */
	@Inject(method = "shouldShowName(Lnet/minecraft/world/entity/Entity;D)Z",
		at = @At("HEAD"),
		cancellable = true)
	private void onShouldShowName(T entity, double distanceSq,
		CallbackInfoReturnable<Boolean> cir)
	{
		if(entity instanceof ItemEntity
			&& WurstClient.INSTANCE.getHax().itemEspHack.shouldShowNames())
			cir.setReturnValue(true);
	}
	
	/**
	 * Shows the stack size in front of the item name.
	 */
	@Inject(
		method = "getNameTag(Lnet/minecraft/world/entity/Entity;)Lnet/minecraft/network/chat/Component;",
		at = @At("HEAD"),
		cancellable = true)
	private void onGetNameTag(T entity, CallbackInfoReturnable<Component> cir)
	{
		if(!(entity instanceof ItemEntity itemEntity)
			|| !WurstClient.INSTANCE.getHax().itemEspHack.shouldShowNames())
			return;
		
		ItemStack stack = itemEntity.getItem();
		Component name = stack.getHoverName();
		if(stack.getCount() > 1)
			name = Component.literal(stack.getCount() + "x ").append(name);
		
		cir.setReturnValue(name);
	}
	
	/**
	 * Marks the name tag drawing of dropped items, so that the shared name tag
	 * code can give them their own scale and make them see-through.
	 */
	@WrapMethod(
		method = "submitNameDisplay(Lnet/minecraft/client/renderer/entity/state/EntityRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/state/level/CameraRenderState;I)V")
	private void wrapSubmitNameDisplay(EntityRenderState state,
		PoseStack matrices, SubmitNodeCollector collector,
		CameraRenderState camera, int offset, Operation<Void> original)
	{
		ItemEspHack itemEsp = WurstClient.INSTANCE.getHax().itemEspHack;
		boolean item =
			state instanceof ItemEntityRenderState && itemEsp.shouldShowNames();
		
		itemEsp.setRenderingNameTag(item);
		try
		{
			original.call(state, matrices, collector, camera, offset);
			
		}finally
		{
			itemEsp.setRenderingNameTag(false);
		}
	}
}
