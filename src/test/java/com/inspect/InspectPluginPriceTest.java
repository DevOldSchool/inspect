package com.inspect;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import com.inspect.item.ItemInspectInfo;
import com.inspect.item.ItemPriceSummary;
import com.inspect.player.PlayerEquipmentItem;
import java.util.Arrays;
import java.util.Collections;
import net.runelite.api.gameval.ItemID;
import org.junit.Test;

public class InspectPluginPriceTest
{
	@Test
	public void preservesPricesAndAlchLossesAboveIntegerRange()
	{
		ItemInspectInfo info = ItemInspectInfo.builder()
			.highAlch("60,000")
			.lowAlch("40,000")
			.build();

		ItemPriceSummary summary = InspectPlugin.itemPriceSummary(info, 3_000_000_000L);

		assertEquals("3,000,000,000 gp", summary.getGePrice());
		assertEquals("60,000 gp", summary.getHighAlch());
		assertEquals("40,000 gp", summary.getLowAlch());
		assertEquals("-2,999,940,000 gp", summary.getHighAlchProfit());
		assertEquals(Long.valueOf(-2_999_940_000L), summary.getHighAlchProfitValue());
	}

	@Test
	public void retainsAlchFallbackAndProfitFormatting()
	{
		ItemInspectInfo info = ItemInspectInfo.builder().value("100,000").build();

		ItemPriceSummary profit = InspectPlugin.itemPriceSummary(info, 50_000L);
		assertEquals("60,000 gp", profit.getHighAlch());
		assertEquals("40,000 gp", profit.getLowAlch());
		assertEquals("+10,000 gp", profit.getHighAlchProfit());
		assertEquals(Long.valueOf(10_000L), profit.getHighAlchProfitValue());
		assertEquals("0 gp", InspectPlugin.itemPriceSummary(info, 60_000L).getHighAlchProfit());
	}

	@Test
	public void unavailablePricesDoNotProduceProfit()
	{
		ItemInspectInfo info = ItemInspectInfo.builder().highAlch("60,000").build();
		for (long price : new long[] {0L, -1L})
		{
			ItemPriceSummary summary = InspectPlugin.itemPriceSummary(info, price);
			assertNull(summary.getGePrice());
			assertNull(summary.getHighAlchProfit());
			assertNull(summary.getHighAlchProfitValue());
		}
	}

	@Test
	public void sumsVisibleEquipmentAboveIntegerRange()
	{
		assertEquals(5_000_000_000L, InspectPlugin.totalVisibleValue(Arrays.asList(
			new PlayerEquipmentItem("Weapon", ItemID.ABYSSAL_WHIP, "Abyssal whip", 3_000_000_000L),
			new PlayerEquipmentItem("Shield", ItemID.DRAGON_SQ_SHIELD, "Dragon sq shield", 2_000_000_000L))));
		assertEquals(0L, InspectPlugin.totalVisibleValue(Collections.emptyList()));
	}
}
