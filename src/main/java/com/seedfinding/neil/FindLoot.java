package com.seedfinding.neil;

import com.seedfinding.mcbiome.source.BiomeSource;
import com.seedfinding.mccore.rand.ChunkRand;
import com.seedfinding.mccore.state.Dimension;
import com.seedfinding.mccore.util.data.Pair;
import com.seedfinding.mccore.util.data.SpiralIterator;
import com.seedfinding.mccore.util.math.DistanceMetric;
import com.seedfinding.mccore.util.pos.BPos;
import com.seedfinding.mccore.util.pos.CPos;
import com.seedfinding.mccore.util.pos.RPos;
import com.seedfinding.mccore.version.MCVersion;
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

import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

@SuppressWarnings("unchecked")
public class FindLoot {
	// FIXME: WARNING Replace RegionStructure with RegionStructure<?,?> in streamLoot when using it
	// FIXME: I am allowing this here because of the check() function

	/**
	 * Return a stream of position for a specific item predicate withing a radius for a set of structure
	 * Supports only 1.13+ (verified for 1.15+)
	 * Returns an empty stream if the item predicate or the structure are not able to generate.
	 *
	 * @param center           The center position to start the search from
	 * @param radius           The radius in blocks to look for
	 * @param version          The version to generate the Generator
	 * @param terrainGenerator The terrainGenerator supplier for terrain and biome
	 * @param structure        The structure to look for
	 * @param itemPredicate    the item predicate to look for (could match multiple one then)
	 * @return a stream of pair Position, number of item matching the predicate
	 */
	public static Stream<Pair<CPos, Integer>> streamLoot(BPos center, int radius, MCVersion version, Supplier<TerrainGenerator> terrainGenerator, Supplier<RegionStructure> structure, Predicate<Item> itemPredicate) {
		// check that this generator can provide the desired loot
		Generator.GeneratorFactory<?> factory = Generators.get(structure.get().getClass());
		if (factory == null) {
			return Stream.empty();
		}
		Generator structureGenerator = factory.create(version);
		if (structureGenerator.getPossibleLootItems().stream().noneMatch(itemPredicate)) {
			return Stream.empty();
		}

		int chunkInRegion = structure.get().getSpacing();
		int regionSize = chunkInRegion * 16;
		SpiralIterator<RPos> spiralIterator = new SpiralIterator<>(
				new RPos(center.toRegionPos(regionSize).getX(), center.toRegionPos(regionSize).getZ(), regionSize),
				new RPos(radius / regionSize, radius / regionSize, regionSize), 1, (x, y, z) -> new RPos(x, z, regionSize)
		);
		ThreadLocal<ChunkRand> chunkRand = ThreadLocal.withInitial(ChunkRand::new);
		ThreadLocal<RegionStructure> localStructure = ThreadLocal.withInitial(structure);
		ThreadLocal<TerrainGenerator> localTerrain = ThreadLocal.withInitial(terrainGenerator);
		ThreadLocal<Generator.GeneratorFactory<?>> localFactory = ThreadLocal.withInitial(() -> factory);
		// this will be by thread and thus be "thread safe"
		return StreamSupport.stream(spiralIterator.spliterator(), true)
				.map(rPos -> localStructure.get().getInRegion(localTerrain.get().getWorldSeed(), rPos.getX(), rPos.getZ(), chunkRand.get()))
				.filter(Objects::nonNull)
				.filter(cPos -> localStructure.get().canSpawn(cPos, localTerrain.get().getBiomeSource()))
				.filter(cPos -> localStructure.get().canGenerate(cPos, localTerrain.get()))
				.map(cPos -> {
					// You should regenerate a generator since you might actually have leftover fields (however the if should catch it)
					// I will expose the reset() later on
					Generator generator = localFactory.get().create(localTerrain.get().getVersion());
					if (generator.generate(localTerrain.get(), cPos, chunkRand.get())) {
						// get the count for this position of all the matching item predicate in all the chests
						int count = ((ILoot) localStructure.get()).getLoot(localTerrain.get().getWorldSeed(), generator, chunkRand.get(), false)
								.stream().mapToInt(chestContent -> chestContent.getCount(itemPredicate)).sum();
						if (count <= 0) {
							return null;
						}
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
				.parallel(forkJoinPool)
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
				.parallel(forkJoinPool)
				.unordered()
				.takeWhile(cPosIntegerPair -> value.addAndGet(cPosIntegerPair.getSecond()) < limit)
				.map(Pair::getFirst)
				.map(CPos::toBlockPos)
				.collect(Collectors.toList());
	}


	public static void main(String[] args) {
		final MCVersion version = MCVersion.v1_16;
		final long worldSeed = 1L;
		final Dimension dimension = Dimension.OVERWORLD;
		final Supplier<TerrainGenerator> terrainGeneratorSupplier = () -> TerrainGenerator.of(BiomeSource.of(dimension, version, worldSeed));

		final BPos originSearch = BPos.ORIGIN;
		final int radiusSearch = 10000;
		// please use RegionStructure<?,?>
		final HashSet<Supplier<RegionStructure>> structureToInclude = new HashSet<>() {{
			add(() -> new DesertPyramid(version));
			add(() -> new BuriedTreasure(version));
			add(() -> new Shipwreck(version));
		}};
		// Notch + normal gold apple
		final Predicate<Item> itemPredicate = item -> item.equalsName(Items.ENCHANTED_GOLDEN_APPLE) || item.equalsName(Items.GOLDEN_APPLE);
		final int numberOfItemToFind = 50;
		final int numberOfStructureToFind = 30;
		final int parallelism = 5;

		Stream<Pair<CPos, Integer>> mergedStream1 = mergeStreams(structureToInclude, originSearch, structure -> streamLoot(originSearch, radiusSearch, version, terrainGeneratorSupplier, structure, itemPredicate));
		List<BPos> list1 = getNClosestLootPos(mergedStream1, numberOfStructureToFind, parallelism);
		System.out.println(list1);

		Stream<Pair<CPos, Integer>> mergedStream2 = mergeStreams(structureToInclude, originSearch, structure -> streamLoot(originSearch, radiusSearch, version, terrainGeneratorSupplier, structure, itemPredicate));
		List<BPos> list2 = getNItemsPos(mergedStream2, numberOfItemToFind, parallelism);
		System.out.println(list2);
	}

	private static Stream<Pair<CPos, Integer>> mergeStreams(HashSet<Supplier<RegionStructure>> structureToInclude, BPos originSearch, Function<Supplier<RegionStructure>, Stream<Pair<CPos, Integer>>> fn) {
		Stream<Pair<CPos, Integer>> stream = Stream.empty();
		for (Supplier<RegionStructure> structure : structureToInclude) {
			stream = Stream.concat(stream, fn.apply(structure));
		}
		Stream<Pair<CPos, Integer>> mergedStream = stream.sorted(Comparator.comparing(a -> originSearch.distanceTo(a.getFirst().toBlockPos(), DistanceMetric.EUCLIDEAN_SQ)));

	}

	private static boolean check(BPos pos, Dimension dimension, long worldSeed, MCVersion version, Supplier<RegionStructure> structure, Predicate<Item> itemPredicate) {
		CPos cPos = pos.toChunkPos();
		TerrainGenerator terrainGenerator1 = TerrainGenerator.of(BiomeSource.of(dimension, version, worldSeed));
		RegionStructure.Data<?> data = structure.get().at(cPos.getX(), cPos.getZ());
		boolean canStart = structure.get().canStart(data, terrainGenerator1.getWorldSeed(), new ChunkRand());
		boolean canSpawn = structure.get().canSpawn(cPos, terrainGenerator1.getBiomeSource());
		boolean canGenerate = structure.get().canGenerate(cPos, terrainGenerator1);
		Generator generator = Generators.get(structure.get().getClass()).create(terrainGenerator1.getVersion());
		boolean generate = generator.generate(terrainGenerator1, cPos);
		int count = ((ILoot) (structure.get())).getLoot(terrainGenerator1.getWorldSeed(), generator, false)
				.stream().mapToInt(chestContent -> chestContent.getCount(itemPredicate)).sum();
		if (canStart && canSpawn && canGenerate && generate && count > 0) {
			return true;
		}
		System.err.println("Failed at " + cPos + " " + canStart + " " + canSpawn + " " + canGenerate + " " + generate);
		return false;
	}
}
