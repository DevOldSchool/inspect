package com.inspect.item;

import java.util.List;
import lombok.Value;

@Value
public class ItemSourceStatus
{
	ItemSource source;
	List<String> metRequirements;
	List<String> missingRequirements;
	boolean ironmanRelevant;

	public boolean isReady()
	{
		return missingRequirements == null || missingRequirements.isEmpty();
	}
}
