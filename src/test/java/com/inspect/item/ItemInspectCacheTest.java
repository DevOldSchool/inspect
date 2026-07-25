package com.inspect.item;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.google.gson.Gson;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class ItemInspectCacheTest
{
	@Rule
	public TemporaryFolder temporaryFolder = new TemporaryFolder();

	@Test
	public void readsFreshDiskCache() throws Exception
	{
		Path directory = temporaryFolder.newFolder().toPath();
		ItemInspectCache cache = new ItemInspectCache(new Gson(), directory);
		ItemInspectInfo info = info(4151, 1000L);

		cache.put(info).get();
		cache.shutDown();

		ItemInspectCache restored = new ItemInspectCache(new Gson(), directory);
		Optional<ItemInspectInfo> cached = restored.get(4151, 1100L, 7).get();

		assertTrue(cached.isPresent());
		restored.shutDown();
	}

	@Test
	public void readsFreshVariantSearchFromDisk() throws Exception
	{
		Path directory = temporaryFolder.newFolder().toPath();
		ItemInspectCache cache = new ItemInspectCache(new Gson(), directory);
		List<ItemInspectVariant> variants = variants();

		cache.putVariants("Dragon dagger", variants, 1000L).get();
		cache.shutDown();

		ItemInspectCache restored = new ItemInspectCache(new Gson(), directory);
		Optional<List<ItemInspectVariant>> cached = restored.getVariants("dragon dagger", 1100L, 7).get();

		assertTrue(cached.isPresent());
		assertEquals(variants, cached.get());
		restored.shutDown();
	}

	@Test
	public void ignoresExpiredDiskCache() throws Exception
	{
		Path directory = temporaryFolder.newFolder().toPath();
		ItemInspectCache cache = new ItemInspectCache(new Gson(), directory);
		ItemInspectInfo info = info(4151, 1000L);

		cache.put(info).get();
		Optional<ItemInspectInfo> cached = cache.get(4151, 1000L + 8L * 24L * 60L * 60L, 7).get();

		assertFalse(cached.isPresent());
		cache.shutDown();
	}

	@Test
	public void keepsVariantCacheAtExactTtlBoundaryAndExpiresItAfterward() throws Exception
	{
		Path directory = temporaryFolder.newFolder().toPath();
		ItemInspectCache cache = new ItemInspectCache(new Gson(), directory);
		cache.putVariants("Dragon dagger", variants(), 1000L).get();
		long sevenDays = 7L * 24L * 60L * 60L;

		assertTrue(cache.getVariants("Dragon dagger", 1000L + sevenDays, 7).get().isPresent());
		assertFalse(cache.getVariants("Dragon dagger", 1000L + sevenDays + 1L, 7).get().isPresent());
		cache.shutDown();
	}

	@Test
	public void keepsCacheAtExactTtlBoundaryAndExpiresItAfterward() throws Exception
	{
		Path directory = temporaryFolder.newFolder().toPath();
		ItemInspectCache cache = new ItemInspectCache(new Gson(), directory);
		ItemInspectInfo info = info(4151, 1000L);

		cache.put(info).get();
		long sevenDays = 7L * 24L * 60L * 60L;

		assertTrue(cache.get(4151, 1000L + sevenDays, 7).get().isPresent());
		assertFalse(cache.get(4151, 1000L + sevenDays + 1L, 7).get().isPresent());
		cache.shutDown();
	}

	@Test
	public void zeroTtlDoesNotReturnNewlyCachedItem() throws Exception
	{
		Path directory = temporaryFolder.newFolder().toPath();
		ItemInspectCache cache = new ItemInspectCache(new Gson(), directory);
		ItemInspectInfo info = info(4151, 1000L);

		cache.put(info).get();
		Optional<ItemInspectInfo> cached = cache.get(4151, 1000L, 0).get();

		assertFalse(cached.isPresent());
		cache.shutDown();
	}

	@Test
	public void ignoresCorruptDiskCache() throws Exception
	{
		Path directory = temporaryFolder.newFolder().toPath();
		ItemInspectCache cache = new ItemInspectCache(new Gson(), directory);
		ItemInspectInfo info = info(4151, 1000L);
		cache.put(info).get();
		cache.shutDown();

		Files.write(directory.resolve(info.cacheKey() + ".json"), "{".getBytes(StandardCharsets.UTF_8));

		ItemInspectCache restored = new ItemInspectCache(new Gson(), directory);
		Optional<ItemInspectInfo> cached = restored.get(4151, 1100L, 7).get();

		assertFalse(cached.isPresent());
		restored.shutDown();
	}

	@Test
	public void ignoresCorruptVariantDiskCache() throws Exception
	{
		Path directory = temporaryFolder.newFolder().toPath();
		Files.write(directory.resolve("variants.json"), "{".getBytes(StandardCharsets.UTF_8));
		ItemInspectCache cache = new ItemInspectCache(new Gson(), directory);

		assertFalse(cache.getVariants("Dragon dagger", 1100L, 7).get().isPresent());
		assertFalse(Files.exists(directory.resolve("variants.json")));
		cache.shutDown();
	}

	@Test
	public void rejectsDiskPayloadForDifferentItemId() throws Exception
	{
		Path directory = temporaryFolder.newFolder().toPath();
		Gson gson = new Gson();
		ItemInspectCache cache = new ItemInspectCache(gson, directory);
		ItemInspectInfo indexedInfo = info(4151, 1000L);
		cache.put(indexedInfo).get();
		cache.shutDown();

		Path cacheFile = directory.resolve(indexedInfo.cacheKey() + ".json");
		Files.write(cacheFile, gson.toJson(info(995, 1000L)).getBytes(StandardCharsets.UTF_8));

		ItemInspectCache restored = new ItemInspectCache(gson, directory);
		Optional<ItemInspectInfo> cached = restored.get(4151, 1100L, 7).get();

		assertFalse(cached.isPresent());
		assertFalse(Files.exists(cacheFile));
		restored.shutDown();
	}

	@Test
	public void ignoresEntireIndexWhenAnyEntryIsCorrupt() throws Exception
	{
		Path directory = temporaryFolder.newFolder().toPath();
		ItemInspectInfo info = info(4151, 1000L);
		ItemInspectCache cache = new ItemInspectCache(new Gson(), directory);
		cache.put(info).get();
		cache.shutDown();

		Path indexFile = directory.resolve("index.json");
		String corruptIndex = "{\"4151\":\"" + info.cacheKey() + "\",\"not-an-item-id\":\"invalid\"}";
		Files.write(indexFile, corruptIndex.getBytes(StandardCharsets.UTF_8));

		ItemInspectCache restored = new ItemInspectCache(new Gson(), directory);
		Optional<ItemInspectInfo> cached = restored.get(4151, 1100L, 7).get();

		assertFalse(cached.isPresent());
		assertFalse(Files.exists(indexFile));
		restored.shutDown();
	}

	@Test
	public void rejectsCacheKeyThatEscapesCacheDirectory() throws Exception
	{
		Path parent = temporaryFolder.newFolder().toPath();
		Path directory = Files.createDirectory(parent.resolve("cache"));
		Path outsideFile = parent.resolve("outside.json");
		Files.write(outsideFile, new Gson().toJson(info(4151, 1000L)).getBytes(StandardCharsets.UTF_8));
		Path indexFile = directory.resolve("index.json");
		Files.write(indexFile, "{\"4151\":\"../outside\"}".getBytes(StandardCharsets.UTF_8));

		ItemInspectCache cache = new ItemInspectCache(new Gson(), directory);

		assertFalse(cache.get(4151, 1100L, 7).get().isPresent());
		assertFalse(Files.exists(indexFile));
		assertTrue(Files.exists(outsideFile));
		cache.shutDown();
	}

	@Test
	public void clearsMemoryAndDiskOnStartupWhenRequested() throws Exception
	{
		Path directory = temporaryFolder.newFolder().toPath();
		ItemInspectCache cache = new ItemInspectCache(new Gson(), directory);
		ItemInspectInfo info = info(4151, 1000L);
		cache.put(info).get();
		cache.putVariants("Dragon dagger", variants(), 1000L).get();

		cache.startUp(true).get();

		assertFalse(cache.get(4151, 1100L, 7).get().isPresent());
		assertFalse(cache.getVariants("Dragon dagger", 1100L, 7).get().isPresent());
		assertFalse(Files.exists(directory.resolve(info.cacheKey() + ".json")));
		assertFalse(Files.exists(directory.resolve("index.json")));
		assertFalse(Files.exists(directory.resolve("variants.json")));
		cache.shutDown();
	}

	@Test
	public void retainsDiskCacheOnStartupWhenClearIsNotRequested() throws Exception
	{
		Path directory = temporaryFolder.newFolder().toPath();
		ItemInspectCache cache = new ItemInspectCache(new Gson(), directory);
		ItemInspectInfo info = info(4151, 1000L);
		cache.put(info).get();
		cache.shutDown();

		ItemInspectCache restored = new ItemInspectCache(new Gson(), directory);
		restored.startUp(false).get();

		assertTrue(restored.get(4151, 1100L, 7).get().isPresent());
		restored.shutDown();
	}

	private static ItemInspectInfo info(int itemId, long fetchedAt)
	{
		return ItemInspectInfo.builder()
			.itemId(itemId)
			.wikiPage("Abyssal_whip")
			.displayName("Abyssal whip")
			.slot("weapon")
			.fetchedAtEpochSecond(fetchedAt)
			.sourceUrl("https://oldschool.runescape.wiki/w/Abyssal_whip")
			.build();
	}

	private static List<ItemInspectVariant> variants()
	{
		return Arrays.asList(
			new ItemInspectVariant(
				1215,
				"Dragon dagger",
				"Dragon_dagger",
				"Unpoisoned",
				"https://oldschool.runescape.wiki/w/Dragon_dagger#Unpoisoned"),
			new ItemInspectVariant(
				5698,
				"Dragon dagger(p++)",
				"Dragon_dagger",
				"Poison++",
				"https://oldschool.runescape.wiki/w/Dragon_dagger#Poison++"));
	}
}
