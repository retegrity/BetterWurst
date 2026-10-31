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

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.wurstclient.WurstClient;

@Mixin(BlockItem.class)
public abstract class BlockItemMixin
{
	/**
	 * GhostBlocks: don't use up the block when placing it client-side.
	 */
	@WrapOperation(
		method = "place(Lnet/minecraft/world/item/context/BlockPlaceContext;)Lnet/minecraft/world/InteractionResult;",
		at = @At(value = "INVOKE",
			target = "Lnet/minecraft/world/item/ItemStack;consume(ILnet/minecraft/world/entity/LivingEntity;)V"))
	private void wrapConsume(ItemStack stack, int amount, LivingEntity entity,
		Operation<Void> original, BlockPlaceContext context)
	{
		if(entity instanceof LocalPlayer
			&& WurstClient.INSTANCE.getHax().ghostBlocksHack
				.shouldKeepPlacedItem())
			return;
		
		original.call(stack, amount, entity);
	}
}
