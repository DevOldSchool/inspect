package com.inspect.item;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.inspect.testutil.QueuedResponseInterceptor;
import com.google.gson.Gson;
import com.google.gson.annotations.SerializedName;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class ItemInspectServiceTest
{
	private static final HttpUrl WIKI_BASE = HttpUrl.get("https://wiki.test/");

	@Rule
	public TemporaryFolder temporaryFolder = new TemporaryFolder();

	private ItemInspectService service;

	@After
	public void tearDown()
	{
		if (service != null)
		{
			service.shutDown();
		}
	}

	@Test
	public void invalidInspectInputDoesNotMakeHttpRequest() throws Exception
	{
		QueuedResponseInterceptor responses = new QueuedResponseInterceptor();
		ItemInspectService itemService = service(responses);

		assertNull(itemService.inspect(-1, "Whip", 7).get(5, TimeUnit.SECONDS));
		assertNull(itemService.inspect(4151, null, 7).get(5, TimeUnit.SECONDS));
		assertNull(itemService.inspect(4151, "  ", 7).get(5, TimeUnit.SECONDS));
		assertNull(itemService.search("  ").get(5, TimeUnit.SECONDS));
		assertEquals(0, responses.requestCount());
	}

	@Test
	public void resolvesBucketLookupAndCachesSuccessfulInspect() throws Exception
	{
		QueuedResponseInterceptor responses = new QueuedResponseInterceptor();
		responses.enqueue(200, bucketResponse("item_name", "Abyssal whip", "Variant"));
		responses.enqueue(200, parseResponse("{{Infobox Item\n"
			+ "|name = Abyssal whip\n"
			+ "|id = 4151\n"
			+ "}}\n"
			+ "{{Infobox Bonuses\n"
			+ "|slot = weapon\n"
			+ "|aslash = +82\n"
			+ "}}"));
		ItemInspectService itemService = service(responses);

		ItemInspectInfo first = itemService.inspect(4151, "Abyssal whip", 7).get(5, TimeUnit.SECONDS);
		ItemInspectInfo second = itemService.inspect(4151, "Abyssal whip", 7).get(5, TimeUnit.SECONDS);

		assertEquals("Abyssal_whip", first.getWikiPage());
		assertEquals("Variant", first.getWikiAnchor());
		assertEquals("https://wiki.test/w/Abyssal_whip#Variant", first.getSourceUrl());
		assertEquals("+82", first.getAttackSlash());
		assertEquals(first, second);
		assertEquals(2, responses.requestCount());
		assertEquals("/api.php", responses.requests().get(0).url().encodedPath());
		assertEquals("bucket", responses.requests().get(0).url().queryParameter("action"));
		assertTrue(responses.requests().get(0).url().queryParameter("query").contains("infobox_item"));
		assertTrue(responses.requests().get(0).url().queryParameter("query").contains("4151"));
		assertEquals("/api.php", responses.requests().get(1).url().encodedPath());
		assertEquals("parse", responses.requests().get(1).url().queryParameter("action"));
		assertEquals("Abyssal_whip", responses.requests().get(1).url().queryParameter("page"));
	}

	@Test
	public void usesWikiCategoriesToIdentifyMonsterDropSources() throws Exception
	{
		QueuedResponseInterceptor responses = new QueuedResponseInterceptor();
		responses.enqueue(200, bucketResponse("item_name", "Dragon plateskirt", ""));
		responses.enqueue(200, parseResponse("{{Infobox Item\n"
			+ "|name = Dragon plateskirt\n"
			+ "|id = 4585\n"
			+ "}}\n"
			+ "The dragon plateskirt requires a Defence level of 60 to be worn.\n"
			+ "==Treasure Trails==\n"
			+ "{{EmoteClue|tier=master|item1=Dragon plateskirt}}\n"
			+ "==Item sources==\n"
			+ "{{Drop sources|Dragon plateskirt}}\n",
			"Items_needed_for_an_emote_clue",
			"Items_dropped_by_monster"));
		ItemInspectService itemService = service(responses);

		ItemInspectInfo info = itemService.inspect(4585, "Dragon plateskirt", 7).get(5, TimeUnit.SECONDS);

		assertEquals("Monsters", info.getSourceSummary());
		assertEquals("wikitext|categories", responses.requests().get(1).url().queryParameter("prop"));
		assertEquals(2, responses.requestCount());
	}

	@Test
	public void emptySearchResultReturnsNull() throws Exception
	{
		QueuedResponseInterceptor responses = new QueuedResponseInterceptor();
		responses.enqueue(200, "{\"query\":{\"search\":[]}}");
		ItemInspectService itemService = service(responses);

		assertNull(itemService.search("missing item").get(5, TimeUnit.SECONDS));
		assertEquals(1, responses.requestCount());
		assertEquals("missing item", responses.requests().get(0).url().queryParameter("srsearch"));
	}

	@Test
	public void variantSearchCachesEmptyResult() throws Exception
	{
		QueuedResponseInterceptor responses = new QueuedResponseInterceptor();
		responses.enqueue(200, "{\"query\":{\"search\":[]}}");
		ItemInspectService itemService = service(responses);

		List<ItemInspectVariant> first = itemService.searchVariants("missing item", 7).get(5, TimeUnit.SECONDS);
		List<ItemInspectVariant> second = itemService.searchVariants("missing item", 7).get(5, TimeUnit.SECONDS);

		assertTrue(first.isEmpty());
		assertEquals(first, second);
		assertEquals(1, responses.requestCount());
	}

	@Test
	public void variantSearchReturnsExactIdsAndSelectedVariantSkipsAnotherBucketLookup() throws Exception
	{
		QueuedResponseInterceptor responses = new QueuedResponseInterceptor();
		responses.enqueue(200, searchResponse("Dragon dagger"));
		responses.enqueue(200, "{\"bucket\":["
			+ "{\"page_name\":\"Dragon dagger\",\"item_id\":[\"1215\"],\"item_name\":\"Dragon dagger\",\"version_anchor\":\"Unpoisoned\"},"
			+ "{\"page_name\":\"Dragon dagger\",\"item_id\":[\"5698\"],\"item_name\":\"Dragon dagger(p++)\",\"version_anchor\":\"Poison++\"},"
			+ "{\"page_name\":\"Dragon dagger\",\"item_id\":[\"1231\"],\"item_name\":\"Dragon dagger(p)\",\"version_anchor\":\"Poison\"},"
			+ "{\"page_name\":\"Dragon dagger\",\"item_id\":[\"5680\"],\"item_name\":\"Dragon dagger(p+)\",\"version_anchor\":\"Poison+\"}"
			+ "]}");
		responses.enqueue(200, parseResponse("{{Infobox Item\n"
			+ "|version1 = Unpoisoned\n"
			+ "|version2 = Poison\n"
			+ "|version3 = Poison+\n"
			+ "|version4 = Poison++\n"
			+ "|name1 = Dragon dagger\n"
			+ "|name2 = Dragon dagger(p)\n"
			+ "|name3 = Dragon dagger(p+)\n"
			+ "|name4 = Dragon dagger(p++)\n"
			+ "|id1 = 1215\n"
			+ "|id2 = 1231\n"
			+ "|id3 = 5680\n"
			+ "|id4 = 5698\n"
			+ "}}\n"));
		ItemInspectService itemService = service(responses);

		List<ItemInspectVariant> variants = itemService.searchVariants("Dragon dagger", 7).get(5, TimeUnit.SECONDS);
		List<ItemInspectVariant> cachedVariants = itemService.searchVariants("Dragon dagger", 7).get(5, TimeUnit.SECONDS);
		ItemInspectVariant selected = variants.get(3);
		ItemInspectInfo info = itemService.inspect(selected, 7).get(5, TimeUnit.SECONDS);

		assertEquals(4, variants.size());
		assertEquals(variants, cachedVariants);
		assertEquals(1215, variants.get(0).getId());
		assertEquals("Dragon dagger(p++)", selected.getDisplayName());
		assertEquals(5698, selected.getId());
		assertEquals("Poison++", selected.getWikiAnchor());
		assertEquals(5698, info.getItemId());
		assertEquals("Dragon dagger(p++)", info.getDisplayName());
		assertEquals(3, responses.requestCount());
		assertEquals("query", responses.requests().get(0).url().queryParameter("action"));
		assertEquals("bucket", responses.requests().get(1).url().queryParameter("action"));
		assertTrue(responses.requests().get(1).url().queryParameter("query")
			.contains(".where('page_name','Dragon dagger')"));
		assertEquals("parse", responses.requests().get(2).url().queryParameter("action"));
		assertEquals("Dragon_dagger", responses.requests().get(2).url().queryParameter("page"));
	}

	@Test
	public void searchUsesCachedItemInfoForRepeatedQuery() throws Exception
	{
		QueuedResponseInterceptor responses = new QueuedResponseInterceptor();
		responses.enqueue(200, searchResponse("Abyssal whip"));
		responses.enqueue(200, parseResponse("{{Infobox Item\n"
			+ "|name = Abyssal whip\n"
			+ "|id = 4151\n"
			+ "}}\n"
			+ "{{Infobox Bonuses\n"
			+ "|slot = weapon\n"
			+ "|aslash = +82\n"
			+ "}}"));
		ItemInspectService itemService = service(responses);

		ItemInspectInfo first = itemService.search("Abyssal whip", 7).get(5, TimeUnit.SECONDS);
		ItemInspectInfo second = itemService.search("Abyssal whip", 7).get(5, TimeUnit.SECONDS);

		assertEquals("Abyssal whip", first.getDisplayName());
		assertEquals(first, second);
		assertEquals(2, responses.requestCount());
		assertEquals("query", responses.requests().get(0).url().queryParameter("action"));
		assertEquals("parse", responses.requests().get(1).url().queryParameter("action"));
	}

	@Test
	public void exactVariantSearchSelectsMatchingInfoboxVersion() throws Exception
	{
		QueuedResponseInterceptor responses = new QueuedResponseInterceptor();
		responses.enqueue(200, searchResponse("Dragon dagger"));
		responses.enqueue(200, parseResponse("{{Infobox Item\n"
			+ "|version1 = Unpoisoned\n"
			+ "|version2 = Poison\n"
			+ "|version3 = Poison+\n"
			+ "|version4 = Poison++\n"
			+ "|name1 = Dragon dagger\n"
			+ "|name2 = Dragon dagger(p)\n"
			+ "|name3 = Dragon dagger(p+)\n"
			+ "|name4 = Dragon dagger(p++)\n"
			+ "|id1 = 1215\n"
			+ "|id2 = 1231\n"
			+ "|id3 = 5680\n"
			+ "|id4 = 5698\n"
			+ "}}\n"));
		ItemInspectService itemService = service(responses);

		ItemInspectInfo info = itemService.search("Dragon dagger(p++)", 7).get(5, TimeUnit.SECONDS);

		assertEquals(5698, info.getItemId());
		assertEquals("Dragon dagger(p++)", info.getDisplayName());
		assertEquals("Poison++", info.getWikiAnchor());
		assertEquals("https://wiki.test/w/Dragon%20dagger#Poison%2B%2B", info.getSourceUrl());
		assertEquals("Dragon dagger(p++)", responses.requests().get(0).url().queryParameter("srsearch"));
		assertEquals(2, responses.requestCount());
	}

	@Test
	public void searchUsesPersistedCachedItemInfoByName() throws Exception
	{
		Path cacheDirectory = temporaryFolder.newFolder().toPath();
		QueuedResponseInterceptor firstResponses = new QueuedResponseInterceptor();
		firstResponses.enqueue(200, searchResponse("Abyssal whip"));
		firstResponses.enqueue(200, parseResponse("{{Infobox Item\n"
			+ "|name = Abyssal whip\n"
			+ "|id = 4151\n"
			+ "}}\n"
			+ "{{Infobox Bonuses\n"
			+ "|slot = weapon\n"
			+ "|aslash = +82\n"
			+ "}}"));
		ItemInspectService firstService = service(firstResponses, cacheDirectory);
		ItemInspectInfo first = firstService.search("Abyssal whip", 7).get(5, TimeUnit.SECONDS);
		firstService.shutDown();
		service = null;

		QueuedResponseInterceptor secondResponses = new QueuedResponseInterceptor();
		ItemInspectService secondService = service(secondResponses, cacheDirectory);
		ItemInspectInfo second = secondService.search("Abyssal_whip", 7).get(5, TimeUnit.SECONDS);

		assertEquals(first, second);
		assertEquals(0, secondResponses.requestCount());
	}

	@Test
	public void equipmentStatsUseBucketBatchLookup() throws Exception
	{
		QueuedResponseInterceptor responses = new QueuedResponseInterceptor();
		responses.enqueue(200, "{\"bucket\":[{"
			+ "\"page_name\":\"Abyssal whip\","
			+ "\"page_name_sub\":\"Abyssal whip\","
			+ "\"item_name\":\"Abyssal whip\","
			+ "\"item_id\":[\"4151\"],"
			+ "\"infobox_bonuses.page_name_sub\":\"Abyssal whip\","
			+ "\"infobox_bonuses.equipment_slot\":\"weapon\","
			+ "\"infobox_bonuses.stab_attack_bonus\":0,"
			+ "\"infobox_bonuses.slash_attack_bonus\":82,"
			+ "\"infobox_bonuses.strength_bonus\":82,"
			+ "\"infobox_bonuses.prayer_bonus\":0"
			+ "}]}");
		ItemInspectService itemService = service(responses);

		Map<Integer, ItemInspectInfo> stats = itemService.inspectEquipmentStats(Arrays.asList(4151, 1127)).get(5, TimeUnit.SECONDS);

		assertEquals(1, responses.requestCount());
		assertEquals("bucket", responses.requests().get(0).url().queryParameter("action"));
		assertTrue(responses.requests().get(0).url().queryParameter("query").contains("infobox_bonuses"));
		assertEquals("Abyssal whip", stats.get(4151).getDisplayName());
		assertEquals("weapon", stats.get(4151).getSlot());
		assertEquals("82", stats.get(4151).getAttackSlash());
		assertEquals("82", stats.get(4151).getStrength());
	}

	@Test
	public void httpAndTransportFailuresCompleteExceptionally() throws Exception
	{
		QueuedResponseInterceptor responses = new QueuedResponseInterceptor();
		responses.enqueue(500, "server error");
		ItemInspectService itemService = service(responses);
		ItemInspectService httpFailureService = itemService;

		assertFailure(() -> httpFailureService.search("whip").get(5, TimeUnit.SECONDS), "Unexpected wiki response: 500");

		service.shutDown();
		service = null;
		QueuedResponseInterceptor transportFailure = new QueuedResponseInterceptor();
		transportFailure.enqueueFailure(new IOException("offline"));
		itemService = service(transportFailure);
		ItemInspectService finalItemService = itemService;
		assertFailure(() -> finalItemService.search("whip").get(5, TimeUnit.SECONDS), "offline");
	}

	private ItemInspectService service(QueuedResponseInterceptor responses) throws IOException
	{
		return service(responses, temporaryFolder.newFolder().toPath());
	}

	private ItemInspectService service(QueuedResponseInterceptor responses, Path cacheDirectory)
	{
		service = new ItemInspectService(
			new OkHttpClient.Builder().addInterceptor(responses).build(),
			new Gson(),
			WIKI_BASE,
			cacheDirectory);
		return service;
	}

	private static String searchResponse(String title)
	{
		return "{\"query\":{\"search\":[{\"title\":\"" + title + "\"}]}}";
	}

	private static String parseResponse(String wikitext, String... categories)
	{
		return new Gson().toJson(new ParseResponse(wikitext, categories));
	}

	private static String bucketResponse(String nameField, String name, String anchor)
	{
		return "{\"bucket\":[{\""
			+ nameField
			+ "\":\""
			+ name
			+ "\",\"version_anchor\":\""
			+ anchor
			+ "\"}]}";
	}

	private static void assertFailure(CheckedAction action, String expectedMessage) throws Exception
	{
		try
		{
			action.run();
			fail("Expected lookup to fail");
		}
		catch (ExecutionException ex)
		{
			assertTrue(ex.getCause() instanceof IOException);
			assertTrue(ex.getCause().getMessage().contains(expectedMessage));
		}
	}

	private interface CheckedAction
	{
		void run() throws Exception;
	}

	private static final class ParseResponse
	{
		private final Parse parse;

		private ParseResponse(String wikitext, String[] categories)
		{
			this.parse = new Parse(wikitext, categories);
		}
	}

	private static final class Parse
	{
		private final Wikitext wikitext;
		private final Category[] categories;

		private Parse(String value, String[] categoryNames)
		{
			this.wikitext = new Wikitext(value);
			this.categories = new Category[categoryNames.length];
			for (int i = 0; i < categoryNames.length; i++)
			{
				this.categories[i] = new Category(categoryNames[i]);
			}
		}
	}

	private static final class Category
	{
		@SerializedName("*")
		private final String value;

		private Category(String value)
		{
			this.value = value;
		}
	}

	private static final class Wikitext
	{
		@SerializedName("*")
		private final String value;

		private Wikitext(String value)
		{
			this.value = value;
		}
	}
}
