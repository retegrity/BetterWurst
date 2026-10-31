/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.mixin.noclip;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour.BlockStateBase;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateHolder;
import net.minecraft.world.level.block.state.properties.Property;
import net.wurstclient.WurstClient;
import net.wurstclient.hack.HackList;

@Mixin(BlockStateBase.class)
public abstract class BlockStateBaseMixin extends StateHolder<Block, BlockState>
{
	private BlockStateBaseMixin(WurstClient wurst, Block owner,
		Property<?>[] properties, Comparable<?>[] values)
	{
		super(owner, properties, values);
	}
	
	/**
	 * Makes NoClip's Old mode treat all blocks as not being full cubes, like
	 * it did in older Wurst versions.
	 */
	@Inject(
		method = "isCollisionShapeFullBlock(Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/core/BlockPos;)Z",
		at = @At("TAIL"),
		cancellable = true)
	private void onIsCollisionShapeFullBlock(BlockGetter world, BlockPos pos,
		CallbackInfoReturnable<Boolean> cir)
	{
		HackList hax = WurstClient.INSTANCE.getHax();
		if(hax != null && hax.noClipHack.isOldMode())
			cir.setReturnValue(false);
	}
}
