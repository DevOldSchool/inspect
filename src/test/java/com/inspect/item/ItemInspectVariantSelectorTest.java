package com.inspect.item;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import java.util.Arrays;
import java.util.Collections;
import org.junit.Test;

public class ItemInspectVariantSelectorTest
{
	@Test
	public void selectsOnlyVariantWithoutShowingPicker()
	{
		ItemInspectVariant whip = variant(4151, "Abyssal whip", "Abyssal_whip", null);

		assertEquals(whip, ItemInspectVariantSelector.exactMatch("whip", Collections.singletonList(whip)));
	}

	@Test
	public void selectsExactNamedVariantButKeepsBasePageAmbiguous()
	{
		ItemInspectVariant unpoisoned = variant(1215, "Dragon dagger", "Dragon_dagger", "Unpoisoned");
		ItemInspectVariant poisonPlusPlus = variant(5698, "Dragon dagger(p++)", "Dragon_dagger", "Poison++");

		assertEquals(poisonPlusPlus, ItemInspectVariantSelector.exactMatch(
			"Dragon dagger(p++)",
			Arrays.asList(unpoisoned, poisonPlusPlus)));
		assertNull(ItemInspectVariantSelector.exactMatch(
			"Dragon dagger",
			Arrays.asList(unpoisoned, poisonPlusPlus)));
	}

	private static ItemInspectVariant variant(int id, String name, String page, String anchor)
	{
		return new ItemInspectVariant(id, name, page, anchor, "https://wiki.test");
	}
}
