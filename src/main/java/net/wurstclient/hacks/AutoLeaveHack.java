/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.network.protocol.common.ServerboundKeepAlivePacket;
import net.minecraft.network.protocol.cookie.ServerboundCookieResponsePacket;
import net.minecraft.network.protocol.game.ServerboundAttackPacket;
import net.minecraft.network.protocol.game.ServerboundChatAckPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Items;
import net.wurstclient.Category;
import net.wurstclient.SearchTags;
import net.wurstclient.events.UpdateListener;
import net.wurstclient.hack.Hack;
import net.wurstclient.settings.CheckboxSetting;
import net.wurstclient.settings.EnumSetting;
import net.wurstclient.settings.SliderSetting;
import net.wurstclient.settings.SliderSetting.ValueDisplay;
import net.wurstclient.util.InventoryUtils;

@SearchTags({"auto leave", "AutoDisconnect", "auto disconnect", "AutoQuit",
	"auto quit"})
public final class AutoLeaveHack extends Hack implements UpdateListener
{
	private final SliderSetting health = new SliderSetting("Health",
		"Leaves the server when your health reaches this value or falls below it.",
		4, 0.5, 9.5, 0.5, ValueDisplay.DECIMAL.withSuffix(" hearts"));
	
	public final EnumSetting<Mode> mode = new EnumSetting<>("Mode",
		"\u00a7lQuit\u00a7r mode just quits the game normally."
			+ " Doesn't bypass CombatLog.\n"
			+ "\u00a7lChars\u00a7r mode sends a special chat message that"
			+ " causes the server to kick you. Bypasses some versions of"
			+ " CombatLog.\n"
			+ "\u00a7lSelfHurt\u00a7r mode sends the packet for attacking"
			+ " another player, but with yourself as both the attacker and the"
			+ " target, causing the server to kick you. Bypasses CombatLog.\n"
			+ "\u00a7lKeepAlive\u00a7r mode answers the server's connection"
			+ " check wrong, so it kicks you for timing out. Doesn't work on"
			+ " Velocity servers.\n"
			+ "\u00a7lInvalidPos\u00a7r mode sends an impossible position,"
			+ " which the server kicks you for.\n"
			+ "\u00a7lCookie\u00a7r mode sends an answer to a question the"
			+ " server never asked, which it kicks you for.\n"
			+ "\u00a7lChatAck\u00a7r mode confirms chat messages that were"
			+ " never sent, which fails the server's chat validation.",
		Mode.values(), Mode.QUIT);
	
	private final CheckboxSetting chatAckMessage = new CheckboxSetting(
		"Also send chat packet (ChatAck)",
		"In \u00a7lChatAck\u00a7r mode, also sends a chat message (a dot)"
			+ " right after it. Velocity only passes the bad confirmation on to"
			+ " the server together with your next chat message, so without"
			+ " this, you are only kicked once you chat.\n\n"
			+ "On servers that don't check chat messages, the dot is posted in"
			+ " chat.",
		false);
	
	private final CheckboxSetting disableAutoReconnect = new CheckboxSetting(
		"Disable AutoReconnect", "Automatically turns off AutoReconnect when"
			+ " AutoLeave makes you leave the server.",
		true);
	
	private final SliderSetting totems = new SliderSetting("Totems",
		"Won't leave the server until the number of totems you have reaches"
			+ " this value or falls below it.\n\n"
			+ "11 = always able to leave",
		11, 0, 11, 1, ValueDisplay.INTEGER.withSuffix(" totems")
			.withLabel(1, "1 totem").withLabel(11, "ignore"));
	
	public AutoLeaveHack()
	{
		super("AutoLeave");
		setCategory(Category.COMBAT);
		addSetting(health);
		addSetting(mode);
		addSetting(chatAckMessage);
		addSetting(disableAutoReconnect);
		addSetting(totems);
	}
	
	@Override
	public String getRenderName()
	{
		if(MC.player != null && MC.player.getAbilities().instabuild)
			return getName() + " (paused)";
		
		return getName() + " [" + mode.getSelected() + "]";
	}
	
	@Override
	protected void onEnable()
	{
		EVENTS.add(UpdateListener.class, this);
	}
	
	@Override
	protected void onDisable()
	{
		EVENTS.remove(UpdateListener.class, this);
	}
	
	@Override
	public void onUpdate()
	{
		// check gamemode
		if(MC.player.getAbilities().instabuild)
			return;
		
		// check health
		float currentHealth = MC.player.getHealth();
		if(currentHealth <= 0F || currentHealth > health.getValueF() * 2F)
			return;
		
		// check totems
		if(totems.getValueI() < 11 && InventoryUtils
			.count(Items.TOTEM_OF_UNDYING, 40, true) > totems.getValueI())
			return;
		
		// leave server
		mode.getSelected().leave.run();
		if(mode.getSelected() == Mode.CHATACK && chatAckMessage.isChecked())
			MC.getConnection().sendChat(".");
		
		// disable
		setEnabled(false);
		
		if(disableAutoReconnect.isChecked())
			WURST.getHax().autoReconnectHack.setEnabled(false);
	}
	
	public static enum Mode
	{
		QUIT("Quit",
			() -> MC.level.disconnect(ClientLevel.DEFAULT_QUIT_MESSAGE)),
		
		CHARS("Chars", () -> MC.getConnection().sendChat("\u00a7")),
		
		SELFHURT("SelfHurt",
			() -> MC.getConnection()
				.send(new ServerboundAttackPacket(MC.player.getId()))),
		
		// the server expects the ID it sent, which is a timestamp
		KEEPALIVE("KeepAlive",
			() -> MC.getConnection()
				.send(new ServerboundKeepAlivePacket(Long.MIN_VALUE))),
		
		INVALIDPOS("InvalidPos",
			() -> MC.getConnection()
				.send(new ServerboundMovePlayerPacket.Pos(Double.NaN,
					Double.NaN, Double.NaN, false, false))),
		
		COOKIE("Cookie",
			() -> MC.getConnection()
				.send(new ServerboundCookieResponsePacket(
					Identifier.fromNamespaceAndPath("wurst", "leave"),
					new byte[0]))),
		
		// a negative number of newly seen messages is never valid
		CHATACK("ChatAck",
			() -> MC.getConnection().send(new ServerboundChatAckPacket(-1)));
		
		private final String name;
		private final Runnable leave;
		
		private Mode(String name, Runnable leave)
		{
			this.name = name;
			this.leave = leave;
		}
		
		@Override
		public String toString()
		{
			return name;
		}
	}
}
