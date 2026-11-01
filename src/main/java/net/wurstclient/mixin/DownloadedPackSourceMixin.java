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

import net.minecraft.client.resources.server.DownloadedPackSource;
import net.minecraft.client.resources.server.PackReloadConfig;
import net.minecraft.server.packs.repository.RepositorySource;
import net.wurstclient.WurstClient;
import net.wurstclient.other_features.PacksBypassOtf;

@Mixin(DownloadedPackSource.class)
public abstract class DownloadedPackSourceMixin implements AutoCloseable
{
	@Shadow
	@Final
	private static RepositorySource EMPTY_SOURCE;
	
	@Shadow
	private RepositorySource packSource;
	
	/**
	 * Skips the resource reload for packs from PacksBypass's "Bypass" mode, as
	 * long as no real server pack is loaded that would have to be removed.
	 */
	@Inject(
		method = "startReload(Lnet/minecraft/client/resources/server/PackReloadConfig$Callbacks;)V",
		at = @At("HEAD"),
		cancellable = true)
	private void onStartReload(PackReloadConfig.Callbacks callbacks,
		CallbackInfo ci)
	{
		PacksBypassOtf packsBypass =
			WurstClient.INSTANCE.getOtfs().packsBypassOtf;
		if(packSource != EMPTY_SOURCE
			|| !packsBypass.isBypassed(callbacks.packsToLoad()))
			return;
		
		packsBypass.fakeReload(callbacks);
		ci.cancel();
	}
	
	@Inject(method = "cleanupAfterDisconnect()V", at = @At("HEAD"))
	private void onCleanupAfterDisconnect(CallbackInfo ci)
	{
		WurstClient.INSTANCE.getOtfs().packsBypassOtf.forgetBypassedPacks();
		WurstClient.INSTANCE.getOtfs().noModDetectionOtf.onDisconnect();
	}
}
