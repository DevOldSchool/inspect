package com.inspect.npc;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import lombok.Value;

@Value
public class NpcSearchResults
{
	String query;
	List<NpcCombatInfo> choices;
	long fetchedAtEpochSecond;
	boolean partial;

	public NpcSearchResults(String query, List<NpcCombatInfo> choices, long fetchedAtEpochSecond, boolean partial)
	{
		this.query = query;
		this.choices = Collections.unmodifiableList(new ArrayList<>(choices));
		this.fetchedAtEpochSecond = fetchedAtEpochSecond;
		this.partial = partial;
	}

	boolean isExpired(long now, int ttlDays)
	{
		return ttlDays <= 0 || now - fetchedAtEpochSecond > ttlDays * 86400L;
	}

	NpcSearchResults asFallback()
	{
		return new NpcSearchResults(query, choices.stream()
			.map(info -> info.toBuilder().cachedFallback(true).build()).collect(Collectors.toList()),
			fetchedAtEpochSecond, partial);
	}
}
