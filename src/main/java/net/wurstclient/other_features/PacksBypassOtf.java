/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.other_features;

import java.io.InputStream;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.minecraft.SharedConstants;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.ServerData.ServerPackStatus;
import net.minecraft.client.resources.server.DownloadedPackSource;
import net.minecraft.client.resources.server.PackReloadConfig;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.FilePackResources;
import net.minecraft.server.packs.PackLocationInfo;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.metadata.pack.PackFormat;
import net.minecraft.server.packs.repository.Pack;
import net.minecraft.server.packs.repository.PackSource;
import net.minecraft.server.packs.resources.IoSupplier;
import net.minecraft.util.FormattedCharSequence;
import net.wurstclient.DontBlock;
import net.wurstclient.SearchTags;
import net.wurstclient.mixinterface.IServerData;
import net.wurstclient.other_feature.OtherFeature;
import net.wurstclient.settings.CheckboxSetting;

@SearchTags({"Packs Bypass", "ResourcePackBypass", "resource pack bypass",
	"server resource packs", "TexturePackBypass", "ResourcePackSpoof"})
@DontBlock
public final class PacksBypassOtf extends OtherFeature
{
	private static final Logger LOGGER =
		LoggerFactory.getLogger("Wurst PacksBypass");
	
	private final CheckboxSetting loadLanguageFiles = new CheckboxSetting(
		"Load server language files",
		"Loads only the language files of bypassed server resource packs,"
			+ " without reloading resources. Some servers check whether their"
			+ " pack is loaded by making the client translate text that only"
			+ " exists in the pack (e.g. on a sign) and send it back.\n\n"
			+ "Takes effect the next time a server sends its pack.",
		false);
	
	private final HashSet<UUID> bypassedPacks = new HashSet<>();
	private List<PackReloadConfig.IdAndPath> activePacks = List.of();
	private Map<String, String> serverTranslations = Map.of();
	
	public PacksBypassOtf()
	{
		super("PacksBypass", "description.wurst.other_feature.packsbypass");
		addSetting(loadLanguageFiles);
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
		activePacks = List.copyOf(callbacks.packsToLoad());
		updateServerTranslations();
		
		long delay = callbacks.packsToLoad().isEmpty() ? 0
			: ThreadLocalRandom.current().nextLong(1500, 3000);
		CompletableFuture.runAsync(callbacks::onSuccess, CompletableFuture
			.delayedExecutor(delay, TimeUnit.MILLISECONDS, MC));
	}
	
	public void forgetBypassedPacks()
	{
		bypassedPacks.clear();
		activePacks = List.of();
		updateServerTranslations();
	}
	
	/**
	 * Called after Minecraft has reloaded its language, which replaces the
	 * translations added by {@link #updateServerTranslations()}.
	 */
	public void onLanguageReload()
	{
		if(!activePacks.isEmpty())
			updateServerTranslations();
	}
	
	/**
	 * The translations loaded by "Load server language files", or an empty
	 * map if there are none.
	 */
	public Map<String, String> getServerTranslations()
	{
		return serverTranslations;
	}
	
	private void updateServerTranslations()
	{
		serverTranslations = loadLanguageFiles.isChecked()
			? loadTranslations(activePacks) : Map.of();
		
		Language language = Language.getInstance();
		if(language instanceof ServerLanguage serverLanguage)
			language = serverLanguage.base;
		
		if(!serverTranslations.isEmpty())
			language = new ServerLanguage(language, serverTranslations);
		
		Language.inject(language);
	}
	
	/**
	 * Loads the packs' language files in the same order as vanilla: English
	 * from all packs first, then the selected language from all packs.
	 */
	private Map<String, String> loadTranslations(
		List<PackReloadConfig.IdAndPath> packs)
	{
		ArrayList<String> languages = new ArrayList<>(List.of("en_us"));
		String selected = MC.getLanguageManager().getSelected();
		if(!selected.equals("en_us"))
			languages.add(selected);
		
		ArrayList<HashMap<String, String>> perLanguage = new ArrayList<>();
		for(int i = 0; i < languages.size(); i++)
			perLanguage.add(new HashMap<>());
		
		for(PackReloadConfig.IdAndPath pack : packs)
			loadTranslations(pack, languages, perLanguage);
		
		HashMap<String, String> translations = new HashMap<>();
		perLanguage.forEach(translations::putAll);
		return translations;
	}
	
	private void loadTranslations(PackReloadConfig.IdAndPath pack,
		List<String> languages, List<HashMap<String, String>> perLanguage)
	{
		PackLocationInfo info = new PackLocationInfo("server/" + pack.id(),
			Component.literal(pack.id().toString()), PackSource.SERVER,
			Optional.empty());
		Pack.ResourcesSupplier supplier =
			new FilePackResources.FileResourcesSupplier(pack.path());
		PackFormat packVersion = SharedConstants.getCurrentVersion()
			.packVersion(PackType.CLIENT_RESOURCES);
		Pack.Metadata metadata = Pack.readPackMetadata(info, supplier,
			packVersion, PackType.CLIENT_RESOURCES);
		if(metadata == null)
			return;
		
		try(PackResources resources = supplier.openFull(info, metadata))
		{
			for(String namespace : resources
				.getNamespaces(PackType.CLIENT_RESOURCES))
				for(int i = 0; i < languages.size(); i++)
				{
					String path = "lang/" + languages.get(i) + ".json";
					HashMap<String, String> translations = perLanguage.get(i);
					
					try
					{
						IoSupplier<InputStream> resource = resources
							.getResource(PackType.CLIENT_RESOURCES, Identifier
								.fromNamespaceAndPath(namespace, path));
						if(resource == null)
							continue;
						
						try(InputStream stream = resource.get())
						{
							Language.loadFromJson(stream, translations::put);
						}
						
					}catch(Exception e)
					{
						LOGGER.warn("Skipped language file {}:{} in pack {}",
							namespace, path, pack.id(), e);
					}
				}
		}
	}
	
	/**
	 * The current language with the server packs' translations on top.
	 */
	private static final class ServerLanguage extends Language
	{
		private final Language base;
		private final Map<String, String> translations;
		
		private ServerLanguage(Language base, Map<String, String> translations)
		{
			this.base = base;
			this.translations = translations;
		}
		
		@Override
		public String getOrDefault(String key, String defaultValue)
		{
			String translation = translations.get(key);
			if(translation != null)
				return translation;
			
			return base.getOrDefault(key, defaultValue);
		}
		
		@Override
		public boolean has(String key)
		{
			return translations.containsKey(key) || base.has(key);
		}
		
		@Override
		public boolean isDefaultRightToLeft()
		{
			return base.isDefaultRightToLeft();
		}
		
		@Override
		public FormattedCharSequence getVisualOrder(FormattedText text)
		{
			return base.getVisualOrder(text);
		}
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
