package com.inspect.item;

import java.util.List;
import lombok.Value;

@Value
public class ItemSourceReadiness
{
	ItemSourceAccountMode accountMode;
	List<ItemSourceStatus> sources;
}
