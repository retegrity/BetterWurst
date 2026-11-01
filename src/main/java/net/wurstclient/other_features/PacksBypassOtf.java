/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.other_features;

import java.net.URL;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.ServerData.ServerPackStatus;
import net.minecraft.client.resources.server.DownloadedPackSource;
import net.minecraft.client.resources.server.PackReloadConfig;
import net.minecraft.network.chat.Component;
import net.wurstclient.DontBlock;
import net.wurstclient.SearchTags;
import net.wurstclient.mixinterface.IServerData;
import net.wurstclient.other_feature.OtherFeature;

@SearchTags({"Packs Bypass", "ResourcePackBypass", "resource pack bypass",
	"server resource packs", "TexturePackBypass", "ResourcePackSpoof"})
@DontBlock
public final class PacksBypassOtf extends OtherFeature
{
	private final HashSet<UUID> bypassedPacks = new HashSet<>();
	
	public PacksBypassOtf()
	{
		super("PacksBypass", "description.wurst.other_feature.packsbypass");
	}
	
	public CycleButton<PackMode> createPackStatusButton(ServerData serverData,
		int x, int y, int width, int height, Component name)
	{
		return CycleButton.builder(PackMode::getName, PackMode.of(serverData))
			.withValues(PackMode.values()).create(x, y, width, height, name,
				(button, mode) -> mode.applyTo(serverData));
	}
	
	public boolean isBypassing(ServerData serverData)
	{
		return serverData != null && ((IServerData)serverData).isPacksBypass();
	}
	
	/**
	 * Lets vanilla accept and download the pack (or take it from the cache)
	 * like normal, so that the server sees the same responses and download as
	 * from a real client. Only the resource reload that would apply the pack is
	 * skipped later, see {@link #fakeReload(PackReloadConfig.Callbacks)}.
	 */
	public void pushPack(UUID id, URL url, String hash)
	{
		bypassedPacks.add(id);
		DownloadedPackSource packSource = MC.getDownloadedPackSource();
		packSource.allowServerPacks();
		packSource.pushPack(id, url, hash);
	}
	
	public boolean isBypassed(List<PackReloadConfig.IdAndPath> packs)
	{
		return packs.stream()
			.allMatch(pack -> bypassedPacks.contains(pack.id()));
	}
	
	/**
	 * Reports the packs as applied without loading them, after about as long
	 * as a real resource reload would take.
	 */
	public void fakeReload(PackReloadConfig.Callbacks callbacks)
	{
		long delay = callbacks.packsToLoad().isEmpty() ? 0
			: ThreadLocalRandom.current().nextLong(1500, 3000);
		CompletableFuture.runAsync(callbacks::onSuccess, CompletableFuture
			.delayedExecutor(delay, TimeUnit.MILLISECONDS, MC));
	}
	
	public void forgetBypassedPacks()
	{
		bypassedPacks.clear();
	}
	
	public enum PackMode
	{
		ENABLED(ServerPackStatus.ENABLED),
		DISABLED(ServerPackStatus.DISABLED),
		BYPASS(ServerPackStatus.DISABLED),
		PROMPT(ServerPackStatus.PROMPT);
		
		private final ServerPackStatus vanillaStatus;
		
		private PackMode(ServerPackStatus vanillaStatus)
		{
			this.vanillaStatus = vanillaStatus;
		}
		
		public Component getName()
		{
			if(this == BYPASS)
				return Component.literal("Bypass");
			
			return vanillaStatus.getName();
		}
		
		private void applyTo(ServerData serverData)
		{
			serverData.setResourcePackStatus(vanillaStatus);
			((IServerData)serverData).setPacksBypass(this == BYPASS);
		}
		
		private static PackMode of(ServerData serverData)
		{
			if(((IServerData)serverData).isPacksBypass())
				return BYPASS;
			
			return switch(serverData.getResourcePackStatus())
			{
				case ENABLED -> ENABLED;
				case DISABLED -> DISABLED;
				case PROMPT -> PROMPT;
			};
		}
	}
}
