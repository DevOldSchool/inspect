package com.inspect.npc;

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
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Stream;
import lombok.extern.slf4j.Slf4j;

@Slf4j
class NpcInspectCache
{
	private static final String INDEX_FILE = "index.json";

	private final Gson gson;
	private final Path cacheDirectory;
	private final ExecutorService executor = Executors.newSingleThreadExecutor(runnable ->
	{
		Thread thread = new Thread(runnable, "inspect-npc-cache");
		thread.setDaemon(true);
		return thread;
	});
	private final Map<Integer, NpcCombatInfo> memoryCache = new HashMap<>();
	private final Map<Integer, String> cacheKeysByNpc = new HashMap<>();

	NpcInspectCache(Gson gson, Path cacheDirectory)
	{
		this.gson = gson;
		this.cacheDirectory = cacheDirectory;
	}

	void startUp(boolean clearCache)
	{
		if (clearCache)
		{
			clearAsync();
		}
	}

	void shutDown()
	{
		memoryCache.clear();
		cacheKeysByNpc.clear();
		executor.shutdownNow();
	}

	CompletableFuture<Optional<NpcCombatInfo>> get(int npcId, long nowEpochSecond, int ttlDays)
	{
		return get(npcId, nowEpochSecond, ttlDays, false);
	}

	CompletableFuture<Optional<NpcCombatInfo>> get(int npcId, long nowEpochSecond, int ttlDays, boolean includeExpired)
	{
		synchronized (this)
		{
			NpcCombatInfo cached = memoryCache.get(npcId);
			if (cached != null)
			{
				if (includeExpired || !cached.isExpired(nowEpochSecond, ttlDays))
				{
					return CompletableFuture.completedFuture(Optional.of(cached));
				}
				memoryCache.remove(npcId);
			}
		}

		return CompletableFuture.supplyAsync(() -> readFromDisk(npcId, nowEpochSecond, ttlDays, includeExpired), executor);
	}

	CompletableFuture<Optional<NpcCombatInfo>> getBySearchTerm(String searchTerm, long nowEpochSecond, int ttlDays)
	{
		return getBySearchTerm(searchTerm, nowEpochSecond, ttlDays, false);
	}

	CompletableFuture<Optional<NpcCombatInfo>> getBySearchTerm(String searchTerm, long nowEpochSecond, int ttlDays, boolean includeExpired)
	{
		String normalizedSearchTerm = normalizeSearchTerm(searchTerm);
		if (normalizedSearchTerm.isEmpty())
		{
			return CompletableFuture.completedFuture(Optional.empty());
		}

		synchronized (this)
		{
			for (NpcCombatInfo cached : memoryCache.values())
			{
				if ((includeExpired || !cached.isExpired(nowEpochSecond, ttlDays)) && matchesSearchTerm(cached, normalizedSearchTerm))
				{
					return CompletableFuture.completedFuture(Optional.of(cached));
				}
			}
		}

		return CompletableFuture.supplyAsync(() -> readBySearchTerm(normalizedSearchTerm, nowEpochSecond, ttlDays, includeExpired), executor);
	}

	CompletableFuture<Void> put(NpcCombatInfo info)
	{
		synchronized (this)
		{
			memoryCache.put(info.getNpcId(), info);
			cacheKeysByNpc.put(info.getNpcId(), info.cacheKey());
		}

		return CompletableFuture.runAsync(() -> writeToDisk(info), executor);
	}

	CompletableFuture<Void> clearAsync()
	{
		synchronized (this)
		{
			memoryCache.clear();
			cacheKeysByNpc.clear();
		}

		return CompletableFuture.runAsync(() ->
		{
			if (!Files.isDirectory(cacheDirectory))
			{
				return;
			}

			try (Stream<Path> files = Files.list(cacheDirectory))
			{
				files.forEach(path ->
				{
					try
					{
						Files.deleteIfExists(path);
					}
					catch (IOException ex)
					{
						log.debug("Unable to delete NPC Inspect cache file {}", path, ex);
					}
				});
			}
			catch (IOException ex)
			{
				log.debug("Unable to clear NPC Inspect cache", ex);
			}
		}, executor);
	}

	private Optional<NpcCombatInfo> readFromDisk(int npcId, long nowEpochSecond, int ttlDays, boolean includeExpired)
	{
		try
		{
			Files.createDirectories(cacheDirectory);
			loadIndexIfNeeded();

			String cacheKey;
			synchronized (this)
			{
				cacheKey = cacheKeysByNpc.get(npcId);
			}
			if (cacheKey == null)
			{
				return Optional.empty();
			}

			Path cacheFile = cachePath(cacheKey);
			if (!Files.isRegularFile(cacheFile))
			{
				return Optional.empty();
			}

			try (Reader reader = Files.newBufferedReader(cacheFile, StandardCharsets.UTF_8))
			{
				NpcCombatInfo info = gson.fromJson(reader, NpcCombatInfo.class);
				if (info == null
					|| info.getNpcId() != npcId
					|| !info.hasCurrentCacheSchema()
					|| !cacheKey.equals(info.cacheKey()))
				{
					Files.deleteIfExists(cacheFile);
					removeIndex(npcId);
					return Optional.empty();
				}

				if (!includeExpired && info.isExpired(nowEpochSecond, ttlDays))
				{
					return Optional.empty();
				}

				synchronized (this)
				{
					memoryCache.put(npcId, info);
					cacheKeysByNpc.put(npcId, cacheKey);
				}
				return Optional.of(info);
			}
			catch (RuntimeException | IOException ex)
			{
				log.debug("Ignoring corrupt NPC Inspect cache file {}", cacheFile, ex);
				Files.deleteIfExists(cacheFile);
				removeIndex(npcId);
				return Optional.empty();
			}
		}
		catch (IOException ex)
		{
			log.debug("Unable to read NPC Inspect cache", ex);
			return Optional.empty();
		}
	}

	private Optional<NpcCombatInfo> readBySearchTerm(String normalizedSearchTerm, long nowEpochSecond, int ttlDays, boolean includeExpired)
	{
		try
		{
			Files.createDirectories(cacheDirectory);
			loadIndexIfNeeded();

			Map<Integer, String> cacheKeys;
			synchronized (this)
			{
				cacheKeys = new HashMap<>(cacheKeysByNpc);
			}

			for (Map.Entry<Integer, String> entry : cacheKeys.entrySet())
			{
				Optional<NpcCombatInfo> cached = readCachedInfo(entry.getKey(), entry.getValue(), nowEpochSecond, ttlDays, includeExpired);
				if (cached.isPresent() && matchesSearchTerm(cached.get(), normalizedSearchTerm))
				{
					return cached;
				}
			}
		}
		catch (IOException ex)
		{
			log.debug("Unable to read NPC Inspect cache by search term", ex);
		}
		return Optional.empty();
	}

	private Optional<NpcCombatInfo> readCachedInfo(int npcId, String cacheKey, long nowEpochSecond, int ttlDays, boolean includeExpired)
		throws IOException
	{
		Path cacheFile = cachePath(cacheKey);
		if (!Files.isRegularFile(cacheFile))
		{
			return Optional.empty();
		}

		try (Reader reader = Files.newBufferedReader(cacheFile, StandardCharsets.UTF_8))
		{
			NpcCombatInfo info = gson.fromJson(reader, NpcCombatInfo.class);
			if (info == null
				|| info.getNpcId() != npcId
				|| !info.hasCurrentCacheSchema()
				|| !cacheKey.equals(info.cacheKey()))
			{
				Files.deleteIfExists(cacheFile);
				removeIndex(npcId);
				return Optional.empty();
			}

			if (!includeExpired && info.isExpired(nowEpochSecond, ttlDays))
			{
				return Optional.empty();
			}

			synchronized (this)
			{
				memoryCache.put(npcId, info);
				cacheKeysByNpc.put(npcId, cacheKey);
			}
			return Optional.of(info);
		}
		catch (RuntimeException | IOException ex)
		{
			log.debug("Ignoring corrupt NPC Inspect cache file {}", cacheFile, ex);
			Files.deleteIfExists(cacheFile);
			removeIndex(npcId);
			return Optional.empty();
		}
	}

	private void writeToDisk(NpcCombatInfo info)
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
			log.debug("Unable to write NPC Inspect cache", ex);
		}
	}

	private void loadIndexIfNeeded() throws IOException
	{
		synchronized (this)
		{
			if (!cacheKeysByNpc.isEmpty())
			{
				return;
			}
		}

		Path indexFile = cacheDirectory.resolve(INDEX_FILE);
		if (!Files.isRegularFile(indexFile))
		{
			return;
		}

		try (Reader reader = Files.newBufferedReader(indexFile, StandardCharsets.UTF_8))
		{
			JsonObject index = gson.fromJson(reader, JsonObject.class);
			if (index == null)
			{
				return;
			}

			Map<Integer, String> loadedCacheKeys = new HashMap<>();
			for (Map.Entry<String, JsonElement> entry : index.entrySet())
			{
				int npcId = Integer.parseInt(entry.getKey());
				String cacheKey = entry.getValue().getAsString();
				if (npcId < 0 || !isValidCacheKey(cacheKey))
				{
					throw new IllegalArgumentException("Invalid NPC Inspect cache index entry");
				}
				loadedCacheKeys.put(npcId, cacheKey);
			}

			synchronized (this)
			{
				cacheKeysByNpc.putAll(loadedCacheKeys);
			}
		}
		catch (RuntimeException ex)
		{
			log.debug("Ignoring corrupt NPC Inspect cache index", ex);
			Files.deleteIfExists(indexFile);
		}
	}

	private void writeIndex() throws IOException
	{
		JsonObject index = new JsonObject();
		synchronized (this)
		{
			for (Map.Entry<Integer, String> entry : cacheKeysByNpc.entrySet())
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

	private void removeIndex(int npcId) throws IOException
	{
		synchronized (this)
		{
			cacheKeysByNpc.remove(npcId);
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

	private static boolean matchesSearchTerm(NpcCombatInfo info, String normalizedSearchTerm)
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
				.toLowerCase(Locale.ENGLISH);
	}
}
