package com.inspect.item;

import lombok.Value;

@Value
public class ItemInspectVariant
{
	int id;
	String displayName;
	String wikiPage;
	String wikiAnchor;
	String sourceUrl;
}
