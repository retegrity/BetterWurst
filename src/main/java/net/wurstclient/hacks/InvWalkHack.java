/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ServerboundContainerClickPacket;
import net.minecraft.network.protocol.game.ServerboundContainerClosePacket;
import net.minecraft.world.item.CreativeModeTabs;
import net.wurstclient.Category;
import net.wurstclient.SearchTags;
import net.wurstclient.clickgui.screens.ClickGuiScreen;
import net.wurstclient.events.PacketOutputListener;
import net.wurstclient.events.UpdateListener;
import net.wurstclient.hack.Hack;
import net.wurstclient.mixinterface.IKeyMapping;
import net.wurstclient.settings.CheckboxSetting;
import net.wurstclient.util.InventoryUtils;

@SearchTags({"inv walk", "inventory walk", "InvMove", "inv move",
	"inventory move", "MenuWalk", "menu walk"})
public final class InvWalkHack extends Hack
	implements UpdateListener, PacketOutputListener
{
	private final CheckboxSetting allowClickGUI =
		new CheckboxSetting("Allow ClickGUI",
			"description.wurst.setting.invwalk.allow_clickgui", true);
	
	private final CheckboxSetting allowOther =
		new CheckboxSetting("Allow other screens",
			"description.wurst.setting.invwalk.allow_other", true);
	
	private final CheckboxSetting allowSneak =
		new CheckboxSetting("Allow sneak key", true);
	
	private final CheckboxSetting allowSprint =
		new CheckboxSetting("Allow sprint key", true);
	
	private final CheckboxSetting allowJump =
		new CheckboxSetting("Allow jump key", true);
	
	private final CheckboxSetting grimMode = new CheckboxSetting("Grim mode",
		"description.wurst.setting.invwalk.grim_mode", false);
	
	// Grim mode: inventory clicks (and closing it) that came while you were
	// moving, held back until the server knows that you stopped
	private final ArrayDeque<Packet<?>> heldPackets = new ArrayDeque<>();
	private ClientPacketListener heldConnection;
	private boolean sendingHeld;
	private boolean restoreKeys;
	
	public InvWalkHack()
	{
		super("InvWalk");
		setCategory(Category.MOVEMENT);
		addSetting(allowClickGUI);
		addSetting(allowOther);
		addSetting(allowSneak);
		addSetting(allowSprint);
		addSetting(allowJump);
		addSetting(grimMode);
	}
	
	@Override
	protected void onEnable()
	{
		EVENTS.add(UpdateListener.class, this);
		EVENTS.add(PacketOutputListener.class, this);
	}
	
	@Override
	protected void onDisable()
	{
		EVENTS.remove(UpdateListener.class, this);
		EVENTS.remove(PacketOutputListener.class, this);
		sendHeldPackets();
		if(restoreKeys)
			restoreMovementKeys();
	}
	
	@Override
	public void onUpdate()
	{
		updateKeys();
		
		// Grim mode: the last tick's input packet told the server that you
		// stopped, so send the held clicks now, before this tick's input and
		// movement packets. Sending them after last tick's movement packet
		// instead would flag Grim's PacketOrderO (packets between the
		// movement packet and the end of the tick).
		if(restoreKeys)
		{
			sendHeldPackets();
			restoreMovementKeys();
			restoreKeys = false;
		}
		
		// stop moving for this tick, so that this tick's input packet tells
		// the server that you aren't pressing any keys
		if(!heldPackets.isEmpty())
		{
			for(KeyMapping key : getMovementKeys())
				key.setDown(false);
			MC.player.setSprinting(false);
			restoreKeys = true;
		}
	}
	
	/**
	 * Grim's MultiActionsC check cancels inventory clicks while the server
	 * thinks that you're moving (pressing movement or jump keys, sprinting
	 * or sneaking), and MultiActionsD flags closing an inventory then, since
	 * you can't move with an inventory open in vanilla. So in Grim mode,
	 * clicks and closing while you're moving are held back: you stop moving
	 * for a tick, and they go out at the start of the next one.
	 */
	@Override
	public void onSentPacket(PacketOutputEvent event)
	{
		if(!grimMode.isChecked() || sendingHeld)
			return;
		
		Packet<?> packet = event.getPacket();
		if(!(packet instanceof ServerboundContainerClickPacket)
			&& !(packet instanceof ServerboundContainerClosePacket))
			return;
		
		// keep them in order
		if(heldPackets.isEmpty() && InventoryUtils.isStandingStill())
			return;
		
		event.cancel();
		if(heldPackets.isEmpty())
			heldConnection = MC.getConnection();
		heldPackets.add(packet);
	}
	
	private void sendHeldPackets()
	{
		if(heldPackets.isEmpty())
			return;
		
		sendingHeld = true;
		try
		{
			// never send them to a different server
			if(MC.getConnection() != null
				&& MC.getConnection() == heldConnection)
				while(!heldPackets.isEmpty())
					MC.getConnection().send(heldPackets.poll());
				
		}finally
		{
			heldPackets.clear();
			sendingHeld = false;
		}
	}
	
	private void restoreMovementKeys()
	{
		for(KeyMapping key : getMovementKeys())
			IKeyMapping.get(key).resetDownState();
	}
	
	private KeyMapping[] getMovementKeys()
	{
		return new KeyMapping[]{MC.options.keyUp, MC.options.keyDown,
			MC.options.keyLeft, MC.options.keyRight, MC.options.keyJump,
			MC.options.keySprint, MC.options.keyShift};
	}
	
	private void updateKeys()
	{
		Screen screen = MC.screen;
		if(screen == null)
			return;
		
		if(!isAllowedScreen(screen))
			return;
		
		ArrayList<KeyMapping> keys =
			new ArrayList<>(Arrays.asList(MC.options.keyUp, MC.options.keyDown,
				MC.options.keyLeft, MC.options.keyRight));
		
		if(allowSneak.isChecked())
			keys.add(MC.options.keyShift);
		
		if(allowSprint.isChecked())
			keys.add(MC.options.keySprint);
		
		if(allowJump.isChecked())
			keys.add(MC.options.keyJump);
		
		for(KeyMapping key : keys)
			IKeyMapping.get(key).resetDownState();
	}
	
	/**
	 * Returns true if hacks may press the given key right now, i.e. no screen
	 * is open, or InvWalk lets you use that key in the open screen.
	 */
	public boolean canPressKey(KeyMapping key)
	{
		Screen screen = MC.screen;
		if(screen == null)
			return true;
		
		if(!isEnabled() || !isAllowedScreen(screen))
			return false;
		
		if(key == MC.options.keyShift)
			return allowSneak.isChecked();
		
		if(key == MC.options.keySprint)
			return allowSprint.isChecked();
		
		if(key == MC.options.keyJump)
			return allowJump.isChecked();
		
		return key == MC.options.keyUp || key == MC.options.keyDown
			|| key == MC.options.keyLeft || key == MC.options.keyRight;
	}
	
	private boolean isAllowedScreen(Screen screen)
	{
		if((screen instanceof InventoryScreen
			|| screen instanceof CreativeModeInventoryScreen)
			&& !isCreativeSearchBarOpen(screen))
			return true;
		
		if(allowClickGUI.isChecked() && screen instanceof ClickGuiScreen)
			return true;
		
		if(allowOther.isChecked() && screen instanceof AbstractContainerScreen
			&& !hasTextBox(screen))
			return true;
		
		return false;
	}
	
	private boolean isCreativeSearchBarOpen(Screen screen)
	{
		if(!(screen instanceof CreativeModeInventoryScreen))
			return false;
		
		return CreativeModeInventoryScreen.selectedTab == CreativeModeTabs
			.searchTab();
	}
	
	private boolean hasTextBox(Screen screen)
	{
		return screen.children().stream().anyMatch(EditBox.class::isInstance);
	}
}
