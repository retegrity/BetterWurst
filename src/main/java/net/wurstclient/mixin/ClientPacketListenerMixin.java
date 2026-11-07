/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.mixin;

import java.util.List;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.client.multiplayer.ClientCommonPacketListenerImpl;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.CommonListenerCookie;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.Connection;
import net.minecraft.network.TickablePacketListener;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundLoginPacket;
import net.minecraft.network.protocol.game.ClientboundUpdateAttributesPacket;
import net.minecraft.network.syncher.SynchedEntityData;
import net.wurstclient.WurstClient;
import net.wurstclient.util.ChatUtils;

@Mixin(ClientPacketListener.class)
public abstract class ClientPacketListenerMixin
	extends ClientCommonPacketListenerImpl
	implements TickablePacketListener, ClientGamePacketListener
{
	private ClientPacketListenerMixin(WurstClient wurst, Minecraft client,
		Connection connection, CommonListenerCookie connectionState)
	{
		super(client, connection, connectionState);
	}
	
	@Inject(
		method = "handleLogin(Lnet/minecraft/network/protocol/game/ClientboundLoginPacket;)V",
		at = @At("TAIL"))
	public void onHandleLogin(ClientboundLoginPacket packet, CallbackInfo ci)
	{
		WurstClient wurst = WurstClient.INSTANCE;
		if(!wurst.isEnabled())
			return;
		
		// Remove Mojang's dishonest warning toast on safe servers
		if(!packet.enforcesSecureChat())
		{
			minecraft.getToastManager().queued.removeIf(toast -> toast
				.getToken() == SystemToast.SystemToastId.UNSECURE_SERVER_WARNING);
			return;
		}
		
		// Add an honest warning toast on unsafe servers
		MutableComponent title = Component.literal(ChatUtils.WURST_PREFIX
			+ wurst.translate("toast.wurst.nochatreports.unsafe_server.title"));
		MutableComponent message = Component.literal(
			wurst.translate("toast.wurst.nochatreports.unsafe_server.message"));
		
		SystemToast systemToast = SystemToast.multiline(minecraft,
			SystemToast.SystemToastId.UNSECURE_SERVER_WARNING, title, message);
		minecraft.getToastManager().addToast(systemToast);
	}
	
	/**
	 * Lets KeepSprint keep your sprint when the server turns it off after a
	 * knockback hit.
	 */
	@WrapOperation(
		method = "handleSetEntityData(Lnet/minecraft/network/protocol/game/ClientboundSetEntityDataPacket;)V",
		at = @At(value = "INVOKE",
			target = "Lnet/minecraft/network/syncher/SynchedEntityData;assignValues(Ljava/util/List;)V"))
	private void wrapAssignValues(SynchedEntityData entityData,
		List<SynchedEntityData.DataValue<?>> values, Operation<Void> original)
	{
		LocalPlayer player = minecraft.player;
		boolean wasSprinting = player != null
			&& entityData == player.getEntityData() && player.isSprinting();
		original.call(entityData, values);
		
		if(wasSprinting && !player.isSprinting())
			WurstClient.INSTANCE.getHax().keepSprintHack.afterServerUpdate();
	}
	
	/**
	 * Lets KeepSprint put the sprinting bonus back onto your movement speed
	 * when the server sends it without that bonus.
	 */
	@Inject(
		method = "handleUpdateAttributes(Lnet/minecraft/network/protocol/game/ClientboundUpdateAttributesPacket;)V",
		at = @At("TAIL"))
	private void onHandleUpdateAttributes(
		ClientboundUpdateAttributesPacket packet, CallbackInfo ci)
	{
		LocalPlayer player = minecraft.player;
		if(player != null && packet.getEntityId() == player.getId()
			&& player.isSprinting())
			WurstClient.INSTANCE.getHax().keepSprintHack.afterServerUpdate();
	}
}
