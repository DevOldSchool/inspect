package com.inspect.npc;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.inspect.InspectConfig;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.MemoryCacheImageInputStream;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

@Slf4j
@Singleton
public class NpcThumbnailService
{
	private final OkHttpClient http;
	private final Gson gson;
	private final BooleanSupplier enabled;
	private final HttpUrl wiki;
	private final Map<String, CompletableFuture<BufferedImage>> images = new LinkedHashMap<>();
	private final Set<Call> calls = new HashSet<>();
	private boolean running;
	private long generation;

	@Inject
	NpcThumbnailService(OkHttpClient http, Gson gson, InspectConfig config)
	{
		this(http, gson, config::enableWikiLookups, HttpUrl.get("https://oldschool.runescape.wiki"));
	}

	NpcThumbnailService(OkHttpClient http, Gson gson, BooleanSupplier enabled, HttpUrl wiki)
	{
		this.http = http.newBuilder().followRedirects(false).followSslRedirects(false).build();
		this.gson = gson;
		this.enabled = enabled;
		this.wiki = wiki;
	}

	public synchronized void startUp()
	{
		running = true;
	}

	public synchronized void shutDown()
	{
		running = false;
		generation++;
		for (Call call : calls)
		{
			call.cancel();
		}
		calls.clear();
		for (CompletableFuture<BufferedImage> image : images.values())
		{
			image.complete(null);
		}
		images.clear();
	}

	public synchronized CompletableFuture<BufferedImage> getThumbnail(String file)
	{
		if (!running || !enabled.getAsBoolean() || file == null || file.trim().isEmpty())
		{
			return CompletableFuture.completedFuture(null);
		}
		String key = file.replace('_', ' ').trim();
		CompletableFuture<BufferedImage> cached = images.get(key);
		if (cached != null)
		{
			return cached;
		}
		CompletableFuture<BufferedImage> result = new CompletableFuture<>();
		if (images.size() >= 128)
		{
			images.remove(images.keySet().iterator().next());
		}
		images.put(key, result);
		long revision = generation;
		HttpUrl query = wiki.newBuilder().addPathSegment("api.php")
			.addQueryParameter("action", "query").addQueryParameter("format", "json")
			.addQueryParameter("prop", "imageinfo").addQueryParameter("iiprop", "url")
			.addQueryParameter("iiurlwidth", "38").addQueryParameter("iiurlheight", "38")
			.addQueryParameter("titles", "File:" + key).build();
		request(revision, query, body -> thumbnailUrl(gson.fromJson(body.string(), JsonObject.class)))
			.thenCompose(url -> url == null ? CompletableFuture.completedFuture(null) : request(revision, url, NpcThumbnailService::decode))
			.whenComplete((image, error) ->
			{
				synchronized (this)
				{
					if (error != null || image == null)
					{
						images.remove(key, result);
					}
					result.complete(running && revision == generation && enabled.getAsBoolean() && error == null ? image : null);
				}
				if (error != null)
				{
					log.debug("Unable to load NPC thumbnail {}", key, error);
				}
			});
		return result;
	}

	private HttpUrl thumbnailUrl(JsonObject json)
	{
		if (json == null || !json.has("query") || !json.getAsJsonObject("query").has("pages"))
		{
			return null;
		}
		for (Map.Entry<String, JsonElement> page : json.getAsJsonObject("query").getAsJsonObject("pages").entrySet())
		{
			JsonObject object = page.getValue().getAsJsonObject();
			if (object.has("imageinfo") && object.getAsJsonArray("imageinfo").size() > 0)
			{
				JsonObject info = object.getAsJsonArray("imageinfo").get(0).getAsJsonObject();
				JsonElement url = info.has("thumburl") ? info.get("thumburl") : info.get("url");
				return url == null ? null : HttpUrl.parse(url.getAsString());
			}
		}
		return null;
	}

	private synchronized <T> CompletableFuture<T> request(long revision, HttpUrl url, BodyReader<T> reader)
	{
		if (!running || revision != generation || !enabled.getAsBoolean() || !url.scheme().equals(wiki.scheme())
			|| !url.host().equals(wiki.host()) || url.port() != wiki.port())
		{
			return CompletableFuture.completedFuture(null);
		}
		CompletableFuture<T> result = new CompletableFuture<>();
		Call call = http.newCall(new Request.Builder().url(url)
			.header("User-Agent", "Inspect RuneLite plugin (https://github.com/DevOldSchool/inspect)").build());
		calls.add(call);
		call.enqueue(new Callback()
		{
			@Override
			public void onFailure(Call failed, IOException error)
			{
				finish(failed);
				result.completeExceptionally(error);
			}

			@Override
			public void onResponse(Call completed, Response response)
			{
				try (Response closed = response; ResponseBody body = closed.body())
				{
					if (!closed.isSuccessful() || body == null)
					{
						throw new IOException("Wiki thumbnail request failed: " + closed.code());
					}
					result.complete(reader.read(body));
				}
				catch (IOException | RuntimeException error)
				{
					result.completeExceptionally(error);
				}
				finally
				{
					finish(completed);
				}
			}
		});
		return result;
	}

	private synchronized void finish(Call call)
	{
		calls.remove(call);
	}

	private static BufferedImage decode(ResponseBody body) throws IOException
	{
		byte[] bytes = body.byteStream().readNBytes(1_048_577);
		if (bytes.length > 1_048_576)
		{
			return null;
		}
		try (MemoryCacheImageInputStream input = new MemoryCacheImageInputStream(new ByteArrayInputStream(bytes)))
		{
			Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
			if (!readers.hasNext())
			{
				return null;
			}
			ImageReader reader = readers.next();
			try
			{
				reader.setInput(input);
				int width = reader.getWidth(0), height = reader.getHeight(0);
				if (width <= 0 || height <= 0 || width > 512 || height > 512)
				{
					return null;
				}
				BufferedImage image = reader.read(0);
				BufferedImage icon = new BufferedImage(38, 38, BufferedImage.TYPE_INT_ARGB);
				Graphics2D graphics = icon.createGraphics();
				try
				{
					double scale = Math.min(1d, Math.min(38d / width, 38d / height));
					int w = Math.max(1, (int) (width * scale)), h = Math.max(1, (int) (height * scale));
					graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
					graphics.drawImage(image, (38 - w) / 2, (38 - h) / 2, w, h, null);
				}
				finally
				{
					graphics.dispose();
				}
				return icon;
			}
			finally
			{
				reader.dispose();
			}
		}
	}

	private interface BodyReader<T>
	{
		T read(ResponseBody body) throws IOException;
	}
}
