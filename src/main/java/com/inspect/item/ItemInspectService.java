package com.inspect.item;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import javax.annotation.Nonnull;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
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
public class ItemInspectService
{
	private static final HttpUrl DEFAULT_WIKI_BASE = HttpUrl.get("https://oldschool.runescape.wiki");
	private static final String USER_AGENT = "Inspect RuneLite plugin (https://github.com/DevOldSchool/inspect)";
	private static final int EQUIPMENT_STATS_BATCH_SIZE = 50;

	private final OkHttpClient httpClient;
	private final Gson gson;
	private final HttpUrl wikiBase;
	private final ItemInspectParser parser = new ItemInspectParser();
	private final ItemInspectCache cache;

	@Inject
	ItemInspectService(OkHttpClient httpClient, Gson gson)
	{
		this(httpClient, gson, DEFAULT_WIKI_BASE,
			RuneLite.RUNELITE_DIR.toPath().resolve("inspect").resolve("item-inspect"));
	}

	ItemInspectService(OkHttpClient httpClient, Gson gson, HttpUrl wikiBase, Path cacheDirectory)
	{
		this.httpClient = httpClient;
		this.gson = gson;
		this.wikiBase = wikiBase;
		this.cache = new ItemInspectCache(gson, cacheDirectory);
	}

	public void startUp(boolean clearCache)
	{
		cache.startUp(clearCache);
	}

	public void shutDown()
	{
		cache.shutDown();
	}

	public CompletableFuture<Void> clearCacheAsync()
	{
		return cache.clearAsync();
	}

	public CompletableFuture<ItemInspectInfo> inspect(int itemId, String itemName, int ttlDays)
	{
		if (itemId < 0 || itemName == null || itemName.trim().isEmpty())
		{
			return CompletableFuture.completedFuture(null);
		}

		long now = System.currentTimeMillis() / 1000L;
		return cache.get(itemId, now, ttlDays)
			.thenCompose(cached -> cached.map(CompletableFuture::completedFuture).orElseGet(() -> fetch(itemId, itemName)));
	}

	public CompletableFuture<ItemInspectInfo> inspect(ItemInspectVariant variant, int ttlDays)
	{
		if (variant == null || variant.getId() < 0 || variant.getWikiPage() == null
			|| variant.getWikiPage().trim().isEmpty())
		{
			return CompletableFuture.completedFuture(null);
		}

		long now = System.currentTimeMillis() / 1000L;
		return cache.get(variant.getId(), now, ttlDays)
			.thenCompose(cached -> cached.map(CompletableFuture::completedFuture).orElseGet(() -> fetch(variant)));
	}

	public CompletableFuture<List<ItemInspectVariant>> searchVariants(String query)
	{
		return searchVariants(query, 0);
	}

	public CompletableFuture<List<ItemInspectVariant>> searchVariants(String query, int ttlDays)
	{
		if (query == null || query.trim().isEmpty())
		{
			return CompletableFuture.completedFuture(Collections.emptyList());
		}

		String normalizedQuery = query.trim();
		long now = System.currentTimeMillis() / 1000L;
		return cache.getVariants(normalizedQuery, now, ttlDays)
			.thenCompose(cached -> cached
				.map(CompletableFuture::completedFuture)
				.orElseGet(() -> searchVariantsWiki(normalizedQuery)));
	}

	private CompletableFuture<List<ItemInspectVariant>> searchVariantsWiki(String query)
	{
		return searchPage(query)
			.thenCompose(page -> page == null
				? CompletableFuture.completedFuture(Collections.emptyList())
				: findVariants(page))
			.thenCompose(variants -> cache.putVariants(
				query,
				variants,
				System.currentTimeMillis() / 1000L).thenApply(ignored -> variants));
	}

	public CompletableFuture<ItemInspectInfo> search(String query)
	{
		return search(query, 0);
	}

	public CompletableFuture<ItemInspectInfo> search(String query, int ttlDays)
	{
		if (query == null || query.trim().isEmpty())
		{
			return CompletableFuture.completedFuture(null);
		}

		String normalizedQuery = query.trim();
		long now = System.currentTimeMillis() / 1000L;
		return cache.getBySearchTerm(normalizedQuery, now, ttlDays)
			.thenCompose(cached -> cached.map(CompletableFuture::completedFuture).orElseGet(() -> searchWiki(normalizedQuery)));
	}

	private CompletableFuture<ItemInspectInfo> searchWiki(String query)
	{
		return searchPage(query)
			.thenCompose(page ->
			{
				if (page == null)
				{
					return CompletableFuture.completedFuture(null);
				}

				ItemWikiLookup lookup = new ItemWikiLookup(page, null, wikiBase.newBuilder()
					.addPathSegment("w")
					.addPathSegment(page)
					.build()
					.toString());

				return fetchWikiPage(lookup)
					.thenApply(wikiPage -> parser.parse(-1, query, lookup, wikiPage.wikitext, wikiPage.categories))
					.thenCompose(info ->
					{
						if (info == null)
						{
							return CompletableFuture.completedFuture(null);
						}

						if (info.getItemId() < 0)
						{
							return CompletableFuture.completedFuture(info);
						}

						return cache.put(info).thenApply(ignored -> info);
					});
				});
	}

	public CompletableFuture<Map<Integer, ItemInspectInfo>> inspectEquipmentStats(Collection<Integer> itemIds)
	{
		if (itemIds == null || itemIds.isEmpty())
		{
			return CompletableFuture.completedFuture(Collections.emptyMap());
		}

		List<Integer> uniqueItemIds = new ArrayList<>(new LinkedHashSet<>(itemIds));
		Map<Integer, ItemInspectInfo> items = new LinkedHashMap<>();
		CompletableFuture<Void> lookupChain = CompletableFuture.completedFuture(null);
		for (int start = 0; start < uniqueItemIds.size(); start += EQUIPMENT_STATS_BATCH_SIZE)
		{
			List<Integer> batch = uniqueItemIds.subList(start, Math.min(start + EQUIPMENT_STATS_BATCH_SIZE, uniqueItemIds.size()));
			lookupChain = lookupChain.thenCompose(ignored -> fetchEquipmentStatsBatch(batch)
				.thenAccept(items::putAll));
		}
		return lookupChain.thenApply(ignored -> items);
	}

	private CompletableFuture<ItemInspectInfo> fetch(int itemId, String itemName)
	{
		return resolveLookup(itemId, itemName)
			.thenCompose(lookup -> fetchResolved(itemId, itemName, lookup));
	}

	private CompletableFuture<ItemInspectInfo> fetch(ItemInspectVariant variant)
	{
		ItemWikiLookup lookup = new ItemWikiLookup(
			variant.getWikiPage(),
			variant.getWikiAnchor(),
			variant.getSourceUrl());
		return fetchResolved(variant.getId(), variant.getDisplayName(), lookup);
	}

	private CompletableFuture<ItemInspectInfo> fetchResolved(int itemId, String itemName, ItemWikiLookup lookup)
	{
		if (lookup == null)
		{
			return CompletableFuture.completedFuture(null);
		}

		return fetchWikiPage(lookup)
			.thenApply(wikiPage -> parser.parse(itemId, itemName, lookup, wikiPage.wikitext, wikiPage.categories))
			.thenCompose(info ->
			{
				if (info == null)
				{
					return CompletableFuture.completedFuture(null);
				}
				return cache.put(info).thenApply(ignored -> info);
			});
	}

	private CompletableFuture<String> searchPage(String query)
	{
		HttpUrl url = wikiBase.newBuilder()
			.addPathSegment("api.php")
			.addQueryParameter("action", "query")
			.addQueryParameter("format", "json")
			.addQueryParameter("list", "search")
			.addQueryParameter("srnamespace", "0")
			.addQueryParameter("srlimit", "1")
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
					return null;
				}
				return search.get(0).getAsJsonObject().get("title").getAsString();
			}
			catch (IOException ex)
			{
				throw new IllegalStateException("Unable to read wiki search response", ex);
			}
		});
	}

	private CompletableFuture<ItemWikiLookup> resolveLookup(int itemId, String itemName)
	{
		String query = "bucket('infobox_item').select('item_name','version_anchor').where('item_id','"
			+ itemId
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
					log.debug("Wiki item bucket lookup for {} ({}) did not return a row", itemName, itemId);
					return null;
				}

				JsonObject row = bucket.get(0).getAsJsonObject();
				String page = normalizedPageTitle(firstString(row, "item_name"));
				if (page == null)
				{
					return null;
				}

				String anchor = normalizedAnchor(firstString(row, "version_anchor"));
				return new ItemWikiLookup(page, anchor, wikiUrl(page, anchor));
			}
			catch (IOException ex)
			{
				throw new IllegalStateException("Unable to read wiki bucket response", ex);
			}
		});
	}

	private CompletableFuture<List<ItemInspectVariant>> findVariants(String page)
	{
		String query = "bucket('infobox_item')"
			+ ".select('page_name','item_id','item_name','version_anchor')"
			+ ".where('page_name','"
			+ bucketLiteral(page)
			+ "').limit(50).run()";
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
					throw new IllegalStateException("Wiki item variant response did not include a body");
				}

				JsonObject json = gson.fromJson(body.string(), JsonObject.class);
				JsonArray bucket = json.getAsJsonArray("bucket");
				if (bucket == null || bucket.size() == 0)
				{
					return Collections.emptyList();
				}

				Map<Integer, ItemInspectVariant> variants = new LinkedHashMap<>();
				for (JsonElement element : bucket)
				{
					JsonObject row = element.getAsJsonObject();
					int itemId = firstNumericId(row, "item_id");
					if (itemId < 0)
					{
						continue;
					}

					String variantPage = normalizedPageTitle(firstString(row, "page_name"));
					if (variantPage == null)
					{
						variantPage = normalizedPageTitle(page);
					}
					String anchor = normalizedAnchor(firstString(row, "version_anchor"));
					String displayName = firstString(row, "item_name");
					if (displayName == null || displayName.trim().isEmpty())
					{
						displayName = page;
					}
					variants.putIfAbsent(itemId, new ItemInspectVariant(
						itemId,
						displayName,
						variantPage,
						anchor,
						wikiUrl(variantPage, anchor)));
				}

				List<ItemInspectVariant> sorted = new ArrayList<>(variants.values());
				sorted.sort(Comparator
					.comparing(ItemInspectVariant::getDisplayName, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER))
					.thenComparing(ItemInspectVariant::getWikiAnchor, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER))
					.thenComparingInt(ItemInspectVariant::getId));
				return Collections.unmodifiableList(sorted);
			}
			catch (IOException ex)
			{
				throw new IllegalStateException("Unable to read wiki item variant response", ex);
			}
		});
	}

	private CompletableFuture<Map<Integer, ItemInspectInfo>> fetchEquipmentStatsBatch(Collection<Integer> itemIds)
	{
		String query = "bucket('infobox_item')"
			+ ".join('infobox_bonuses','infobox_bonuses.page_name','page_name')"
			+ ".select('page_name','page_name_sub','item_id','item_name',"
			+ "'infobox_bonuses.page_name_sub','infobox_bonuses.equipment_slot',"
			+ "'infobox_bonuses.stab_attack_bonus','infobox_bonuses.slash_attack_bonus','infobox_bonuses.crush_attack_bonus',"
			+ "'infobox_bonuses.magic_attack_bonus','infobox_bonuses.range_attack_bonus',"
			+ "'infobox_bonuses.stab_defence_bonus','infobox_bonuses.slash_defence_bonus','infobox_bonuses.crush_defence_bonus',"
			+ "'infobox_bonuses.magic_defence_bonus','infobox_bonuses.range_defence_bonus',"
			+ "'infobox_bonuses.strength_bonus','infobox_bonuses.ranged_strength_bonus','infobox_bonuses.magic_damage_bonus',"
			+ "'infobox_bonuses.prayer_bonus','infobox_bonuses.weapon_attack_speed','infobox_bonuses.weapon_attack_range')"
			+ ".where("
			+ bucketOrConditions("item_id", itemIds)
			+ ").limit(5000).run()";
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

		Set<Integer> requestedItemIds = new LinkedHashSet<>(itemIds);
		return execute(httpClient, request).thenApply(response ->
		{
			try (Response closeable = response; ResponseBody body = closeable.body())
			{
				if (body == null)
				{
					throw new IllegalStateException("Wiki equipment bucket response did not include a body");
				}

				JsonObject json = gson.fromJson(body.string(), JsonObject.class);
				JsonArray bucket = json.getAsJsonArray("bucket");
				if (bucket == null || bucket.size() == 0)
				{
					return Collections.emptyMap();
				}

				Map<Integer, ItemInspectInfo> items = new LinkedHashMap<>();
				for (JsonElement element : bucket)
				{
					JsonObject row = element.getAsJsonObject();
					String itemSubpage = firstString(row, "page_name_sub");
					String bonusSubpage = firstString(row, "infobox_bonuses.page_name_sub");
					if (itemSubpage != null && bonusSubpage != null && !itemSubpage.equals(bonusSubpage))
					{
						continue;
					}

					for (int itemId : requestedItemIds(row, requestedItemIds))
					{
						items.put(itemId, equipmentInfo(row, itemId));
					}
				}
				return items;
			}
			catch (IOException ex)
			{
				throw new IllegalStateException("Unable to read wiki equipment bucket response", ex);
			}
		});
	}

	private CompletableFuture<WikiPage> fetchWikiPage(ItemWikiLookup lookup)
	{
		HttpUrl url = wikiBase.newBuilder()
			.addPathSegment("api.php")
			.addQueryParameter("action", "parse")
			.addQueryParameter("format", "json")
			.addQueryParameter("page", lookup.getPage())
			.addQueryParameter("prop", "wikitext|categories")
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
				JsonObject parse = json.getAsJsonObject("parse");
				String wikitext = parse.getAsJsonObject("wikitext").get("*").getAsString();
				List<String> categories = new ArrayList<>();
				JsonArray categoryEntries = parse.getAsJsonArray("categories");
				if (categoryEntries != null)
				{
					for (JsonElement categoryEntry : categoryEntries)
					{
						JsonElement category = categoryEntry.getAsJsonObject().get("*");
						if (category != null && !category.isJsonNull())
						{
							categories.add(category.getAsString());
						}
					}
				}
				return new WikiPage(wikitext, categories);
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

	private static String bucketOrConditions(String field, Collection<Integer> itemIds)
	{
		StringBuilder query = new StringBuilder("bucket.Or(");
		boolean first = true;
		for (int itemId : itemIds)
		{
			if (!first)
			{
				query.append(',');
			}
			query.append("{'")
				.append(field)
				.append("','")
				.append(itemId)
				.append("'}");
			first = false;
		}
		return query.append(')').toString();
	}

	private static String bucketLiteral(String value)
	{
		return value.replace("\\", "\\\\").replace("'", "\\'");
	}

	private static int firstNumericId(JsonObject row, String key)
	{
		JsonElement element = row.get(key);
		if (element == null || element.isJsonNull())
		{
			return -1;
		}

		if (element.isJsonArray())
		{
			for (JsonElement candidate : element.getAsJsonArray())
			{
				int id = numericId(candidate);
				if (id >= 0)
				{
					return id;
				}
			}
			return -1;
		}

		return numericId(element);
	}

	private static int numericId(JsonElement element)
	{
		if (element == null || element.isJsonNull())
		{
			return -1;
		}

		String value = element.getAsString();
		if (!value.matches("\\d+"))
		{
			return -1;
		}

		try
		{
			return Integer.parseInt(value);
		}
		catch (NumberFormatException ignored)
		{
			return -1;
		}
	}

	private static Set<Integer> requestedItemIds(JsonObject row, Set<Integer> requestedItemIds)
	{
		Set<Integer> itemIds = new LinkedHashSet<>();
		JsonElement itemIdElement = row.get("item_id");
		if (itemIdElement == null || itemIdElement.isJsonNull())
		{
			return itemIds;
		}

		if (itemIdElement.isJsonArray())
		{
			for (JsonElement element : itemIdElement.getAsJsonArray())
			{
				addRequestedItemId(itemIds, requestedItemIds, element);
			}
			return itemIds;
		}

		addRequestedItemId(itemIds, requestedItemIds, itemIdElement);
		return itemIds;
	}

	private static void addRequestedItemId(Set<Integer> itemIds, Set<Integer> requestedItemIds, JsonElement element)
	{
		try
		{
			int itemId = Integer.parseInt(element.getAsString());
			if (requestedItemIds.contains(itemId))
			{
				itemIds.add(itemId);
			}
		}
		catch (NumberFormatException ignored)
		{
			// Ignore malformed wiki bucket rows.
		}
	}

	private ItemInspectInfo equipmentInfo(JsonObject row, int itemId)
	{
		String page = normalizedPageTitle(firstString(row, "page_name"));
		String anchor = normalizedAnchor(firstString(row, "page_name_sub"));
		return ItemInspectInfo.builder()
			.itemId(itemId)
			.wikiPage(page)
			.wikiAnchor(anchor)
			.displayName(firstString(row, "item_name"))
			.attackStab(stringValue(row, "infobox_bonuses.stab_attack_bonus"))
			.attackSlash(stringValue(row, "infobox_bonuses.slash_attack_bonus"))
			.attackCrush(stringValue(row, "infobox_bonuses.crush_attack_bonus"))
			.attackMagic(stringValue(row, "infobox_bonuses.magic_attack_bonus"))
			.attackRanged(stringValue(row, "infobox_bonuses.range_attack_bonus"))
			.defenceStab(stringValue(row, "infobox_bonuses.stab_defence_bonus"))
			.defenceSlash(stringValue(row, "infobox_bonuses.slash_defence_bonus"))
			.defenceCrush(stringValue(row, "infobox_bonuses.crush_defence_bonus"))
			.defenceMagic(stringValue(row, "infobox_bonuses.magic_defence_bonus"))
			.defenceRanged(stringValue(row, "infobox_bonuses.range_defence_bonus"))
			.strength(stringValue(row, "infobox_bonuses.strength_bonus"))
			.rangedStrength(stringValue(row, "infobox_bonuses.ranged_strength_bonus"))
			.magicDamage(stringValue(row, "infobox_bonuses.magic_damage_bonus"))
			.prayer(stringValue(row, "infobox_bonuses.prayer_bonus"))
			.slot(firstString(row, "infobox_bonuses.equipment_slot"))
			.attackSpeed(stringValue(row, "infobox_bonuses.weapon_attack_speed"))
			.attackRange(stringValue(row, "infobox_bonuses.weapon_attack_range"))
			.fetchedAtEpochSecond(Instant.now().getEpochSecond())
			.sourceUrl(page == null ? null : wikiUrl(page, anchor))
			.build();
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

	private static String stringValue(JsonObject json, String key)
	{
		JsonElement element = json.get(key);
		if (element == null || element.isJsonNull())
		{
			return null;
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

	private static final class WikiPage
	{
		private final String wikitext;
		private final List<String> categories;

		private WikiPage(String wikitext, List<String> categories)
		{
			this.wikitext = wikitext;
			this.categories = categories;
		}
	}
}
