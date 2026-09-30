package com.inspect.npc;

import com.google.gson.Gson;
import com.inspect.inspect.WikiCacheLookup;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import javax.annotation.Nonnull;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.NPC;
import net.runelite.api.NPCComposition;
import net.runelite.client.RuneLite;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

@Slf4j
@Singleton
public class NpcInspectService
{
	private static final HttpUrl DEFAULT_WIKI_BASE = HttpUrl.get("https://oldschool.runescape.wiki");
	private static final String USER_AGENT = "Inspect RuneLite plugin (https://github.com/DevOldSchool/inspect)";

	private final OkHttpClient httpClient;
	private final Gson gson;
	private final HttpUrl wikiBase;
	private final NpcInspectParser parser = new NpcInspectParser();
	private final NpcInspectCache cache;

	@Inject
	NpcInspectService(OkHttpClient httpClient, Gson gson)
	{
		this(httpClient, gson, DEFAULT_WIKI_BASE,
			RuneLite.RUNELITE_DIR.toPath().resolve("inspect").resolve("npc-inspect"));
	}

	NpcInspectService(OkHttpClient httpClient, Gson gson, HttpUrl wikiBase, Path cacheDirectory)
	{
		this.httpClient = httpClient;
		this.gson = gson;
		this.wikiBase = wikiBase;
		this.cache = new NpcInspectCache(gson, cacheDirectory);
	}

	public void startUp(boolean clearCache)
	{
		cache.startUp(clearCache);
	}

	public void shutDown()
	{
		cache.shutDown();
	}

	public CompletableFuture<NpcCombatInfo> refresh(NpcCombatInfo info)
	{
		NpcWikiLookup lookup = new NpcWikiLookup(info.getWikiPage(), info.getWikiAnchor(), info.getSourceUrl());
		return fetchWikitext(lookup)
			.thenApply(wikitext -> parser.parse(info.getNpcId(), info.getDisplayName(), lookup, wikitext))
			.thenCompose(updated -> updated == null || updated.getNpcId() < 0
				? CompletableFuture.completedFuture(updated)
				: cache.put(updated).thenApply(ignored -> updated))
			.exceptionally(error -> info.toBuilder().cachedFallback(true).build());
	}

	public CompletableFuture<Void> clearCacheAsync()
	{
		return cache.clearAsync();
	}

	public CompletableFuture<NpcCombatInfo> inspect(NPC npc, int ttlDays)
	{
		if (npc == null)
		{
			return CompletableFuture.completedFuture(null);
		}

		NPCComposition composition = npc.getTransformedComposition();
		if (composition == null || composition.getName() == null)
		{
			return CompletableFuture.completedFuture(null);
		}

		return inspect(composition.getId(), composition.getName(), ttlDays);
	}

	public CompletableFuture<NpcCombatInfo> inspect(int npcId, String npcName, int ttlDays)
	{
		if (npcId < 0 || npcName == null || npcName.trim().isEmpty())
		{
			return CompletableFuture.completedFuture(null);
		}

		long now = System.currentTimeMillis() / 1000L;
		return cache.get(npcId, now, ttlDays, true)
			.thenCompose(cached -> WikiCacheLookup.load(cached, info -> !info.isExpired(now, ttlDays),
				() -> fetch(npcId, npcName), info -> info.toBuilder().cachedFallback(true).build()));
	}

	public CompletableFuture<NpcCombatInfo> search(String query)
	{
		return search(query, 7);
	}

	public CompletableFuture<NpcSearchResults> searchChoices(String query, int ttlDays)
	{
		if (query == null || query.trim().isEmpty())
		{
			return CompletableFuture.completedFuture(new NpcSearchResults("", Collections.emptyList(), 0, false));
		}
		String normalized = query.trim();
		long now = System.currentTimeMillis() / 1000L;
		return cache.getSearchResults(normalized).thenCompose(cached -> WikiCacheLookup.load(cached,
			results -> !results.isExpired(now, ttlDays), () -> fetchSearchChoices(normalized), NpcSearchResults::asFallback));
	}

	public CompletableFuture<NpcCombatInfo> inspectChoice(NpcCombatInfo choice, int ttlDays)
	{
		long now = System.currentTimeMillis() / 1000L;
		return cache.get(choice.getNpcId(), now, ttlDays, true).thenCompose(cached ->
		{
			NpcCombatInfo selected = cached.filter(info -> info.cacheKey().equals(choice.cacheKey())
				&& info.getFetchedAtEpochSecond() > choice.getFetchedAtEpochSecond()).orElse(choice);
			if (selected.isCachedFallback() || selected.isExpired(now, ttlDays))
			{
				return refresh(selected);
			}
			return cache.put(selected).thenApply(ignored -> selected);
		});
	}

	private CompletableFuture<NpcSearchResults> fetchSearchChoices(String query)
	{
		return searchPages(query, 5).thenCompose(pages ->
		{
			List<CompletableFuture<List<NpcCombatInfo>>> lookups = new ArrayList<>();
			for (String page : pages)
			{
				NpcWikiLookup lookup = new NpcWikiLookup(page, null, wikiUrl(page, null));
				lookups.add(fetchWikitext(lookup).thenApply(text -> parser.parseChoices(lookup, text)));
			}
			return CompletableFuture.allOf(lookups.toArray(new CompletableFuture<?>[0])).handle((ignored, error) ->
			{
				Map<String, NpcCombatInfo> choices = new LinkedHashMap<>();
				boolean partial = false;
				for (CompletableFuture<List<NpcCombatInfo>> lookup : lookups)
				{
					if (lookup.isCompletedExceptionally())
					{
						partial = true;
						continue;
					}
					for (NpcCombatInfo info : lookup.join())
					{
						if (choices.size() < 50)
						{
							choices.putIfAbsent(info.cacheKey(), info);
						}
					}
				}
				if (choices.isEmpty() && error != null)
				{
					throw error instanceof CompletionException ? (CompletionException) error : new CompletionException(error);
				}
				return new NpcSearchResults(query, new ArrayList<>(choices.values()), System.currentTimeMillis() / 1000L, partial);
			});
		}).thenCompose(results -> results.isPartial()
			? CompletableFuture.completedFuture(results)
			: cache.putSearchResults(results).thenApply(ignored -> results));
	}

	public CompletableFuture<NpcCombatInfo> search(String query, int ttlDays)
	{
		if (query == null || query.trim().isEmpty())
		{
			return CompletableFuture.completedFuture(null);
		}

		String normalizedQuery = query.trim();
		long now = System.currentTimeMillis() / 1000L;
		return cache.getBySearchTerm(normalizedQuery, now, ttlDays, true)
			.thenCompose(cached -> WikiCacheLookup.load(cached, info -> !info.isExpired(now, ttlDays),
				() -> searchWiki(normalizedQuery), info -> info.toBuilder().cachedFallback(true).build()));
	}

	private CompletableFuture<NpcCombatInfo> searchWiki(String query)
	{
		return searchPage(query)
			.thenCompose(page ->
			{
				if (page == null)
				{
					return CompletableFuture.completedFuture(null);
				}

				NpcWikiLookup lookup = new NpcWikiLookup(page, null, wikiBase.newBuilder()
					.addPathSegment("w")
					.addPathSegment(page)
					.build()
					.toString());

				return fetchWikitext(lookup)
					.thenApply(wikitext -> parser.parse(-1, query.trim(), lookup, wikitext))
					.thenCompose(info ->
					{
						if (info == null)
						{
							return CompletableFuture.completedFuture(null);
						}

						if (info.getNpcId() < 0)
						{
							return CompletableFuture.completedFuture(info);
						}

						return cache.put(info).thenApply(ignored -> info);
					});
			});
	}

	private CompletableFuture<NpcCombatInfo> fetch(int npcId, String npcName)
	{
		return resolveLookup(npcId, npcName)
			.thenCompose(lookup ->
			{
				if (lookup == null)
				{
					return CompletableFuture.completedFuture(null);
				}
				return fetchWikitext(lookup)
					.thenApply(wikitext -> parser.parse(npcId, npcName, lookup, wikitext))
					.thenCompose(info ->
					{
						if (info == null)
						{
							return CompletableFuture.completedFuture(null);
						}
						return cache.put(info).thenApply(ignored -> info);
					});
			});
	}

	private CompletableFuture<String> searchPage(String query)
	{
		return searchPages(query, 1).thenApply(pages -> pages.isEmpty() ? null : pages.get(0));
	}

	private CompletableFuture<List<String>> searchPages(String query, int limit)
	{
		HttpUrl url = wikiBase.newBuilder()
			.addPathSegment("api.php")
			.addQueryParameter("action", "query")
			.addQueryParameter("format", "json")
			.addQueryParameter("list", "search")
			.addQueryParameter("srnamespace", "0")
			.addQueryParameter("srlimit", Integer.toString(limit))
			.addQueryParameter("srsearch", query)
			.build();

		Request request = new Request.Builder()
			.url(url)
			.header("User-Agent", USER_AGENT)
			.build();

		return execute(httpClient, request).thenApply(response ->
		{
			try (Response closeable = response; ResponseBody body = closeable.body())
			{
				if (body == null)
				{
					throw new IllegalStateException("Wiki search response did not include a body");
				}

				JsonObject json = gson.fromJson(body.string(), JsonObject.class);
				JsonArray search = json.getAsJsonObject("query").getAsJsonArray("search");
				if (search == null || search.size() == 0)
				{
					return Collections.emptyList();
				}
				LinkedHashSet<String> pages = new LinkedHashSet<>();
				for (JsonElement result : search)
				{
					String page = normalizedPageTitle(firstString(result.getAsJsonObject(), "title"));
					if (page != null)
					{
						pages.add(page);
					}
					if (pages.size() >= limit)
					{
						break;
					}
				}
				return new ArrayList<>(pages);
			}
			catch (IOException ex)
			{
				throw new IllegalStateException("Unable to read wiki search response", ex);
			}
		});
	}

	private CompletableFuture<NpcWikiLookup> resolveLookup(int npcId, String npcName)
	{
		String query = "bucket('infobox_monster').select('name','version_anchor').where('id','"
			+ npcId
			+ "').limit(1).run()";
		HttpUrl url = wikiBase.newBuilder()
			.addPathSegment("api.php")
			.addQueryParameter("action", "bucket")
			.addQueryParameter("format", "json")
			.addQueryParameter("query", query)
			.build();

		Request request = new Request.Builder()
			.url(url)
			.header("User-Agent", USER_AGENT)
			.build();

		return execute(httpClient, request).thenApply(response ->
		{
			try (Response closeable = response; ResponseBody body = closeable.body())
			{
				if (body == null)
				{
					throw new IllegalStateException("Wiki bucket response did not include a body");
				}

				JsonObject json = gson.fromJson(body.string(), JsonObject.class);
				JsonArray bucket = json.getAsJsonArray("bucket");
				if (bucket == null || bucket.size() == 0)
				{
					log.debug("Wiki NPC bucket lookup for {} ({}) did not return a row", npcName, npcId);
					return null;
				}

				JsonObject row = bucket.get(0).getAsJsonObject();
				String page = normalizedPageTitle(firstString(row, "name"));
				if (page == null)
				{
					return null;
				}

				String anchor = normalizedAnchor(firstString(row, "version_anchor"));
				return new NpcWikiLookup(page, anchor, wikiUrl(page, anchor));
			}
			catch (IOException ex)
			{
				throw new IllegalStateException("Unable to read wiki bucket response", ex);
			}
		});
	}

	private CompletableFuture<String> fetchWikitext(NpcWikiLookup lookup)
	{
		HttpUrl url = wikiBase.newBuilder()
			.addPathSegment("api.php")
			.addQueryParameter("action", "parse")
			.addQueryParameter("format", "json")
			.addQueryParameter("page", lookup.getPage())
			.addQueryParameter("prop", "wikitext")
			.build();

		Request request = new Request.Builder()
			.url(url)
			.header("User-Agent", USER_AGENT)
			.build();

		return execute(httpClient, request).thenApply(response ->
		{
			try (Response closeable = response; ResponseBody body = closeable.body())
			{
				if (body == null)
				{
					throw new IllegalStateException("Wiki response did not include a body");
				}

				JsonObject json = gson.fromJson(body.string(), JsonObject.class);
				return json.getAsJsonObject("parse")
					.getAsJsonObject("wikitext")
					.get("*")
					.getAsString();
			}
			catch (IOException ex)
			{
				throw new IllegalStateException("Unable to read wiki response", ex);
			}
		});
	}

	private static CompletableFuture<Response> execute(OkHttpClient client, Request request)
	{
		CompletableFuture<Response> future = new CompletableFuture<>();
		client.newCall(request).enqueue(new Callback()
		{
			@Override
			public void onFailure(@Nonnull Call call, @Nonnull IOException e)
			{
				future.completeExceptionally(e);
			}

			@Override
			public void onResponse(@Nonnull Call call, @Nonnull Response response)
			{
				if (!response.isSuccessful() && response.code() / 100 != 3)
				{
					try (Response closeable = response)
					{
						future.completeExceptionally(new IOException("Unexpected wiki response: " + closeable.code()));
					}
					return;
				}

				future.complete(response);
			}
		});
		return future;
	}

	private static String firstString(JsonObject json, String key)
	{
		JsonElement element = json.get(key);
		if (element == null || element.isJsonNull())
		{
			return null;
		}

		if (element.isJsonArray())
		{
			JsonArray array = element.getAsJsonArray();
			if (array.size() == 0 || array.get(0).isJsonNull())
			{
				return null;
			}
			return array.get(0).getAsString();
		}

		return element.getAsString();
	}

	private String wikiUrl(String page, String anchor)
	{
		HttpUrl.Builder builder = wikiBase.newBuilder()
			.addPathSegment("w")
			.addPathSegment(page);
		if (anchor != null)
		{
			builder.fragment(anchor);
		}
		return builder.build().toString();
	}

	private static String normalizedPageTitle(String page)
	{
		if (page == null || page.trim().isEmpty())
		{
			return null;
		}
		return page.trim().replace(' ', '_');
	}

	private static String normalizedAnchor(String anchor)
	{
		if (anchor == null || anchor.trim().isEmpty())
		{
			return null;
		}
		return anchor.trim().replace(' ', '_');
	}
}
