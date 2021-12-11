package com.seedfinding.neil;

import com.seedfinding.mccore.rand.ChunkRand;
import com.seedfinding.mccore.state.Dimension;
import com.seedfinding.mccore.util.data.Pair;
import com.seedfinding.mccore.util.data.SpiralIterator;
import com.seedfinding.mccore.util.pos.BPos;
import com.seedfinding.mccore.util.pos.CPos;
import com.seedfinding.mccore.util.pos.RPos;
import com.seedfinding.mccore.version.MCVersion;
import com.seedfinding.mcfeature.GenerationContext;
import com.seedfinding.mcfeature.loot.ILoot;
import com.seedfinding.mcfeature.loot.item.Item;
import com.seedfinding.mcfeature.loot.item.Items;
import com.seedfinding.mcfeature.structure.BuriedTreasure;
import com.seedfinding.mcfeature.structure.DesertPyramid;
import com.seedfinding.mcfeature.structure.RegionStructure;
import com.seedfinding.mcfeature.structure.Shipwreck;
import com.seedfinding.mcfeature.structure.generator.Generator;
import com.seedfinding.mcfeature.structure.generator.Generators;
import com.seedfinding.mcterrain.TerrainGenerator;
import one.util.streamex.StreamEx;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

public class FindLoot {
	/**
	 * Return a stream of position for a specific item predicate withing a radius for a set of structure
	 * Supports only 1.13+ (verified for 1.15+)
	 * Returns an empty stream if the item predicate or the structure are not able to generate.
	 *
	 * @param center           The center position to start the search from
	 * @param radius           The radius in blocks to look for
	 * @param terrainGenerator The terrain generator used for loot gathering
	 * @param structure        The structure to look for
	 * @param itemPredicate    the item predicate to look for (could match multiple one then)
	 * @return a stream of pair Position, number of item matching the predicate
	 */
	public static Stream<Pair<CPos, Integer>> streamLoot(BPos center, int radius, TerrainGenerator terrainGenerator, RegionStructure<?, ?> structure, Predicate<Item> itemPredicate) {


		Generator.GeneratorFactory<?> factory = Generators.get(structure.getClass());
		if (factory == null) {
			return Stream.empty();
		}
		Generator structureGenerator = factory.create(terrainGenerator.getVersion());
		if (structureGenerator.getPossibleLootItems().stream().noneMatch(itemPredicate)) {
			return Stream.empty();
		}
		int chunkInRegion = structure.getSpacing();
		int regionSize = chunkInRegion * 16;
		SpiralIterator<RPos> spiralIterator = new SpiralIterator<>(
				new RPos(center.toRegionPos(regionSize).getX(), center.toRegionPos(regionSize).getZ(), regionSize),
				new RPos(radius / regionSize, radius / regionSize, regionSize), 1, (x, y, z) -> new RPos(x, z, regionSize)
		);
		ChunkRand chunkRand=new ChunkRand();
		// this will be by thread and thus be "thread safe"
		return StreamSupport.stream(spiralIterator.spliterator(), false)
				.map(rPos -> structure.getInRegion(terrainGenerator.getWorldSeed(), rPos.getX(), rPos.getZ(), chunkRand))
				.filter(Objects::nonNull)
				.filter(cPos -> structure.canSpawn(cPos, terrainGenerator.getBiomeSource()) && structure.canGenerate(cPos, terrainGenerator))
				.map(cPos -> {
					if (structureGenerator.generate(terrainGenerator, cPos, chunkRand)){
						// get the count for this position of all the matching item predicate in all the chests
						int count = ((ILoot) structure).getLoot(terrainGenerator.getWorldSeed(), structureGenerator, chunkRand, false)
								.stream().mapToInt(chestContent -> chestContent.getCount(itemPredicate)).sum();
						return new Pair<>(cPos, count);
					}
					return null;

				}).filter(Objects::nonNull);

	}

	/**
	 * Return the N closest loot position within bounds
	 *
	 * @param stream      the stream obtained from the streamLoot method
	 * @param limit       The N limit
	 * @param parallelism the number of threads to use (defaults to 1 if null)
	 * @return a lit of the N closest
	 */
	public static List<BPos> getNClosestLootPos(Stream<Pair<CPos, Integer>> stream, int limit, Integer parallelism) {
		int threads = parallelism == null || parallelism < 1 ? 1 : Math.min(parallelism, Runtime.getRuntime().availableProcessors());
		ForkJoinPool forkJoinPool = new ForkJoinPool(threads);
		return StreamEx.of(stream)

				.limit(limit)
				.map(Pair::getFirst)
				.map(CPos::toBlockPos)
				.collect(Collectors.toList());
	}

	/**
	 * Return the closest loot position within bounds such as we only have {limit} items at most
	 *
	 * @param stream      the stream obtained from the streamLoot method
	 * @param limit       The max number of items
	 * @param parallelism the number of threads to use (defaults to 1 if null)
	 * @return a lit of the N closest
	 */
	public static List<BPos> getNItemsPos(Stream<Pair<CPos, Integer>> stream, int limit, Integer parallelism) {
		int threads = parallelism == null || parallelism < 1 ? 1 : Math.min(parallelism, Runtime.getRuntime().availableProcessors());
		ForkJoinPool forkJoinPool = new ForkJoinPool(threads);
		AtomicInteger value = new AtomicInteger(0);
		return StreamEx.of(stream)
				.takeWhile(cPosIntegerPair -> value.addAndGet(cPosIntegerPair.getSecond()) < limit)
				.map(Pair::getFirst)
				.map(CPos::toBlockPos)
				.collect(Collectors.toList());
	}


	public static void main(String[] args) {
		final MCVersion version = MCVersion.v1_17;
		final long worldSeed = 1L;
		final Dimension dimension = Dimension.OVERWORLD;
		final GenerationContext.Context context = GenerationContext.getContext(worldSeed, dimension, version);
		final TerrainGenerator terrainGenerator = context.getGenerator();
		final BPos originSearch = BPos.ORIGIN;
		final int radiusSearch = 300000;
		final HashSet<RegionStructure<?, ?>> structureToInclude = new HashSet<RegionStructure<?, ?>>() {{
			add(new DesertPyramid(version));
			add(new BuriedTreasure(version));
			add(new Shipwreck(version));
		}};
		// Notch + normal gold apple
		final Predicate<Item> itemPredicate = item -> item.equalsName(Items.ENCHANTED_GOLDEN_APPLE) || item.equalsName(Items.GOLDEN_APPLE);
		final int numberOfItemToFind = 5;
		final int numberOfStructureToFind = 3;
		final int parallelism = 1;
		for (RegionStructure<?, ?> structure : structureToInclude) {
			System.out.println(structure.getName());
			Stream<Pair<CPos, Integer>> stream1 = streamLoot(originSearch, radiusSearch, terrainGenerator, structure, itemPredicate);
			// Find X structure Pos such as in those all X if we sum the items we get numberOfItemToFind
			List<BPos> NItemsPos = getNItemsPos(stream1, numberOfItemToFind, parallelism);
			System.out.println(NItemsPos);

			// WARNING you can not reuse a stream !

			Stream<Pair<CPos, Integer>> stream2 = streamLoot(originSearch, radiusSearch, terrainGenerator, structure, itemPredicate);
			// Find N structure which satisfies the item predicate (with N=numberOfStructureToFind)
			List<BPos> NClosestPos = getNClosestLootPos(stream2, numberOfStructureToFind, parallelism);
			System.out.println(NClosestPos);
		}
	}
}
