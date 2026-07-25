package com.inspect.item;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Stream;
import lombok.extern.slf4j.Slf4j;

@Slf4j
class ItemInspectCache
{
	private static final String INDEX_FILE = "index.json";
	private static final String VARIANT_FILE = "variants.json";

	private final Gson gson;
	private final Path cacheDirectory;
	private final ExecutorService executor = Executors.newSingleThreadExecutor(runnable ->
	{
		Thread thread = new Thread(runnable, "inspect-item-cache");
		thread.setDaemon(true);
		return thread;
	});
	private final Map<Integer, ItemInspectInfo> memoryCache = new HashMap<>();
	private final Map<Integer, String> cacheKeysByItem = new HashMap<>();
	private final Map<String, ItemInspectVariantCacheEntry> variantsBySearchTerm = new HashMap<>();
	private boolean variantsLoaded;

	ItemInspectCache(Gson gson, Path cacheDirectory)
	{
		this.gson = gson;
		this.cacheDirectory = cacheDirectory;
	}

	CompletableFuture<Void> startUp(boolean clearCache)
	{
		if (clearCache)
		{
			return clearAsync();
		}

		return CompletableFuture.completedFuture(null);
	}

	void shutDown()
	{
		memoryCache.clear();
		cacheKeysByItem.clear();
		variantsBySearchTerm.clear();
		executor.shutdownNow();
	}

	CompletableFuture<Optional<ItemInspectInfo>> get(int itemId, long nowEpochSecond, int ttlDays)
	{
		synchronized (this)
		{
			ItemInspectInfo cached = memoryCache.get(itemId);
			if (cached != null)
			{
				if (!cached.isExpired(nowEpochSecond, ttlDays))
				{
					return CompletableFuture.completedFuture(Optional.of(cached));
				}
				memoryCache.remove(itemId);
			}
		}

		return CompletableFuture.supplyAsync(() -> readFromDisk(itemId, nowEpochSecond, ttlDays), executor);
	}

	CompletableFuture<Optional<ItemInspectInfo>> getBySearchTerm(String searchTerm, long nowEpochSecond, int ttlDays)
	{
		String normalizedSearchTerm = normalizeSearchTerm(searchTerm);
		if (normalizedSearchTerm.isEmpty())
		{
			return CompletableFuture.completedFuture(Optional.empty());
		}

		synchronized (this)
		{
			for (ItemInspectInfo cached : memoryCache.values())
			{
				if (!cached.isExpired(nowEpochSecond, ttlDays) && matchesSearchTerm(cached, normalizedSearchTerm))
				{
					return CompletableFuture.completedFuture(Optional.of(cached));
				}
			}
		}

		return CompletableFuture.supplyAsync(() -> readBySearchTerm(normalizedSearchTerm, nowEpochSecond, ttlDays), executor);
	}

	CompletableFuture<Void> put(ItemInspectInfo info)
	{
		synchronized (this)
		{
			memoryCache.put(info.getItemId(), info);
			cacheKeysByItem.put(info.getItemId(), info.cacheKey());
		}

		return CompletableFuture.runAsync(() -> writeToDisk(info), executor);
	}

	CompletableFuture<Optional<List<ItemInspectVariant>>> getVariants(
		String searchTerm,
		long nowEpochSecond,
		int ttlDays)
	{
		String normalizedSearchTerm = normalizeSearchTerm(searchTerm);
		if (normalizedSearchTerm.isEmpty())
		{
			return CompletableFuture.completedFuture(Optional.empty());
		}

		synchronized (this)
		{
			ItemInspectVariantCacheEntry cached = variantsBySearchTerm.get(normalizedSearchTerm);
			if (cached != null && !cached.isExpired(nowEpochSecond, ttlDays))
			{
				return CompletableFuture.completedFuture(Optional.of(copyVariants(cached.getVariants())));
			}
		}

		return CompletableFuture.supplyAsync(
			() -> readVariants(normalizedSearchTerm, nowEpochSecond, ttlDays),
			executor);
	}

	CompletableFuture<Void> putVariants(
		String searchTerm,
		List<ItemInspectVariant> variants,
		long fetchedAtEpochSecond)
	{
		String normalizedSearchTerm = normalizeSearchTerm(searchTerm);
		if (normalizedSearchTerm.isEmpty())
		{
			return CompletableFuture.completedFuture(null);
		}

		ItemInspectVariantCacheEntry entry = new ItemInspectVariantCacheEntry(
			normalizedSearchTerm,
			fetchedAtEpochSecond,
			copyVariants(variants));
		synchronized (this)
		{
			variantsBySearchTerm.put(normalizedSearchTerm, entry);
			variantsLoaded = true;
		}

		return CompletableFuture.runAsync(this::writeVariants, executor);
	}

	CompletableFuture<Void> clearAsync()
	{
		synchronized (this)
		{
			memoryCache.clear();
			cacheKeysByItem.clear();
			variantsBySearchTerm.clear();
			variantsLoaded = true;
		}

		return CompletableFuture.runAsync(() ->
		{
			if (!Files.isDirectory(cacheDirectory)) {
				return;
			}

			try (Stream<Path> files = Files.list(cacheDirectory)) {
				files.forEach(path ->
				{
					try {
						Files.deleteIfExists(path);
					} catch (IOException ex) {
						log.debug("Unable to delete Item Inspect cache file {}", path, ex);
					}
				});
			} catch (IOException ex) {
				log.debug("Unable to clear Item Inspect cache", ex);
			}
		}, executor);
	}

	private Optional<List<ItemInspectVariant>> readVariants(
		String normalizedSearchTerm,
		long nowEpochSecond,
		int ttlDays)
	{
		try
		{
			Files.createDirectories(cacheDirectory);
			loadVariantsIfNeeded();

			ItemInspectVariantCacheEntry cached;
			synchronized (this)
			{
				cached = variantsBySearchTerm.get(normalizedSearchTerm);
				if (cached == null)
				{
					return Optional.empty();
				}
				if (!cached.isExpired(nowEpochSecond, ttlDays))
				{
					return Optional.of(copyVariants(cached.getVariants()));
				}
				variantsBySearchTerm.remove(normalizedSearchTerm);
			}

			writeVariants();
		}
		catch (IOException ex)
		{
			log.debug("Unable to read Item Inspect variant cache", ex);
		}
		return Optional.empty();
	}

	private Optional<ItemInspectInfo> readFromDisk(int itemId, long nowEpochSecond, int ttlDays)
	{
		try
		{
			Files.createDirectories(cacheDirectory);
			loadIndexIfNeeded();

			String cacheKey;
			synchronized (this)
			{
				cacheKey = cacheKeysByItem.get(itemId);
			}
			if (cacheKey == null)
			{
				return Optional.empty();
			}

			return readCachedInfo(itemId, cacheKey, nowEpochSecond, ttlDays);
		}
		catch (IOException ex)
		{
			log.debug("Unable to read Item Inspect cache", ex);
			return Optional.empty();
		}
	}

	private Optional<ItemInspectInfo> readBySearchTerm(String normalizedSearchTerm, long nowEpochSecond, int ttlDays)
	{
		try
		{
			Files.createDirectories(cacheDirectory);
			loadIndexIfNeeded();

			Map<Integer, String> cacheKeys;
			synchronized (this)
			{
				cacheKeys = new HashMap<>(cacheKeysByItem);
			}

			for (Map.Entry<Integer, String> entry : cacheKeys.entrySet())
			{
				Optional<ItemInspectInfo> cached = readCachedInfo(entry.getKey(), entry.getValue(), nowEpochSecond, ttlDays);
				if (cached.isPresent() && matchesSearchTerm(cached.get(), normalizedSearchTerm))
				{
					return cached;
				}
			}
		}
		catch (IOException ex)
		{
			log.debug("Unable to read Item Inspect cache by search term", ex);
		}
		return Optional.empty();
	}

	private Optional<ItemInspectInfo> readCachedInfo(int itemId, String cacheKey, long nowEpochSecond, int ttlDays)
		throws IOException
	{
		Path cacheFile = cachePath(cacheKey);
		if (!Files.isRegularFile(cacheFile))
		{
			return Optional.empty();
		}

		try (Reader reader = Files.newBufferedReader(cacheFile, StandardCharsets.UTF_8))
		{
			ItemInspectInfo info = gson.fromJson(reader, ItemInspectInfo.class);
			if (info == null || info.getItemId() != itemId || info.isExpired(nowEpochSecond, ttlDays))
			{
				Files.deleteIfExists(cacheFile);
				removeIndex(itemId);
				return Optional.empty();
			}

			synchronized (this)
			{
				memoryCache.put(itemId, info);
				cacheKeysByItem.put(itemId, cacheKey);
			}
			return Optional.of(info);
		}
		catch (RuntimeException | IOException ex)
		{
			log.debug("Ignoring corrupt Item Inspect cache file {}", cacheFile, ex);
			Files.deleteIfExists(cacheFile);
			removeIndex(itemId);
			return Optional.empty();
		}
	}

	private void writeToDisk(ItemInspectInfo info)
	{
		try
		{
			Files.createDirectories(cacheDirectory);
			Path cacheFile = cachePath(info.cacheKey());
			Path tempFile = cacheDirectory.resolve(info.cacheKey() + ".tmp");

			try (Writer writer = Files.newBufferedWriter(tempFile, StandardCharsets.UTF_8))
			{
				gson.toJson(info, writer);
			}
			Files.move(tempFile, cacheFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
			writeIndex();
		}
		catch (IOException ex)
		{
			log.debug("Unable to write Item Inspect cache", ex);
		}
	}

	private void loadVariantsIfNeeded() throws IOException
	{
		synchronized (this)
		{
			if (variantsLoaded)
			{
				return;
			}
		}

		Path variantsFile = cacheDirectory.resolve(VARIANT_FILE);
		if (!Files.isRegularFile(variantsFile))
		{
			synchronized (this)
			{
				variantsLoaded = true;
			}
			return;
		}

		Map<String, ItemInspectVariantCacheEntry> loadedVariants = new HashMap<>();
		try (Reader reader = Files.newBufferedReader(variantsFile, StandardCharsets.UTF_8))
		{
			JsonObject variants = gson.fromJson(reader, JsonObject.class);
			if (variants != null)
			{
				for (Map.Entry<String, JsonElement> entry : variants.entrySet())
				{
					ItemInspectVariantCacheEntry cached = gson.fromJson(
						entry.getValue(),
						ItemInspectVariantCacheEntry.class);
					if (!isValidVariantEntry(entry.getKey(), cached))
					{
						throw new IllegalArgumentException("Invalid Item Inspect variant cache entry");
					}
					loadedVariants.put(entry.getKey(), cached);
				}
			}

			synchronized (this)
			{
				if (!variantsLoaded)
				{
					variantsBySearchTerm.putAll(loadedVariants);
					variantsLoaded = true;
				}
			}
		}
		catch (RuntimeException ex)
		{
			log.debug("Ignoring corrupt Item Inspect variant cache", ex);
			Files.deleteIfExists(variantsFile);
			synchronized (this)
			{
				variantsBySearchTerm.clear();
				variantsLoaded = true;
			}
		}
	}

	private void writeVariants()
	{
		try
		{
			Files.createDirectories(cacheDirectory);
			Map<String, ItemInspectVariantCacheEntry> variants;
			synchronized (this)
			{
				variants = new HashMap<>(variantsBySearchTerm);
			}

			Path tempFile = cacheDirectory.resolve(VARIANT_FILE + ".tmp");
			try (Writer writer = Files.newBufferedWriter(tempFile, StandardCharsets.UTF_8))
			{
				gson.toJson(variants, writer);
			}
			Files.move(
				tempFile,
				cacheDirectory.resolve(VARIANT_FILE),
				StandardCopyOption.REPLACE_EXISTING,
				StandardCopyOption.ATOMIC_MOVE);
		}
		catch (IOException ex)
		{
			log.debug("Unable to write Item Inspect variant cache", ex);
		}
	}

	private void loadIndexIfNeeded() throws IOException
	{
		synchronized (this)
		{
			if (!cacheKeysByItem.isEmpty())
			{
				return;
			}
		}

		Path indexFile = cacheDirectory.resolve(INDEX_FILE);
		if (!Files.isRegularFile(indexFile))
		{
			return;
		}

		Map<Integer, String> loadedCacheKeys = new HashMap<>();
		try (Reader reader = Files.newBufferedReader(indexFile, StandardCharsets.UTF_8))
		{
			JsonObject index = gson.fromJson(reader, JsonObject.class);
			if (index == null)
			{
				return;
			}

			for (Map.Entry<String, JsonElement> entry : index.entrySet())
			{
				int itemId = Integer.parseInt(entry.getKey());
				String cacheKey = entry.getValue().getAsString();
				if (itemId < 0 || !isValidCacheKey(cacheKey))
				{
					throw new IllegalArgumentException("Invalid Item Inspect cache index entry");
				}
				loadedCacheKeys.put(itemId, cacheKey);
			}

			synchronized (this)
			{
				if (cacheKeysByItem.isEmpty())
				{
					cacheKeysByItem.putAll(loadedCacheKeys);
				}
			}
		}
		catch (RuntimeException ex)
		{
			log.debug("Ignoring corrupt Item Inspect cache index", ex);
			Files.deleteIfExists(indexFile);
		}
	}

	private void writeIndex() throws IOException
	{
		JsonObject index = new JsonObject();
		synchronized (this)
		{
			for (Map.Entry<Integer, String> entry : cacheKeysByItem.entrySet())
			{
				index.addProperty(Integer.toString(entry.getKey()), entry.getValue());
			}
		}

		Path tempFile = cacheDirectory.resolve(INDEX_FILE + ".tmp");
		try (Writer writer = Files.newBufferedWriter(tempFile, StandardCharsets.UTF_8))
		{
			gson.toJson(index, writer);
		}
		Files.move(tempFile, cacheDirectory.resolve(INDEX_FILE), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
	}

	private void removeIndex(int itemId) throws IOException
	{
		synchronized (this)
		{
			cacheKeysByItem.remove(itemId);
		}
		writeIndex();
	}

	private Path cachePath(String cacheKey)
	{
		return cacheDirectory.resolve(cacheKey + ".json");
	}

	private static boolean isValidCacheKey(String cacheKey)
	{
		return cacheKey != null && !cacheKey.isEmpty() && cacheKey.matches("[A-Za-z0-9._-]+");
	}

	private static boolean isValidVariantEntry(String searchTerm, ItemInspectVariantCacheEntry entry)
	{
		if (entry == null
			|| searchTerm == null
			|| searchTerm.isEmpty()
			|| !searchTerm.equals(entry.getNormalizedSearchTerm())
			|| !searchTerm.equals(normalizeSearchTerm(searchTerm))
			|| entry.getFetchedAtEpochSecond() < 0
			|| entry.getVariants() == null)
		{
			return false;
		}

		for (ItemInspectVariant variant : entry.getVariants())
		{
			if (variant == null
				|| variant.getId() < 0
				|| variant.getDisplayName() == null
				|| variant.getDisplayName().trim().isEmpty()
				|| variant.getWikiPage() == null
				|| variant.getWikiPage().trim().isEmpty()
				|| variant.getSourceUrl() == null
				|| variant.getSourceUrl().trim().isEmpty())
			{
				return false;
			}
		}
		return true;
	}

	private static List<ItemInspectVariant> copyVariants(List<ItemInspectVariant> variants)
	{
		return variants == null
			? Collections.emptyList()
			: Collections.unmodifiableList(new ArrayList<>(variants));
	}

	private static boolean matchesSearchTerm(ItemInspectInfo info, String normalizedSearchTerm)
	{
		return normalizedSearchTerm.equals(normalizeSearchTerm(info.getDisplayName()))
			|| normalizedSearchTerm.equals(normalizeSearchTerm(info.getWikiPage()));
	}

	private static String normalizeSearchTerm(String value)
	{
		return value == null
			? ""
			: value.replace('_', ' ')
				.replace('\u00A0', ' ')
				.trim()
				.replaceAll("\\s+", " ")
				.toLowerCase(java.util.Locale.ENGLISH);
	}
}
