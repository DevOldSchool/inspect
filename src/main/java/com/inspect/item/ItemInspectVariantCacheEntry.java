package com.inspect.item;

import java.util.List;
import lombok.Value;

@Value
class ItemInspectVariantCacheEntry
{
	String normalizedSearchTerm;
	long fetchedAtEpochSecond;
	List<ItemInspectVariant> variants;

	boolean isExpired(long nowEpochSecond, int ttlDays)
	{
		return ttlDays <= 0 || nowEpochSecond - fetchedAtEpochSecond > ttlDays * 24L * 60L * 60L;
	}
}
