/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.other_features;

import java.io.IOException;
import java.io.InputStream;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.minecraft.locale.DeprecatedTranslationsInfo;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.IoSupplier;
import net.minecraft.util.FormattedCharSequence;
import net.wurstclient.DontBlock;
import net.wurstclient.SearchTags;
import net.wurstclient.other_feature.OtherFeature;
import net.wurstclient.settings.CheckboxSetting;

@SearchTags({"no mod detection", "AntiModDetection", "anti mod detection",
	"ExploitPreventer", "exploit preventer", "translation exploit",
	"anvil exploit", "sign exploit", "key resolving exploit"})
@DontBlock
public final class NoModDetectionOtf extends OtherFeature
{
	private static final Logger LOGGER =
		LoggerFactory.getLogger("Wurst NoModDetection");
	
	private final CheckboxSetting hideTranslations = new CheckboxSetting(
		"Hide mod translations",
		"When text from the server is copied into an anvil or a sign editor"
			+ " (and from there sent back to the server), translates it like a"
			+ " vanilla client would: only with Minecraft's own language files,"
			+ " your resource packs and server resource packs (including the"
			+ " ones from PacksBypass). Mod translations and mod keybinds are"
			+ " left as their raw keys.\n\n"
			+ "Servers use this to find out which mods you have, e.g. by"
			+ " opening an invisible anvil with items named after mod"
			+ " translation keys.",
		true);
	
	private final CheckboxSetting blockLocalUrls = new CheckboxSetting(
		"Block local pack URLs",
		"Stops servers from making your client download resource packs from"
			+ " your own computer or local network (including through"
			+ " redirects), which they can use to probe your local services."
			+ " Not active when the server itself is on your computer or"
			+ " local network.",
		true);
	
	private static final int MAX_REDIRECTS = 20;
	
	private Map<String, String> vanillaTranslations;
	private boolean resolvingForServer;
	private volatile boolean serverIsLocal = true;
	
	public NoModDetectionOtf()
	{
		super("NoModDetection",
			"description.wurst.other_feature.nomoddetection");
		addSetting(hideTranslations);
		addSetting(blockLocalUrls);
	}
	
	/**
	 * Returns the text of a component from the server the way a vanilla client
	 * would, for when it will be sent back to the server.
	 */
	public String getStringForServer(Component component)
	{
		if(!hideTranslations.isChecked())
			return component.getString();
		
		Language language = Language.getInstance();
		Language.inject(new VanillaLanguage(language));
		resolvingForServer = true;
		
		try
		{
			return component.getString();
			
		}finally
		{
			resolvingForServer = false;
			Language.inject(language);
		}
	}
	
	/**
	 * Whether a keybind in the text that's currently being resolved by
	 * {@link #getStringForServer(Component)} should be left as its raw name,
	 * because it isn't one of Minecraft's own keybinds.
	 */
	public boolean shouldHideKeybind(String name)
	{
		return resolvingForServer && MC.isSameThread()
			&& !getVanillaTranslations().containsKey(name);
	}
	
	public void onLanguageReload()
	{
		vanillaTranslations = null;
	}
	
	/**
	 * Loads the translations that a vanilla client would have: those from
	 * Minecraft itself, the user's resource packs and server resource packs.
	 */
	private Map<String, String> getVanillaTranslations()
	{
		if(vanillaTranslations != null)
			return vanillaTranslations;
		
		ArrayList<String> languages = new ArrayList<>(List.of("en_us"));
		String selected = MC.getLanguageManager().getSelected();
		if(!selected.equals("en_us"))
			languages.add(selected);
		
		List<PackResources> packs = MC.getResourceManager().listPacks()
			.filter(this::isVanillaOrResourcePack).toList();
		
		HashMap<String, String> translations = new HashMap<>();
		for(String language : languages)
		{
			String path = "lang/" + language + ".json";
			for(PackResources pack : packs)
				for(String namespace : pack
					.getNamespaces(PackType.CLIENT_RESOURCES))
					try
					{
						IoSupplier<InputStream> resource = pack.getResource(
							PackType.CLIENT_RESOURCES,
							Identifier.fromNamespaceAndPath(namespace, path));
						if(resource == null)
							continue;
						
						try(InputStream stream = resource.get())
						{
							Language.loadFromJson(stream, translations::put);
						}
						
					}catch(Exception e)
					{
						LOGGER.warn("Skipped language file {}:{} in pack {}",
							namespace, path, pack.packId(), e);
					}
		}
		
		DeprecatedTranslationsInfo.loadFromDefaultResource()
			.applyToMap(translations);
		return vanillaTranslations = translations;
	}
	
	private boolean isVanillaOrResourcePack(PackResources pack)
	{
		String id = pack.packId();
		return id.equals("vanilla") || id.equals("programmer_art")
			|| id.equals("high_contrast") || id.startsWith("file/")
			|| id.startsWith("server/");
	}
	
	/**
	 * Called when the server sends a resource pack, before it's downloaded.
	 */
	public void onServerPackPush(SocketAddress serverAddress)
	{
		serverIsLocal = !(serverAddress instanceof InetSocketAddress address)
			|| isLocalAddress(address.getAddress());
	}
	
	public void onDisconnect()
	{
		serverIsLocal = true;
	}
	
	public boolean isBlockingLocalUrls()
	{
		return blockLocalUrls.isChecked() && !serverIsLocal;
	}
	
	public int getMaxRedirects()
	{
		return MAX_REDIRECTS;
	}
	
	/**
	 * Throws if a resource pack download from this host should be blocked.
	 */
	public void checkPackHost(String host) throws IOException
	{
		if(!isBlockingLocalUrls())
			return;
		
		InetAddress[] addresses;
		try
		{
			addresses = InetAddress.getAllByName(host);
			
		}catch(UnknownHostException e)
		{
			// the download fails anyway
			return;
		}
		
		for(InetAddress address : addresses)
			if(isLocalAddress(address))
				throw new IOException(
					"NoModDetection blocked a resource pack download from a"
						+ " local address: " + host);
	}
	
	private static boolean isLocalAddress(InetAddress address)
	{
		if(address == null)
			return true;
		
		if(address.isAnyLocalAddress() || address.isLoopbackAddress()
			|| address.isSiteLocalAddress() || address.isLinkLocalAddress())
			return true;
		
		// IPv6 unique local addresses (fc00::/7)
		return address instanceof Inet6Address
			&& (address.getAddress()[0] & 0xfe) == 0xfc;
	}
	
	/**
	 * The current language, but with only the translations that a vanilla
	 * client would have.
	 */
	private final class VanillaLanguage extends Language
	{
		private final Language base;
		private final Map<String, String> serverTranslations =
			WURST.getOtfs().packsBypassOtf.getServerTranslations();
		
		private VanillaLanguage(Language base)
		{
			this.base = base;
		}
		
		@Override
		public String getOrDefault(String key, String defaultValue)
		{
			String translation = serverTranslations.get(key);
			if(translation != null)
				return translation;
			
			return getVanillaTranslations().getOrDefault(key, defaultValue);
		}
		
		@Override
		public boolean has(String key)
		{
			return serverTranslations.containsKey(key)
				|| getVanillaTranslations().containsKey(key);
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
}
