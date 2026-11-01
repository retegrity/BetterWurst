/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.other_features;

import java.util.UUID;

import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.ServerData.ServerPackStatus;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.common.ServerboundResourcePackPacket;
import net.minecraft.network.protocol.common.ServerboundResourcePackPacket.Action;
import net.wurstclient.DontBlock;
import net.wurstclient.SearchTags;
import net.wurstclient.mixinterface.IServerData;
import net.wurstclient.other_feature.OtherFeature;

@SearchTags({"Packs Bypass", "ResourcePackBypass", "resource pack bypass",
	"server resource packs", "TexturePackBypass", "ResourcePackSpoof"})
@DontBlock
public final class PacksBypassOtf extends OtherFeature
{
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
	 * Tells the server that the pack was accepted, downloaded and loaded,
	 * which is what vanilla sends when it applies a pack.
	 */
	public void fakeLoadPack(Connection connection, UUID packId)
	{
		connection
			.send(new ServerboundResourcePackPacket(packId, Action.ACCEPTED));
		connection
			.send(new ServerboundResourcePackPacket(packId, Action.DOWNLOADED));
		connection.send(new ServerboundResourcePackPacket(packId,
			Action.SUCCESSFULLY_LOADED));
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
