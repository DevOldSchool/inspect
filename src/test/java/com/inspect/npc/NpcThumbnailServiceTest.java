package com.inspect.npc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.google.gson.Gson;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import javax.imageio.ImageIO;
import okhttp3.HttpUrl;
import okhttp3.Interceptor;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.junit.After;
import org.junit.Test;

public class NpcThumbnailServiceTest
{
	private NpcThumbnailService service;

	@After
	public void tearDown()
	{
		if (service != null)
		{
			service.shutDown();
		}
	}

	@Test
	public void loadsAndCachesScaledImage() throws Exception
	{
		AtomicInteger requests = new AtomicInteger();
		ByteArrayOutputStream output = new ByteArrayOutputStream();
		ImageIO.write(new BufferedImage(80, 100, BufferedImage.TYPE_INT_ARGB), "png", output);
		start(chain ->
		{
			requests.incrementAndGet();
			if (chain.request().url().encodedPath().equals("/api.php"))
			{
				assertEquals("File:Guard Edgeville.png", chain.request().url().queryParameter("titles"));
				assertEquals("38", chain.request().url().queryParameter("iiurlwidth"));
				return response(chain, metadata("https://wiki.test/images/Guard.png"));
			}
			return response(chain, output.toByteArray());
		}, new AtomicBoolean(true));
		CompletableFuture<BufferedImage> first = service.getThumbnail("Guard_Edgeville.png");
		assertSame(first, service.getThumbnail("Guard Edgeville.png"));
		BufferedImage image = first.get(5, TimeUnit.SECONDS);
		assertEquals(38, image.getWidth());
		assertEquals(38, image.getHeight());
		assertSame(first, service.getThumbnail("Guard Edgeville.png"));
		assertEquals(2, requests.get());
	}

	@Test
	public void disabledOrMissingImageDoesNotRequestNetwork() throws Exception
	{
		AtomicInteger requests = new AtomicInteger();
		AtomicBoolean enabled = new AtomicBoolean(false);
		start(chain ->
		{
			requests.incrementAndGet();
			return response(chain, "{}".getBytes(StandardCharsets.UTF_8));
		}, enabled);
		assertNull(service.getThumbnail("Guard.png").get(5, TimeUnit.SECONDS));
		enabled.set(true);
		assertNull(service.getThumbnail(null).get(5, TimeUnit.SECONDS));
		assertNull(service.getThumbnail(" ").get(5, TimeUnit.SECONDS));
		service.shutDown();
		assertNull(service.getThumbnail("Guard.png").get(5, TimeUnit.SECONDS));
		assertEquals(0, requests.get());
	}

	@Test
	public void rejectsExternalImagesAndRetriesMissingImages() throws Exception
	{
		AtomicInteger requests = new AtomicInteger();
		start(chain ->
		{
			requests.incrementAndGet();
			return response(chain, metadata("https://unrelated.test/Guard.png"));
		}, new AtomicBoolean(true));
		assertNull(service.getThumbnail("Guard.png").get(5, TimeUnit.SECONDS));
		assertNull(service.getThumbnail("Guard.png").get(5, TimeUnit.SECONDS));
		assertEquals(2, requests.get());
	}

	@Test
	public void invalidImageLeavesUsableEmptyResult() throws Exception
	{
		start(chain -> response(chain, chain.request().url().encodedPath().equals("/api.php")
			? metadata("https://wiki.test/images/Guard.png") : "not an image".getBytes(StandardCharsets.UTF_8)),
			new AtomicBoolean(true));
		assertNull(service.getThumbnail("Guard.png").get(5, TimeUnit.SECONDS));
	}

	@Test
	public void shutdownCompletesPendingImagesAndPreventsOldRequestsAfterRestart() throws Exception
	{
		CompletableFuture<Void> started = new CompletableFuture<>();
		CompletableFuture<Void> release = new CompletableFuture<>();
		CompletableFuture<Void> ended = new CompletableFuture<>();
		AtomicInteger requests = new AtomicInteger();
		start(chain ->
		{
			requests.incrementAndGet();
			started.complete(null);
			try
			{
				release.get(5, TimeUnit.SECONDS);
				assertTrue(chain.call().isCanceled());
				return response(chain, metadata("https://wiki.test/images/Guard.png"));
			}
			catch (Exception error)
			{
				throw new java.io.IOException(error);
			}
			finally
			{
				ended.complete(null);
			}
		}, new AtomicBoolean(true));
		CompletableFuture<BufferedImage> pending = service.getThumbnail("Guard.png");
		try
		{
			started.get(5, TimeUnit.SECONDS);
			service.shutDown();
			assertNull(pending.get(5, TimeUnit.SECONDS));
			service.startUp();
		}
		finally
		{
			release.complete(null);
		}
		ended.get(5, TimeUnit.SECONDS);
		assertEquals(1, requests.get());
	}

	private void start(Interceptor interceptor, AtomicBoolean enabled)
	{
		service = new NpcThumbnailService(new OkHttpClient.Builder().addInterceptor(interceptor).build(),
			new Gson(), enabled::get, HttpUrl.get("https://wiki.test/"));
		service.startUp();
	}

	private static byte[] metadata(String url)
	{
		return ("{\"query\":{\"pages\":{\"1\":{\"imageinfo\":[{\"thumburl\":\"" + url + "\"}]}}}}")
			.getBytes(StandardCharsets.UTF_8);
	}

	private static Response response(Interceptor.Chain chain, byte[] bytes)
	{
		return new Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
			.code(200).message("OK").body(ResponseBody.create(MediaType.parse("application/octet-stream"), bytes)).build();
	}
}
