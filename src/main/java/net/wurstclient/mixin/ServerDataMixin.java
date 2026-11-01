/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.nbt.CompoundTag;
import net.wurstclient.mixinterface.IServerData;

/**
 * Stores PacksBypass's "Bypass" mode next to the vanilla resource pack status
 * in servers.dat. The vanilla status is set to "Disabled" in that mode, so
 * that the server entry still works without Wurst.
 */
@Mixin(ServerData.class)
public class ServerDataMixin implements IServerData
{
	@Unique
	private static final String PACKS_BYPASS_KEY = "wurstPacksBypass";
	
	@Unique
	private boolean packsBypass;
	
	@Inject(method = "write()Lnet/minecraft/nbt/CompoundTag;",
		at = @At("RETURN"))
	private void onWrite(CallbackInfoReturnable<CompoundTag> cir)
	{
		if(packsBypass)
			cir.getReturnValue().putBoolean(PACKS_BYPASS_KEY, true);
	}
	
	@Inject(
		method = "read(Lnet/minecraft/nbt/CompoundTag;)Lnet/minecraft/client/multiplayer/ServerData;",
		at = @At("RETURN"))
	private static void onRead(CompoundTag tag,
		CallbackInfoReturnable<ServerData> cir)
	{
		((IServerData)cir.getReturnValue())
			.setPacksBypass(tag.getBooleanOr(PACKS_BYPASS_KEY, false));
	}
	
	@Inject(method = "copyFrom(Lnet/minecraft/client/multiplayer/ServerData;)V",
		at = @At("TAIL"))
	private void onCopyFrom(ServerData other, CallbackInfo ci)
	{
		packsBypass = ((IServerData)other).isPacksBypass();
	}
	
	@Override
	public boolean isPacksBypass()
	{
		return packsBypass;
	}
	
	@Override
	public void setPacksBypass(boolean packsBypass)
	{
		this.packsBypass = packsBypass;
	}
}
