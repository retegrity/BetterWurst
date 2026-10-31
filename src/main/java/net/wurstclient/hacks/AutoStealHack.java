/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.Enchantments;
import net.wurstclient.Category;
import net.wurstclient.SearchTags;
import net.wurstclient.hack.Hack;
import net.wurstclient.settings.CheckboxSetting;
import net.wurstclient.settings.ItemListSetting;
import net.wurstclient.settings.SliderSetting;
import net.wurstclient.settings.SliderSetting.ValueDisplay;
import net.wurstclient.util.ItemUtils;

@SearchTags({"auto steal", "ChestStealer", "chest stealer",
	"steal store buttons", "Steal/Store buttons"})
public final class AutoStealHack extends Hack
{
	private final SliderSetting delay =
		new SliderSetting("Delay", "Delay between moving stacks of items.", 100,
			0, 500, 10, ValueDisplay.INTEGER.withSuffix("ms"));
	
	private final CheckboxSetting buttons =
		new CheckboxSetting("Steal/Store buttons", true);
	
	private final CheckboxSetting reverseSteal =
		new CheckboxSetting("Reverse steal order", false);
	
	private final ItemListSetting items = new ItemListSetting("Items",
		"Only these items will be stolen. Everything else in a container is"
			+ " ignored.\n\nBy default, this contains all armor and all"
			+ " tools/weapons.",
		getDefaultItems());
	
	private final CheckboxSetting onlyBestGear = new CheckboxSetting(
		"Only steal best gear",
		"Compares the armor and tools (swords, pickaxes, axes and shovels) in"
			+ " the container to the ones you already have. Only pieces that"
			+ " are better than yours get stolen, and only one piece per type."
			+ "\n\nUseful for mini-games like SkyWars. All other items are"
			+ " still stolen as usual.",
		false);
	
	private Thread thread;
	
	private static final int WAIT_STEP_MS = 25;
	private static final int MAX_WAIT_TICKS = 120;
	
	public AutoStealHack()
	{
		super("AutoSteal");
		setCategory(Category.ITEMS);
		addSetting(buttons);
		addSetting(delay);
		addSetting(reverseSteal);
		addSetting(items);
		addSetting(onlyBestGear);
	}
	
	public void steal(AbstractContainerScreen<?> screen, int rows)
	{
		startClickingSlots(screen, 0, rows * 9, true);
	}
	
	public void store(AbstractContainerScreen<?> screen, int rows)
	{
		startClickingSlots(screen, rows * 9, rows * 9 + 36, false);
	}
	
	private void startClickingSlots(AbstractContainerScreen<?> screen, int from,
		int to, boolean steal)
	{
		if(thread != null && thread.isAlive())
			thread.interrupt();
		
		thread = Thread.ofPlatform().name("AutoSteal")
			.uncaughtExceptionHandler((t, e) -> e.printStackTrace()).daemon()
			.start(() -> shiftClickSlots(screen, from, to, steal));
	}
	
	private void shiftClickSlots(AbstractContainerScreen<?> screen, int from,
		int to, boolean steal)
	{
		List<Slot> allSlots = IntStream.range(from, to)
			.mapToObj(i -> screen.getMenu().slots.get(i)).toList();
		
		if(reverseSteal.isChecked() && steal)
			allSlots = allSlots.reversed();
		
		List<Slot> slots = allSlots;
		
		try
		{
			// When a container opens, its contents arrive from the server a
			// moment after the screen is created. Without waiting for them,
			// every slot still looks empty and nothing gets moved.
			if(!waitForContents(screen, slots))
				return;
			
			if(steal)
			{
				List<String> allowed = items.getItemNames();
				slots = slots.stream()
					.filter(slot -> !slot.getItem().isEmpty()
						&& allowed.contains(BuiltInRegistries.ITEM
							.getKey(slot.getItem().getItem()).toString()))
					.toList();
				
				if(onlyBestGear.isChecked())
					slots = filterBestGear(slots);
			}
			
		}catch(InterruptedException e)
		{
			Thread.currentThread().interrupt();
			return;
		}
		
		for(Slot slot : slots)
			try
			{
				if(slot.getItem().isEmpty())
					continue;
				
				Thread.sleep(delay.getValueI());
				
				if(MC.gui.screen() != screen)
					break;
				
				screen.slotClicked(slot, slot.index, 0,
					ContainerInput.QUICK_MOVE);
				
			}catch(InterruptedException e)
			{
				Thread.currentThread().interrupt();
				break;
			}
	}
	
	/**
	 * Waits (up to a few seconds) until at least one of the given slots has
	 * an item in it. Returns false if the screen was closed in the meantime.
	 */
	private boolean waitForContents(AbstractContainerScreen<?> screen,
		List<Slot> slots) throws InterruptedException
	{
		for(int i = 0; i < MAX_WAIT_TICKS; i++)
		{
			if(MC.gui.screen() != screen)
				return false;
			
			for(Slot slot : slots)
				if(!slot.getItem().isEmpty())
					return true;
				
			Thread.sleep(WAIT_STEP_MS);
		}
		
		// Nothing arrived, so there is nothing to do.
		return false;
	}
	
	/**
	 * Removes all gear that is not an upgrade over what the player already
	 * has, and keeps only the best upgrade for each gear type.
	 */
	private List<Slot> filterBestGear(List<Slot> slots)
	{
		LocalPlayer player = MC.player;
		
		// best gear the player already has
		Map<GearType, Double> owned = new EnumMap<>(GearType.class);
		for(int i = 0; i < 36; i++)
			considerOwned(owned, player.getInventory().getItem(i));
		for(EquipmentSlot type : new EquipmentSlot[]{EquipmentSlot.HEAD,
			EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET})
			considerOwned(owned, player.getItemBySlot(type));
		
		// best upgrade in the container, for each gear type
		Map<GearType, Slot> bestSlots = new EnumMap<>(GearType.class);
		Map<GearType, Double> bestScores = new EnumMap<>(GearType.class);
		for(Slot slot : slots)
		{
			ItemStack stack = slot.getItem();
			GearType type = getGearType(stack);
			if(type == null)
				continue;
			
			double score = getGearScore(type, stack);
			if(score <= owned.getOrDefault(type, Double.NEGATIVE_INFINITY))
				continue;
			
			if(score > bestScores.getOrDefault(type, Double.NEGATIVE_INFINITY))
			{
				bestScores.put(type, score);
				bestSlots.put(type, slot);
			}
		}
		
		List<Slot> result = new ArrayList<>();
		for(Slot slot : slots)
		{
			ItemStack stack = slot.getItem();
			if(stack.isEmpty())
				continue;
			
			GearType type = getGearType(stack);
			if(type == null || bestSlots.get(type) == slot)
				result.add(slot);
		}
		return result;
	}
	
	private void considerOwned(Map<GearType, Double> owned, ItemStack stack)
	{
		GearType type = getGearType(stack);
		if(type != null)
			owned.merge(type, getGearScore(type, stack), Math::max);
	}
	
	private GearType getGearType(ItemStack stack)
	{
		if(stack.isEmpty())
			return null;
		
		Item item = stack.getItem();
		EquipmentSlot slot = ItemUtils.getArmorSlot(item);
		if(slot != null && ItemUtils.getArmorPoints(item) > 0)
			switch(slot)
			{
				case HEAD:
				return GearType.HEAD;
				case CHEST:
				return GearType.CHEST;
				case LEGS:
				return GearType.LEGS;
				case FEET:
				return GearType.FEET;
				default:
				break;
			}
		
		if(stack.is(ItemTags.SWORDS))
			return GearType.SWORD;
		if(stack.is(ItemTags.PICKAXES))
			return GearType.PICKAXE;
		if(stack.is(ItemTags.AXES))
			return GearType.AXE;
		if(stack.is(ItemTags.SHOVELS))
			return GearType.SHOVEL;
		
		return null;
	}
	
	private double getGearScore(GearType type, ItemStack stack)
	{
		Item item = stack.getItem();
		
		if(type.armor)
		{
			double armor = ItemUtils.getArmorPoints(item);
			double toughness = ItemUtils.getToughness(item);
			int protection = getEnchantLevel(Enchantments.PROTECTION, stack);
			return armor * 5 + protection * 3 + toughness;
		}
		
		// Attack damage reflects the material tier for all tools, and keeps
		// gold (fast but weak) from outranking diamond.
		double damage = ItemUtils.calculateModifiedAttribute(item,
			Attributes.ATTACK_DAMAGE, 1, EquipmentSlot.MAINHAND);
		
		int enchant = type == GearType.SWORD
			? getEnchantLevel(Enchantments.SHARPNESS, stack)
			: getEnchantLevel(Enchantments.EFFICIENCY, stack);
		
		return damage * 10 + enchant * 1.5 + stack.getMaxDamage() / 10000.0;
	}
	
	private int getEnchantLevel(ResourceKey<Enchantment> key, ItemStack stack)
	{
		Registry<Enchantment> registry =
			MC.level.registryAccess().lookupOrThrow(Registries.ENCHANTMENT);
		return registry.get(key)
			.map((Holder<Enchantment> entry) -> EnchantmentHelper
				.getItemEnchantmentLevel(entry, stack))
			.orElse(0);
	}
	
	private enum GearType
	{
		HEAD(true),
		CHEST(true),
		LEGS(true),
		FEET(true),
		SWORD(false),
		PICKAXE(false),
		AXE(false),
		SHOVEL(false);
		
		private final boolean armor;
		
		private GearType(boolean armor)
		{
			this.armor = armor;
		}
	}
	
	private static String[] getDefaultItems()
	{
		List<String> names = new ArrayList<>();
		
		for(String material : new String[]{"leather", "chainmail", "copper",
			"golden", "iron", "diamond", "netherite"})
			for(String piece : new String[]{"helmet", "chestplate", "leggings",
				"boots"})
				names.add("minecraft:" + material + "_" + piece);
			
		names.add("minecraft:turtle_helmet");
		
		for(String material : new String[]{"wooden", "stone", "copper",
			"golden", "iron", "diamond", "netherite"})
			for(String tool : new String[]{"sword", "spear", "pickaxe", "axe",
				"shovel", "hoe"})
				names.add("minecraft:" + material + "_" + tool);
			
		return names.toArray(new String[0]);
	}
	
	public boolean areButtonsVisible()
	{
		return buttons.isChecked();
	}
}
