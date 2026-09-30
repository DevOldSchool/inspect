package com.inspect.npc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.inspect.item.ItemInspectInfo;
import org.junit.Test;

public class CombatStyleRecommendationTest
{
	@Test
	public void selectsEachCombatStyleFromTheLowestDefence()
	{
		assertEquals(CombatStyleRecommendation.STAB, CombatStyleRecommendation.forNpc(npc("-5", "20", "30", "40", "50", "60", "70")));
		assertEquals(CombatStyleRecommendation.SLASH, CombatStyleRecommendation.forNpc(npc("20", "-5", "30", "40", "50", "60", "70")));
		assertEquals(CombatStyleRecommendation.CRUSH, CombatStyleRecommendation.forNpc(npc("20", "30", "-5", "40", "50", "60", "70")));
		assertEquals(CombatStyleRecommendation.MAGIC, CombatStyleRecommendation.forNpc(npc("20", "30", "40", "-5", "50", "60", "70")));
		assertEquals(CombatStyleRecommendation.RANGED, CombatStyleRecommendation.forNpc(npc("20", "30", "40", "50", "60", "-5", "70")));
	}

	@Test
	public void usesStableStyleOrderWhenDefencesTie()
	{
		NpcCombatInfo npc = npc("10", "10", "10", "10", "10", "10", "10");

		assertEquals(CombatStyleRecommendation.STAB, CombatStyleRecommendation.forNpc(npc));
	}

	@Test
	public void returnsNullWhenNoDefenceIsNumeric()
	{
		NpcCombatInfo npc = npc(null, "Unknown", "--", "N/A", "", null, "immune");

		assertNull(CombatStyleRecommendation.forNpc(npc));
		assertNull(CombatStyleRecommendation.forNpc(null));
	}

	@Test
	public void parsesFormattedNumbersAndRejectsMalformedValues()
	{
		assertEquals(-1234.5d, CombatStyleRecommendation.numericValue("Defence: -1,234.5%"), 0.0d);
		assertEquals(12.0d, CombatStyleRecommendation.numericValue("+12 bonus"), 0.0d);
		assertNull(CombatStyleRecommendation.numericValue("not available"));
		assertNull(CombatStyleRecommendation.numericValue("-"));
	}

	@Test
	public void scoreWeightsDamageAndPrayerAndUsesStyleSpecificBonuses()
	{
		ItemInspectInfo stabItem = ItemInspectInfo.builder()
			.attackStab("10")
			.strength("4")
			.prayer("20")
			.build();
		ItemInspectInfo rangedItem = ItemInspectInfo.builder()
			.attackRanged("10")
			.rangedStrength("4")
			.build();
		ItemInspectInfo nonPositiveItem = ItemInspectInfo.builder()
			.attackStab("-2")
			.build();

		assertEquals(18.0d, CombatStyleRecommendation.STAB.score(stabItem), 0.0d);
		assertTrue(CombatStyleRecommendation.STAB.isRelevant(stabItem));
		assertFalse(CombatStyleRecommendation.STAB.isRelevant(rangedItem));
		assertTrue(CombatStyleRecommendation.RANGED.isRelevant(rangedItem));
		assertFalse(CombatStyleRecommendation.STAB.isRelevant(nonPositiveItem));
	}

	@Test
	public void explanationsUseTheSameBonusesAndWeightsAsRanking()
	{
		ItemInspectInfo item = ItemInspectInfo.builder().attackStab("-2").attackSlash("10").attackCrush("8")
			.strength("4").attackMagic("5").magicDamage("2.5%").attackRanged("9").rangedStrength("3").prayer("3").build();
		for (CombatStyleRecommendation style : CombatStyleRecommendation.values())
		{
			EquipmentScore score = style.scoreBreakdown(item);
			assertEquals(style.score(item), score.getTotal(), 0.0);
			assertTrue(score.getExplanation().contains("× 1.5"));
			assertTrue(score.getExplanation().contains("Prayer (3) × 0.1"));
		}
		assertEquals(4.3, CombatStyleRecommendation.STAB.scoreBreakdown(item).getTotal(), 0.00001);
		assertEquals(9.05, CombatStyleRecommendation.MAGIC.scoreBreakdown(item).getTotal(), 0.00001);
		assertTrue(CombatStyleRecommendation.MAGIC.scoreBreakdown(item).getSummary().contains("Magic damage (%) +2.5"));
		assertTrue(CombatStyleRecommendation.RANGED.scoreBreakdown(item).getSummary().contains("Ranged strength +3"));
	}

	@Test
	public void missingStatsAreDistinguishedFromKnownZero()
	{
		EquipmentScore score = CombatStyleRecommendation.STAB.scoreBreakdown(
			ItemInspectInfo.builder().attackStab("10").prayer("0").build());
		assertEquals(10.0, score.getTotal(), 0.0);
		assertTrue(score.getSummary().contains("Strength ?"));
		assertTrue(score.getSummary().contains("Prayer 0"));
		assertTrue(score.getExplanation().contains("0 (unknown)"));
	}

	private static NpcCombatInfo npc(
		String stab,
		String slash,
		String crush,
		String magic,
		String lightRanged,
		String standardRanged,
		String heavyRanged)
	{
		return NpcCombatInfo.builder()
			.stabDefence(stab)
			.slashDefence(slash)
			.crushDefence(crush)
			.magicDefence(magic)
			.lightRangedDefence(lightRanged)
			.standardRangedDefence(standardRanged)
			.heavyRangedDefence(heavyRanged)
			.build();
	}
}
