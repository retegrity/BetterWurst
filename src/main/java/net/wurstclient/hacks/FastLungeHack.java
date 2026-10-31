/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.food.FoodData;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.PiercingWeapon;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.Enchantments;
import net.wurstclient.Category;
import net.wurstclient.SearchTags;
import net.wurstclient.events.UpdateListener;
import net.wurstclient.hack.Hack;
import net.wurstclient.settings.SliderSetting;
import net.wurstclient.settings.SliderSetting.ValueDisplay;
import net.wurstclient.util.ChatUtils;
import net.wurstclient.util.ItemUtils;

@SearchTags({"fast lunge", "spear", "lunge", "LongJump", "long jump",
	"SpearBoost", "spear boost"})
public final class FastLungeHack extends Hack implements UpdateListener
{
	private final SliderSetting interval = new SliderSetting("Interval",
		"Ticks between lunges.\n\n"
			+ "2 is the fastest that doesn't flag Grim's PacketOrderE. Higher"
			+ " values miss fewer lunges on laggy connections.",
		2, 2, 10, 1, ValueDisplay.INTEGER.withSuffix(" ticks"));
	
	private final SliderSetting maxLunges = new SliderSetting("Max lunges",
		"FastLunge turns itself off after this many lunges, or once it can't"
			+ " lunge anymore (not enough food, spear about to break, etc.).",
		0, 0, 50, 1, ValueDisplay.INTEGER.withLabel(0, "unlimited"));
	
	// the server allows stabbing this many ticks early (cannotAttackWithItem)
	private static final int SERVER_LENIENCY = 5;
	
	// Lunge needs at least this much food, unless you are in creative mode
	private static final int MIN_FOOD = 7;
	
	private int spearSlot;
	private int restSlot;
	private int originalSlot;
	private int waitTicks;
	private int lunges;
	private int lungeLevel;
	
	// The server only sends food updates after a while, so predict them.
	// Every 4 exhaustion (1 per Lunge level) uses up one saturation or food
	// point.
	private int predictedFood;
	private float predictedSaturation;
	
	public FastLungeHack()
	{
		super("FastLunge");
		setCategory(Category.MOVEMENT);
		addSetting(interval);
		addSetting(maxLunges);
	}
	
	/*
	 * How it works: the server only lets you stab with a spear once it's fully
	 * charged, but it reads the cooldown length from your attack speed
	 * attribute, which only updates when the server ticks you. So FastLunge
	 * holds an item without an attack speed modifier (cooldown: 5 ticks, which
	 * the server's leniency already covers), switches to the spear, stabs and
	 * switches back before the spear's attack speed ever applies.
	 *
	 * The switch back is sent the next tick, because Grim's PacketOrderE
	 * flags switching slots in the same tick as an attack. That's why it can
	 * lunge every 2 ticks at most.
	 */
	@Override
	protected void onEnable()
	{
		LocalPlayer player = MC.player;
		originalSlot = player.getInventory().getSelectedSlot();
		lunges = 0;
		
		spearSlot = findSpear(player);
		if(spearSlot == -1)
		{
			ChatUtils
				.error("FastLunge needs a spear with Lunge in your hotbar.");
			setEnabled(false);
			return;
		}
		
		restSlot = findRestSlot(player);
		if(restSlot == -1)
		{
			ChatUtils.error("FastLunge needs a free hotbar slot or an item that"
				+ " isn't a weapon or tool.");
			setEnabled(false);
			return;
		}
		
		lungeLevel = getLungeLevel(player.getInventory().getItem(spearSlot));
		FoodData food = player.getFoodData();
		predictedFood = food.getFoodLevel();
		predictedSaturation = food.getSaturationLevel();
		
		// If you aren't already holding the rest item, the server needs a
		// tick with it in your hand first, so your attack speed updates.
		if(originalSlot != restSlot)
		{
			player.getInventory().setSelectedSlot(restSlot);
			waitTicks = 2;
		}else
			waitTicks = 0;
		
		EVENTS.add(UpdateListener.class, this);
	}
	
	@Override
	protected void onDisable()
	{
		EVENTS.remove(UpdateListener.class, this);
		
		LocalPlayer player = MC.player;
		if(player != null)
			player.getInventory().setSelectedSlot(originalSlot);
	}
	
	@Override
	public void onUpdate()
	{
		if(waitTicks > 0)
		{
			waitTicks--;
			return;
		}
		
		LocalPlayer player = MC.player;
		FoodData food = player.getFoodData();
		predictedFood = Math.min(predictedFood, food.getFoodLevel());
		predictedSaturation =
			Math.min(predictedSaturation, food.getSaturationLevel());
		
		ItemStack spear = player.getInventory().getItem(spearSlot);
		PiercingWeapon weapon = spear.get(DataComponents.PIERCING_WEAPON);
		if(!canLunge(player, spear, weapon))
		{
			setEnabled(false);
			return;
		}
		
		// vanilla sends the spear slot right before the stab
		// (MultiPlayerGameMode.ensureHasSentCarriedItem)
		player.getInventory().setSelectedSlot(spearSlot);
		MC.gameMode.piercingAttack(weapon);
		player.swing(InteractionHand.MAIN_HAND);
		
		// sent at the start of the next tick, before the movement packet
		player.getInventory().setSelectedSlot(restSlot);
		
		lunges++;
		useFood(lungeLevel);
		waitTicks = interval.getValueI() - 1;
	}
	
	private boolean canLunge(LocalPlayer player, ItemStack spear,
		PiercingWeapon weapon)
	{
		if(weapon == null || getLungeLevel(spear) <= 0)
			return false;
		
		// don't break the spear
		if(spear.isDamageableItem()
			&& spear.getMaxDamage() - spear.getDamageValue() <= 1)
			return false;
		
		int max = maxLunges.getValueI();
		if(max > 0 && lunges >= max)
			return false;
		
		// requirements of the Lunge enchantment
		if(player.isPassenger() || player.isFallFlying() || player.isInWater())
			return false;
		
		return player.getAbilities().instabuild || predictedFood >= MIN_FOOD;
	}
	
	private void useFood(int points)
	{
		if(MC.player.getAbilities().instabuild)
			return;
		
		float fromSaturation = Math.min(predictedSaturation, points);
		predictedSaturation -= fromSaturation;
		predictedFood -= Math.round(points - fromSaturation);
	}
	
	private int findSpear(LocalPlayer player)
	{
		int best = -1;
		int bestLevel = 0;
		for(int slot = 0; slot < 9; slot++)
		{
			ItemStack stack = player.getInventory().getItem(slot);
			if(!stack.is(ItemTags.SPEARS)
				|| !stack.has(DataComponents.PIERCING_WEAPON))
				continue;
			
			if(stack.isDamageableItem()
				&& stack.getMaxDamage() - stack.getDamageValue() <= 1)
				continue;
			
			int level = getLungeLevel(stack);
			if(level > bestLevel)
			{
				best = slot;
				bestLevel = level;
			}
		}
		
		return best;
	}
	
	/**
	 * Finds a hotbar slot whose item has a short enough attack cooldown that
	 * the server lets you stab right after switching from it. Prefers the
	 * item you are holding, then empty slots.
	 */
	private int findRestSlot(LocalPlayer player)
	{
		int selected = player.getInventory().getSelectedSlot();
		if(selected != spearSlot && isRestItem(player, selected))
			return selected;
		
		for(int slot = 0; slot < 9; slot++)
			if(slot != spearSlot
				&& player.getInventory().getItem(slot).isEmpty())
				return slot;
			
		for(int slot = 0; slot < 9; slot++)
			if(slot != spearSlot && isRestItem(player, slot))
				return slot;
			
		return -1;
	}
	
	private boolean isRestItem(LocalPlayer player, int slot)
	{
		ItemStack stack = player.getInventory().getItem(slot);
		double base = player.getAttributeBaseValue(Attributes.ATTACK_SPEED);
		double attackSpeed = stack.isEmpty() ? base
			: ItemUtils.calculateModifiedAttribute(stack.getItem(),
				Attributes.ATTACK_SPEED, base, EquipmentSlot.MAINHAND);
		if(attackSpeed <= 0)
			return false;
		
		// the cooldown ticks the server has counted by the next stab
		int ticks = interval.getValueI() - 1 + SERVER_LENIENCY;
		double delay = 20 / attackSpeed;
		return ticks / delay >= 1;
	}
	
	private int getLungeLevel(ItemStack stack)
	{
		if(MC.level == null)
			return 0;
		
		return MC.level.registryAccess().lookup(Registries.ENCHANTMENT)
			.flatMap(reg -> reg.get(Enchantments.LUNGE))
			.map(holder -> EnchantmentHelper.getItemEnchantmentLevel(holder,
				stack))
			.orElse(0);
	}
}
