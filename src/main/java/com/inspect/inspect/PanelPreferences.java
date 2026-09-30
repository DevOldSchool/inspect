package com.inspect.inspect;

import java.util.HashMap;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Function;
import javax.inject.Inject;
import net.runelite.client.config.ConfigManager;

/** Presentation choices stored in the current RuneLite configuration profile. */
public final class PanelPreferences
{
	private static final String COLLAPSED_PREFIX = "panelCollapsed.";
	private final Function<String, String> read;
	private final BiConsumer<String, String> write;
	private final Runnable resetLayout;

	@Inject
	public PanelPreferences(ConfigManager configManager)
	{
		this(key -> configManager.getConfiguration("inspect", key),
			(key, value) -> configManager.setConfiguration("inspect", key, value),
			() -> configManager.getConfigurationKeys("inspect." + COLLAPSED_PREFIX)
				.forEach(key -> configManager.unsetConfiguration("inspect", key.substring("inspect.".length()))));
	}

	PanelPreferences(Function<String, String> read, BiConsumer<String, String> write, Runnable resetLayout)
	{
		this.read = read;
		this.write = write;
		this.resetLayout = resetLayout;
	}

	static PanelPreferences inMemory()
	{
		Map<String, String> values = new HashMap<>();
		return new PanelPreferences(values::get, values::put,
			() -> values.keySet().removeIf(key -> key.startsWith(COLLAPSED_PREFIX)));
	}

	boolean isCollapsed(String key)
	{
		return Boolean.parseBoolean(read.apply(COLLAPSED_PREFIX + key));
	}

	void setCollapsed(String key, boolean collapsed)
	{
		write.accept(COLLAPSED_PREFIX + key, Boolean.toString(collapsed));
	}

	void resetLayout()
	{
		resetLayout.run();
	}

	String dropFilter()
	{
		String filter = read.apply("panelDropFilter");
		return filter == null ? "Valuable" : filter;
	}

	void setDropFilter(String filter)
	{
		write.accept("panelDropFilter", filter);
	}
}
