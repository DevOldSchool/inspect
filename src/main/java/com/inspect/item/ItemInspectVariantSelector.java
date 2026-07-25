package com.inspect.item;

import java.util.List;
import java.util.Locale;

public final class ItemInspectVariantSelector
{
	private ItemInspectVariantSelector()
	{
	}

	public static ItemInspectVariant exactMatch(String query, List<ItemInspectVariant> variants)
	{
		if (variants == null || variants.isEmpty())
		{
			return null;
		}
		if (variants.size() == 1)
		{
			return variants.get(0);
		}

		String normalizedQuery = normalize(query);
		if (normalizedQuery.isEmpty())
		{
			return null;
		}

		ItemInspectVariant match = null;
		for (ItemInspectVariant variant : variants)
		{
			if (variant == null
				|| normalizedQuery.equals(normalize(variant.getWikiPage()))
				|| !normalizedQuery.equals(normalize(variant.getDisplayName())))
			{
				continue;
			}
			if (match != null)
			{
				return null;
			}
			match = variant;
		}
		return match;
	}

	private static String normalize(String value)
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
