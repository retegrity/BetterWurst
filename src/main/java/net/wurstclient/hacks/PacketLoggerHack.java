/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.minecraft.network.protocol.BundlePacket;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.world.item.ItemStack;
import net.wurstclient.Category;
import net.wurstclient.SearchTags;
import net.wurstclient.events.ConnectionPacketOutputListener;
import net.wurstclient.events.PacketInputListener;
import net.wurstclient.hack.Hack;
import net.wurstclient.settings.CheckboxSetting;
import net.wurstclient.util.ChatUtils;

@SearchTags({"packet logger", "PacketDebug", "packet debug", "PacketLog",
	"packet log"})
public final class PacketLoggerHack extends Hack
	implements PacketInputListener, ConnectionPacketOutputListener
{
	private static final Logger LOGGER =
		LoggerFactory.getLogger("Wurst PacketLogger");
	
	private static final int MAX_LENGTH = 2000;
	
	// sent many times per second, so they would bury everything else
	private static final Set<String> FREQUENT_PACKETS = Set.of(
		"ClientboundMoveEntityPacket", "ClientboundSetEntityMotionPacket",
		"ClientboundRotateHeadPacket", "ClientboundEntityPositionSyncPacket",
		"ClientboundTeleportEntityPacket", "ClientboundMoveMinecartPacket",
		"ClientboundSetEntityDataPacket", "ClientboundUpdateAttributesPacket",
		"ClientboundSetEquipmentPacket", "ClientboundAnimatePacket",
		"ClientboundLevelChunkWithLightPacket",
		"ClientboundForgetLevelChunkPacket", "ClientboundLightUpdatePacket",
		"ClientboundChunkBatchStartPacket",
		"ClientboundChunkBatchFinishedPacket",
		"ServerboundChunkBatchReceivedPacket",
		"ClientboundSetChunkCacheCenterPacket", "ClientboundSetTimePacket",
		"ClientboundKeepAlivePacket", "ServerboundKeepAlivePacket",
		"ClientboundPingPacket", "ServerboundPongPacket",
		"ClientboundSoundPacket", "ClientboundSoundEntityPacket",
		"ClientboundLevelParticlesPacket", "ClientboundLevelEventPacket",
		"ClientboundBlockDestructionPacket", "ClientboundBlockEventPacket",
		"ClientboundBundleDelimiterPacket", "ServerboundMovePlayerPacket",
		"ServerboundClientTickEndPacket", "ServerboundPlayerInputPacket");
	
	private final CheckboxSetting incoming = new CheckboxSetting("Incoming",
		"Logs packets that the server sends to you.", true);
	
	private final CheckboxSetting outgoing = new CheckboxSetting("Outgoing",
		"Logs packets that your client sends to the server.", true);
	
	private final CheckboxSetting frequent =
		new CheckboxSetting("Frequent packets",
			"Also logs packets that are sent many times per second, like"
				+ " movement, entity updates, chunks and sounds. Makes the log"
				+ " much longer.",
			false);
	
	private final Map<Class<?>, List<Field>> fieldCache =
		new ConcurrentHashMap<>();
	
	public PacketLoggerHack()
	{
		super("PacketLogger");
		setCategory(Category.OTHER);
		addSetting(incoming);
		addSetting(outgoing);
		addSetting(frequent);
	}
	
	@Override
	protected void onEnable()
	{
		EVENTS.add(PacketInputListener.class, this);
		EVENTS.add(ConnectionPacketOutputListener.class, this);
		ChatUtils.message("Logging packets to the game log (latest.log).");
	}
	
	@Override
	protected void onDisable()
	{
		EVENTS.remove(PacketInputListener.class, this);
		EVENTS.remove(ConnectionPacketOutputListener.class, this);
	}
	
	/*
	 * Incoming packets arrive on the network thread, so this only reads them.
	 */
	@Override
	public void onReceivedPacket(PacketInputEvent event)
	{
		// in singleplayer, the integrated server's connection fires this too
		Packet<?> packet = event.getPacket();
		if(incoming.isChecked()
			&& packet.type().flow() == PacketFlow.CLIENTBOUND)
			log("IN ", packet);
	}
	
	@Override
	public void onSentConnectionPacket(ConnectionPacketOutputEvent event)
	{
		Packet<?> packet = event.getPacket();
		if(outgoing.isChecked()
			&& packet.type().flow() == PacketFlow.SERVERBOUND)
			log("OUT", packet);
	}
	
	private void log(String direction, Packet<?> packet)
	{
		if(packet instanceof BundlePacket<?> bundle)
		{
			for(Packet<?> subPacket : bundle.subPackets())
				log(direction, subPacket);
			return;
		}
		
		if(!frequent.isChecked() && FREQUENT_PACKETS.contains(getName(packet)))
			return;
		
		String text = format(packet);
		if(text.length() > MAX_LENGTH)
			text = text.substring(0, MAX_LENGTH) + "... ("
				+ (text.length() - MAX_LENGTH) + " more characters)";
		
		LOGGER.info("[{}] {}", direction, text);
	}
	
	/**
	 * Returns the name of the packet's top-level class, e.g.
	 * <code>ServerboundMovePlayerPacket</code> for
	 * <code>ServerboundMovePlayerPacket.Pos</code>.
	 */
	private static String getName(Packet<?> packet)
	{
		String name = packet.getClass().getName();
		name = name.substring(name.lastIndexOf('.') + 1);
		int innerStart = name.indexOf('$');
		return innerStart == -1 ? name : name.substring(0, innerStart);
	}
	
	/**
	 * Lists all fields of the packet, since most packets aren't records and
	 * don't have a useful <code>toString()</code>.
	 */
	private String format(Packet<?> packet)
	{
		String name = packet.getClass().getName();
		StringBuilder text = new StringBuilder(
			name.substring(name.lastIndexOf('.') + 1).replace('$', '.'));
		text.append(" {");
		
		boolean first = true;
		for(Field field : getFields(packet.getClass()))
		{
			if(!first)
				text.append(", ");
			first = false;
			
			text.append(field.getName()).append('=');
			try
			{
				text.append(formatValue(field.get(packet)));
				
			}catch(ReflectiveOperationException | RuntimeException e)
			{
				text.append('?');
			}
		}
		
		return text.append('}').toString();
	}
	
	private List<Field> getFields(Class<?> type)
	{
		return fieldCache.computeIfAbsent(type, t -> {
			ArrayList<Field> fields = new ArrayList<>();
			for(Class<?> c = t; c != null && c != Object.class
				&& c != Record.class; c = c.getSuperclass())
				for(Field field : c.getDeclaredFields())
				{
					if(Modifier.isStatic(field.getModifiers()))
						continue;
					
					try
					{
						field.setAccessible(true);
						fields.add(field);
						
					}catch(RuntimeException e)
					{
						// skip fields that can't be read
					}
				}
			
			return fields;
		});
	}
	
	/**
	 * Formats items with their components, so that custom names and lore
	 * show up, which <code>ItemStack.toString()</code> leaves out.
	 */
	private String formatValue(Object value)
	{
		if(value instanceof ItemStack stack)
			return stack.isEmpty() ? "empty"
				: stack + " " + stack.getComponentsPatch();
		
		if(value instanceof Collection<?> collection)
			return collection.stream().map(this::formatValue)
				.collect(Collectors.joining(", ", "[", "]"));
		
		if(value instanceof Optional<?> optional)
			return optional.map(this::formatValue).orElse("empty");
		
		if(value instanceof byte[] bytes)
			return "byte[" + bytes.length + "]";
		
		if(value != null && value.getClass().isArray())
		{
			ArrayList<String> elements = new ArrayList<>();
			for(int i = 0; i < Array.getLength(value); i++)
				elements.add(formatValue(Array.get(value, i)));
			return elements.toString();
		}
		
		return String.valueOf(value);
	}
}
