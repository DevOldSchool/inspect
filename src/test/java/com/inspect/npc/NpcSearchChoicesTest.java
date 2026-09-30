package com.inspect.npc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertFalse;

import com.google.gson.Gson;
import com.inspect.testutil.QueuedResponseInterceptor;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class NpcSearchChoicesTest
{
	private static final String GUARDS = "{{Infobox Monster\n|name = Guard\n|id = 10\n|combat = 20\n}}\n"
		+ "{{Infobox Monster\n|name = Guard\n|version1 = Varrock\n|version2 = Falador\n"
		+ "|id1 = 11,hist12\n|id2 = 12\n|combat1 = 21\n|combat2 = 22\n}}";
	private final Gson gson = new Gson();
	@Rule
	public TemporaryFolder folder = new TemporaryFolder();
	private NpcInspectService service;

	@After
	public void close()
	{
		if (service != null)
		{
			service.shutDown();
		}
	}

	@Test
	public void enumeratesSharedPageAndRefreshesLaterInfoboxExactly()
	{
		NpcInspectParser parser = new NpcInspectParser();
		NpcWikiLookup page = new NpcWikiLookup("Guard", null, "https://wiki.test/w/Guard");
		List<NpcCombatInfo> choices = parser.parseChoices(page, GUARDS);
		assertEquals(3, choices.size());
		NpcCombatInfo selected = choices.get(2);
		assertEquals(12, selected.getNpcId());
		assertEquals("Falador", selected.getWikiAnchor());
		assertEquals("22", selected.getCombatLevel());
		assertEquals("https://wiki.test/w/Guard#Falador", selected.getSourceUrl());
		NpcCombatInfo refreshed = parser.parse(selected.getNpcId(), "Guard",
			new NpcWikiLookup("Guard", "Falador", selected.getSourceUrl()), GUARDS);
		assertEquals("22", refreshed.getCombatLevel());
	}

	@Test
	public void prefersSelectedAnchorWhenVersionsShareAnId()
	{
		NpcCombatInfo info = new NpcInspectParser().parse(11, "Guard",
			new NpcWikiLookup("Guard", "Falador", "https://wiki.test/w/Guard#Falador"),
			GUARDS.replace("|id2 = 12", "|id2 = 11"));
		assertEquals("22", info.getCombatLevel());
	}

	@Test
	public void excludesHistoricalOnlyVariantsAndNonMonsterPages()
	{
		NpcInspectParser parser = new NpcInspectParser();
		NpcWikiLookup page = new NpcWikiLookup("Guard", null, "https://wiki.test/w/Guard");
		assertTrue(parser.parseChoices(page, "A disambiguation page").isEmpty());
		assertEquals(2, parser.parseChoices(page, GUARDS.replace("|id2 = 12", "|id2 = hist12")).size());
	}

	@Test
	public void searchesSeveralPagesDeduplicatesAndPersistsAllChoices() throws Exception
	{
		Path directory = folder.newFolder().toPath();
		AtomicInteger requests = new AtomicInteger();
		OkHttpClient http = new OkHttpClient.Builder().addInterceptor(chain ->
		{
			requests.incrementAndGet();
			String page = chain.request().url().queryParameter("page");
			String body;
			if (page == null)
			{
				assertEquals("5", chain.request().url().queryParameter("srlimit"));
				body = "{\"query\":{\"search\":[{\"title\":\"Guard\"},{\"title\":\"Guard\"},"
					+ "{\"title\":\"Rat\"},{\"title\":\"Guide\"}]}}";
			}
			else
			{
				body = parse("Guard".equals(page) ? GUARDS : "Rat".equals(page)
					? "{{Infobox Monster\n|name = Rat\n|id = 20\n|combat = 1\n}}" : "A guide");
			}
			return response(chain.request(), 200, body);
		}).build();
		service = service(http, directory);
		NpcSearchResults first = service.searchChoices("guard", 7).get(5, TimeUnit.SECONDS);
		assertEquals(4, first.getChoices().size());
		assertEquals("Rat", first.getChoices().get(3).getDisplayName());
		assertEquals(4, requests.get());
		assertEquals(first, service.searchChoices("guard", 7).get(5, TimeUnit.SECONDS));
		assertEquals(4, requests.get());
		service.shutDown();
		service = service(http, directory);
		assertEquals(first, service.searchChoices("GUARD", 7).get(5, TimeUnit.SECONDS));
		assertEquals(4, requests.get());
	}

	@Test
	public void partialResultsRemainVisibleAndAreRetried() throws Exception
	{
		AtomicBoolean failRat = new AtomicBoolean(true);
		AtomicInteger requests = new AtomicInteger();
		OkHttpClient http = new OkHttpClient.Builder().addInterceptor(chain ->
		{
			requests.incrementAndGet();
			String page = chain.request().url().queryParameter("page");
			if (page == null)
			{
				return response(chain.request(), 200, "{\"query\":{\"search\":[{\"title\":\"Guard\"},{\"title\":\"Rat\"}]}}");
			}
			if ("Rat".equals(page) && failRat.get())
			{
				return response(chain.request(), 503, "Unavailable");
			}
			return response(chain.request(), 200, parse(GUARDS));
		}).build();
		service = service(http, folder.newFolder().toPath());
		NpcSearchResults first = service.searchChoices("guard", 7).get(5, TimeUnit.SECONDS);
		assertTrue(first.isPartial());
		assertEquals(3, first.getChoices().size());
		failRat.set(false);
		assertFalse(service.searchChoices("guard", 7).get(5, TimeUnit.SECONDS).isPartial());
		assertEquals(6, requests.get());
	}

	@Test
	public void expiredSearchFallsBackAndSelectedChoiceKeepsItsTimestampOffline() throws Exception
	{
		Path directory = folder.newFolder().toPath();
		NpcCombatInfo saved = NpcCombatInfo.builder().npcId(12).wikiPage("Guard").wikiAnchor("Falador")
			.displayName("Guard").fetchedAtEpochSecond(1000L).sourceUrl("https://wiki.test/w/Guard#Falador").build();
		NpcInspectCache cache = new NpcInspectCache(gson, directory);
		cache.putSearchResults(new NpcSearchResults("guard", Collections.singletonList(saved), 1000L, false)).get(5, TimeUnit.SECONDS);
		cache.shutDown();
		QueuedResponseInterceptor responses = new QueuedResponseInterceptor();
		responses.enqueueFailure(new IOException("offline"));
		responses.enqueueFailure(new IOException("offline"));
		service = service(new OkHttpClient.Builder().addInterceptor(responses).build(), directory);
		NpcSearchResults results = service.searchChoices("guard", 7).get(5, TimeUnit.SECONDS);
		assertTrue(results.getChoices().get(0).isCachedFallback());
		NpcCombatInfo selected = service.inspectChoice(results.getChoices().get(0), 7).get(5, TimeUnit.SECONDS);
		assertTrue(selected.isCachedFallback());
		assertEquals(1000L, selected.getFetchedAtEpochSecond());
		assertEquals("Falador", selected.getWikiAnchor());
	}

	@Test
	public void selectionDoesNotOverwriteMoreRecentlyRefreshedData() throws Exception
	{
		Path directory = folder.newFolder().toPath();
		long now = System.currentTimeMillis() / 1000L;
		NpcCombatInfo recent = NpcCombatInfo.builder().npcId(12).wikiPage("Guard").wikiAnchor("Falador")
			.displayName("Guard").combatLevel("22").fetchedAtEpochSecond(now)
			.sourceUrl("https://wiki.test/w/Guard#Falador").build();
		NpcInspectCache cache = new NpcInspectCache(gson, directory);
		cache.put(recent).get(5, TimeUnit.SECONDS);
		cache.shutDown();
		QueuedResponseInterceptor responses = new QueuedResponseInterceptor();
		service = service(new OkHttpClient.Builder().addInterceptor(responses).build(), directory);
		NpcCombatInfo olderChoice = recent.toBuilder().combatLevel("20").fetchedAtEpochSecond(now - 100L).build();
		assertEquals(recent, service.inspectChoice(olderChoice, 7).get(5, TimeUnit.SECONDS));
		assertEquals(recent, service.inspect(12, "Guard", 7).get(5, TimeUnit.SECONDS));
		assertEquals(0, responses.requestCount());
	}

	@Test
	public void emptySearchDoesNotFetchAnyPageAndInvalidInputMakesNoRequest() throws Exception
	{
		QueuedResponseInterceptor responses = new QueuedResponseInterceptor();
		responses.enqueue(200, "{\"query\":{\"search\":[]}}");
		service = service(new OkHttpClient.Builder().addInterceptor(responses).build(), folder.newFolder().toPath());
		assertTrue(service.searchChoices(null, 7).get(5, TimeUnit.SECONDS).getChoices().isEmpty());
		assertEquals(0, responses.requestCount());
		assertTrue(service.searchChoices("nothing", 7).get(5, TimeUnit.SECONDS).getChoices().isEmpty());
		assertEquals(1, responses.requestCount());
	}

	private NpcInspectService service(OkHttpClient http, Path directory)
	{
		return new NpcInspectService(http, gson, HttpUrl.get("https://wiki.test/"), directory);
	}

	private String parse(String text)
	{
		return "{\"parse\":{\"wikitext\":{\"*\":" + gson.toJson(text) + "}}}";
	}

	private static Response response(okhttp3.Request request, int code, String body)
	{
		return new Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code).message("Test")
			.body(ResponseBody.create(okhttp3.MediaType.parse("application/json"), body)).build();
	}
}
