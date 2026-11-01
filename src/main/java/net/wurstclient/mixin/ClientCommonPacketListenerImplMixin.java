/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.mixin;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

import net.minecraft.client.multiplayer.ClientCommonPacketListenerImpl;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ClientCommonPacketListener;
import net.minecraft.network.protocol.common.ClientboundResourcePackPushPacket;
import net.wurstclient.WurstClient;
import net.wurstclient.event.EventManager;
import net.wurstclient.events.PacketOutputListener.PacketOutputEvent;
import net.wurstclient.other_features.PacksBypassOtf;

@Mixin(ClientCommonPacketListenerImpl.class)
public abstract class ClientCommonPacketListenerImplMixin
	implements ClientCommonPacketListener
{
	@Shadow
	@Final
	protected Connection connection;
	
	@Shadow
	@Final
	protected ServerData serverData;
	
	/**
	 * PacksBypass's "Bypass" mode. Runs after vanilla has checked the pack's
	 * URL, so that an invalid URL is still answered like vanilla would.
	 */
	@Inject(
		method = "handleResourcePackPush(Lnet/minecraft/network/protocol/common/ClientboundResourcePackPushPacket;)V",
		at = @At(value = "INVOKE",
			target = "Lnet/minecraft/network/protocol/common/ClientboundResourcePackPushPacket;hash()Ljava/lang/String;"),
		cancellable = true)
	private void onHandleResourcePackPush(
		ClientboundResourcePackPushPacket packet, CallbackInfo ci)
	{
		PacksBypassOtf packsBypass =
			WurstClient.INSTANCE.getOtfs().packsBypassOtf;
		if(!packsBypass.isBypassing(serverData))
			return;
		
		packsBypass.fakeLoadPack(connection, packet.id());
		ci.cancel();
	}
	
	@WrapOperation(method = "send(Lnet/minecraft/network/protocol/Packet;)V",
		at = @At(value = "INVOKE",
			target = "Lnet/minecraft/network/Connection;send(Lnet/minecraft/network/protocol/Packet;)V"))
	private void wrapSend(Connection connection, Packet<?> packet,
		Operation<Void> original)
	{
		PacketOutputEvent event = new PacketOutputEvent(packet);
		EventManager.fire(event);
		
		if(!event.isCancelled())
			original.call(connection, event.getPacket());
	}
}
