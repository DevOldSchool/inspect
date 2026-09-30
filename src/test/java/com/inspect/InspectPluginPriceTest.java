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

		ItemPriceSummary summary = InspectPlugin.itemPriceSummary(info, 3_000_000_000L, 100L, 5L, false);

		assertEquals("3,000,000,000 gp", summary.getGePrice());
		assertEquals("60,000 gp", summary.getHighAlch());
		assertEquals("40,000 gp", summary.getLowAlch());
		assertEquals("-2,999,940,125 gp", summary.getHighAlchProfit());
		assertEquals(Long.valueOf(-2_999_940_125L), summary.getHighAlchProfitValue());
	}

	@Test
	public void retainsAlchFallbackAndProfitFormatting()
	{
		ItemInspectInfo info = ItemInspectInfo.builder().value("100,000").build();

		ItemPriceSummary profit = InspectPlugin.itemPriceSummary(info, 50_000L, 100L, 5L, false);
		assertEquals("60,000 gp", profit.getHighAlch());
		assertEquals("40,000 gp", profit.getLowAlch());
		assertEquals("+9,875 gp", profit.getHighAlchProfit());
		assertEquals(Long.valueOf(9_875L), profit.getHighAlchProfitValue());
		assertEquals("0 gp", InspectPlugin.itemPriceSummary(info, 59_875L, 100L, 5L, false).getHighAlchProfit());
	}

	@Test
	public void unavailablePricesDoNotProduceProfit()
	{
		ItemInspectInfo info = ItemInspectInfo.builder().highAlch("60,000").build();
		for (long price : new long[] {0L, -1L})
		{
			ItemPriceSummary summary = InspectPlugin.itemPriceSummary(info, price, 100L, 5L, false);
			assertNull(summary.getGePrice());
			assertNull(summary.getHighAlchProfit());
			assertNull(summary.getHighAlchProfitValue());
		}
	}

	@Test
	public void subtractsNatureAndFireCostsUnlessEquipmentSuppliesFire()
	{
		ItemInspectInfo info = ItemInspectInfo.builder().highAlch("600").build();
		ItemPriceSummary normal = InspectPlugin.itemPriceSummary(info, 450, 100, 5, false);
		assertEquals("125 gp", normal.getCastingCost());
		assertEquals(Long.valueOf(25), normal.getHighAlchProfitValue());
		ItemPriceSummary equipped = InspectPlugin.itemPriceSummary(info, 450, 100, 0, true);
		assertEquals("100 gp", equipped.getCastingCost());
		assertEquals(Long.valueOf(50), equipped.getHighAlchProfitValue());
	}

	@Test
	public void missingRunePricesAndOverflowDoNotPretendRunesAreFree()
	{
		ItemInspectInfo info = ItemInspectInfo.builder().highAlch("600").build();
		assertNull(InspectPlugin.itemPriceSummary(info, 450, 0, 5, true).getHighAlchProfit());
		assertNull(InspectPlugin.itemPriceSummary(info, 450, 100, 0, false).getHighAlchProfit());
		assertNull(InspectPlugin.itemPriceSummary(info, 450, 100, Long.MAX_VALUE, false).getHighAlchProfit());
		assertNull(InspectPlugin.itemPriceSummary(info, Long.MAX_VALUE, 1000, 5, true).getHighAlchProfit());
		assertEquals("Unavailable", InspectPlugin.itemPriceSummary(info, 450, -1, 5, false).getCastingCost());
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
