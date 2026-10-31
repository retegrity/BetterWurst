/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.mixin.longfirework;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

import it.unimi.dsi.fastutil.ints.IntConsumer;
import it.unimi.dsi.fastutil.ints.IntList;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.wurstclient.WurstClient;

@Mixin(ClientPacketListener.class)
public abstract class ClientPacketListenerMixin
{
	/**
	 * Lets LongFirework keep your own fireworks around for longer instead of
	 * removing them when the server says they are gone.
	 */
	@WrapOperation(
		method = "handleRemoveEntities(Lnet/minecraft/network/protocol/game/ClientboundRemoveEntitiesPacket;)V",
		at = @At(value = "INVOKE",
			target = "Lit/unimi/dsi/fastutil/ints/IntList;forEach(Lit/unimi/dsi/fastutil/ints/IntConsumer;)V"))
	private void delayFireworkRemoval(IntList ids, IntConsumer remover,
		Operation<Void> original)
	{
		original.call(ids, (IntConsumer)id -> {
			if(!WurstClient.INSTANCE.getHax().longFireworkHack.delayRemoval(id,
				remover))
				remover.accept(id);
		});
	}
}
