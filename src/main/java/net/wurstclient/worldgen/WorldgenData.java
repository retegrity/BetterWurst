/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.worldgen;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.LayeredRegistryAccess;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.RegistryDataLoader;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.RegistryLayer;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.repository.PackRepository;
import net.minecraft.server.packs.repository.ServerPacksSource;
import net.minecraft.server.packs.resources.CloseableResourceManager;
import net.minecraft.server.packs.resources.MultiPackResourceManager;
import net.minecraft.tags.TagLoader;
import net.minecraft.util.Util;
import net.minecraft.util.datafix.DataFixers;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.levelgen.WorldDimensions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
import net.minecraft.world.level.storage.LevelStorageSource;
import net.minecraft.world.level.validation.DirectoryValidator;
import net.wurstclient.WurstClient;

/**
 * Minecraft's own world generation for one seed, loaded from the vanilla data
 * plus the worldgen datapacks of the server (like Terralith). Loading it
 * takes a few seconds, so {@link WorldgenManager} keeps it around.
 */
public final class WorldgenData implements AutoCloseable
{
	private static final Logger LOGGER =
		LoggerFactory.getLogger("Wurst Worldgen");
	
	private final long seed;
	private final List<String> packs;
	private final CloseableResourceManager resources;
	private final LevelStorageSource.LevelStorageAccess storage;
	private final RegistryAccess.Frozen registries;
	private final StructureTemplateManager templates;
	private final Map<ResourceKey<Level>, DimensionSim> dimensions =
		new HashMap<>();
	
	private WorldgenData(long seed, List<String> packs,
		CloseableResourceManager resources,
		LevelStorageSource.LevelStorageAccess storage,
		RegistryAccess.Frozen registries)
	{
		this.seed = seed;
		this.packs = packs;
		this.resources = resources;
		this.storage = storage;
		this.registries = registries;
		
		templates = new StructureTemplateManager(resources, storage,
			DataFixers.getDataFixer(),
			registries.lookupOrThrow(Registries.BLOCK));
		
		Registry<LevelStem> stems =
			registries.lookupOrThrow(Registries.LEVEL_STEM);
		for(var entry : stems.entrySet())
		{
			ResourceKey<Level> level =
				Registries.levelStemToLevel(entry.getKey());
			dimensions.put(level, new DimensionSim(this, level,
				entry.getValue(), registries, seed));
		}
	}
	
	/**
	 * Loads the worldgen of the given seed with all datapacks in the given
	 * folder. Blocks for a few seconds, so call it on a background thread.
	 */
	public static WorldgenData load(long seed, Path datapacks)
		throws IOException
	{
		return load(seed, datapacks, WurstClient.INSTANCE.getWurstFolder()
			.resolve("worldgen").resolve(".storage"));
	}
	
	/**
	 * @param storageFolder
	 *            where the temporary world folder for the structure templates
	 *            goes
	 */
	public static WorldgenData load(long seed, Path datapacks,
		Path storageFolder) throws IOException
	{
		if(!Files.isDirectory(datapacks))
			Files.createDirectories(datapacks);
			
		// vanilla and every datapack in the folder, but not the built-in
		// experimental feature packs
		PackRepository repository = ServerPacksSource
			.createPackRepository(datapacks, new DirectoryValidator(p -> true));
		repository.reload();
		List<String> selected = new ArrayList<>();
		selected.add("vanilla");
		for(String id : repository.getAvailableIds())
			if(id.startsWith("file/"))
				selected.add(id);
		repository.setSelected(selected);
		
		List<PackResources> opened = repository.openAllSelected();
		CloseableResourceManager resources =
			new MultiPackResourceManager(PackType.SERVER_DATA, opened);
		
		LevelStorageSource.LevelStorageAccess storage = null;
		try
		{
			RegistryAccess.Frozen registries = loadRegistries(resources);
			
			// jigsaw structures need a template manager, which needs a
			// world folder, even though it only reads from the datapacks
			storage = LevelStorageSource.createDefault(storageFolder)
				.createAccess("templates_" + System.nanoTime());
			
			List<String> packNames =
				selected.stream().filter(id -> !id.equals("vanilla"))
					.map(id -> id.substring("file/".length())).toList();
			return new WorldgenData(seed, packNames, resources, storage,
				registries);
			
		}catch(IOException | RuntimeException e)
		{
			resources.close();
			if(storage != null)
				storage.close();
			throw e;
		}
	}
	
	/**
	 * Loads the worldgen registries like {@code WorldLoader} does. The tags
	 * of the static registries (blocks etc.) are only used for loading and
	 * never applied, so the tags that the server sent stay in place.
	 */
	private static RegistryAccess.Frozen loadRegistries(
		CloseableResourceManager resources)
	{
		LayeredRegistryAccess<RegistryLayer> layers =
			RegistryLayer.createRegistryAccess();
		List<Registry.PendingTags<?>> staticTags =
			TagLoader.loadTagsForExistingRegistries(resources,
				layers.getLayer(RegistryLayer.STATIC));
		List<HolderLookup.RegistryLookup<?>> worldgenContext =
			TagLoader.buildUpdatedLookups(
				layers.getAccessForLoading(RegistryLayer.WORLDGEN), staticTags);
		
		Executor executor = Util.backgroundExecutor();
		RegistryAccess.Frozen worldgen =
			RegistryDataLoader.load(resources, worldgenContext,
				RegistryDataLoader.WORLDGEN_REGISTRIES, executor).join();
		
		List<HolderLookup.RegistryLookup<?>> dimensionContext =
			Stream.concat(worldgenContext.stream(), worldgen.listRegistries())
				.toList();
		RegistryAccess.Frozen datapackDimensions =
			RegistryDataLoader.load(resources, dimensionContext,
				RegistryDataLoader.DIMENSION_REGISTRIES, executor).join();
		
		// the dimensions of a normal world, overridden by the ones that the
		// datapacks define
		WorldDimensions preset = worldgen.lookupOrThrow(Registries.WORLD_PRESET)
			.getValueOrThrow(WorldPresets.NORMAL).createWorldDimensions();
		WorldDimensions.Complete dimensions = preset
			.bake(datapackDimensions.lookupOrThrow(Registries.LEVEL_STEM));
		
		return layers.replaceFrom(RegistryLayer.WORLDGEN, worldgen,
			dimensions.dimensionsRegistryAccess()).compositeAccess();
	}
	
	public DimensionSim getDimension(ResourceKey<Level> level)
	{
		return dimensions.get(level);
	}
	
	/**
	 * Finds a structure by its ID. The namespace can be left out if only one
	 * structure has that name (preferring minecraft).
	 */
	public Optional<Holder.Reference<Structure>> findStructure(String name)
	{
		Registry<Structure> structures =
			registries.lookupOrThrow(Registries.STRUCTURE);
		if(name.contains(":"))
			return Optional.ofNullable(Identifier.tryParse(name))
				.flatMap(structures::get);
		
		Optional<Holder.Reference<Structure>> vanilla =
			structures.get(Identifier.withDefaultNamespace(name));
		if(vanilla.isPresent())
			return vanilla;
		
		return structures.keySet().stream()
			.filter(id -> id.getPath().equals(name)).findFirst()
			.flatMap(structures::get);
	}
	
	/**
	 * The IDs of all structures, without the namespace for vanilla ones.
	 */
	public List<String> getStructureNames()
	{
		return registries.lookupOrThrow(Registries.STRUCTURE).keySet().stream()
			.map(id -> id.getNamespace().equals("minecraft") ? id.getPath()
				: id.toString())
			.sorted().toList();
	}
	
	public long getSeed()
	{
		return seed;
	}
	
	/**
	 * The names of the datapacks that were loaded, without vanilla.
	 */
	public List<String> getPacks()
	{
		return Collections.unmodifiableList(packs);
	}
	
	public RegistryAccess.Frozen getRegistries()
	{
		return registries;
	}
	
	public StructureTemplateManager getTemplates()
	{
		return templates;
	}
	
	@Override
	public void close()
	{
		resources.close();
		try
		{
			storage.deleteLevel();
			
		}catch(IOException e)
		{
			LOGGER.warn("Couldn't delete the worldgen template folder", e);
		}
		
		try
		{
			storage.close();
			
		}catch(IOException e)
		{
			LOGGER.warn("Couldn't close the worldgen template folder", e);
		}
	}
}
