package com.inspect.item;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import net.runelite.api.Quest;
import net.runelite.api.QuestState;
import net.runelite.api.Skill;
import org.junit.Test;

public class ItemSourceReadinessEvaluatorTest
{
	@Test
	public void ironmanSourcesPrioritizeAvailableReadyMethods()
	{
		ItemSource trading = source(
			"Trading",
			"Purchased from the Grand Exchange.",
			Collections.emptyList());
		ItemSource skilling = source(
			"Skilling",
			"Created with level 99 Smithing.",
			Collections.singletonList(new ItemSourceRequirement("Smithing", 99, "Skilling")));
		ItemSource quest = source(
			"Quests",
			"Received as a reward after completing the Lost City quest.",
			Collections.emptyList());
		ItemSource monsters = source(
			"Monsters",
			"Dropped by abyssal demons.",
			Collections.emptyList());
		Map<Skill, Integer> skills = new EnumMap<>(Skill.class);
		skills.put(Skill.SMITHING, 80);
		Map<Quest, QuestState> quests = new EnumMap<>(Quest.class);
		quests.put(Quest.LOST_CITY, QuestState.FINISHED);

		ItemSourceReadiness readiness = ItemSourceReadinessEvaluator.evaluate(
			Arrays.asList(trading, skilling, quest, monsters),
			skills,
			quests,
			ItemSourceAccountMode.IRONMAN);

		assertEquals("Quests", readiness.getSources().get(0).getSource().getCategory());
		assertEquals("Monsters", readiness.getSources().get(1).getSource().getCategory());
		assertEquals("Skilling", readiness.getSources().get(2).getSource().getCategory());
		assertEquals("Trading", readiness.getSources().get(3).getSource().getCategory());
		assertEquals(Collections.singletonList("Lost City quest"), readiness.getSources().get(0).getMetRequirements());
		assertEquals(Collections.singletonList("Smithing 99 (80)"), readiness.getSources().get(2).getMissingRequirements());
		assertFalse(readiness.getSources().get(3).isIronmanRelevant());
	}

	@Test
	public void standardAccountPreservesWikiSourceOrder()
	{
		ItemSource skilling = source(
			"Skilling",
			"Created with level 80 Fletching.",
			Collections.singletonList(new ItemSourceRequirement("Fletching", 80, "Skilling")));
		ItemSource shops = source("Shops", "Sold by an NPC shop.", Collections.emptyList());
		Map<Skill, Integer> skills = new EnumMap<>(Skill.class);
		skills.put(Skill.FLETCHING, 90);

		ItemSourceReadiness readiness = ItemSourceReadinessEvaluator.evaluate(
			Arrays.asList(skilling, shops),
			skills,
			Collections.emptyMap(),
			ItemSourceAccountMode.STANDARD);

		assertEquals("Skilling", readiness.getSources().get(0).getSource().getCategory());
		assertEquals("Shops", readiness.getSources().get(1).getSource().getCategory());
		assertEquals(Collections.singletonList("Fletching 80 (90)"), readiness.getSources().get(0).getMetRequirements());
	}

	@Test
	public void grandExchangeSaleNoteDoesNotHideAnIronmanDropMethod()
	{
		ItemSource monsters = source(
			"Monsters",
			"Dropped by abyssal demons and can be sold on the Grand Exchange.",
			Collections.emptyList());

		ItemSourceReadiness readiness = ItemSourceReadinessEvaluator.evaluate(
			Collections.singletonList(monsters),
			Collections.emptyMap(),
			Collections.emptyMap(),
			ItemSourceAccountMode.IRONMAN);

		assertTrue(readiness.getSources().get(0).isIronmanRelevant());
	}

	@Test
	public void detectsReferencedQuestWithoutAlsoMatchingShorterParentQuest()
	{
		ItemSource quest = source(
			"Quests",
			"Rewarded after Recipe for Disaster - Another Cook's Quest.",
			Collections.emptyList());

		List<Quest> quests = ItemSourceReadinessEvaluator.referencedQuests(Collections.singletonList(quest));

		assertEquals(Collections.singletonList(Quest.RECIPE_FOR_DISASTER__ANOTHER_COOKS_QUEST), quests);
	}

	@Test
	public void mapsAllCurrentIronmanAccountModes()
	{
		assertFalse(ItemSourceAccountMode.fromVarbitValue(0).isIronman());
		for (int value = 1; value <= 6; value++)
		{
			assertTrue(ItemSourceAccountMode.fromVarbitValue(value).isIronman());
		}
		assertEquals(
			"Unranked Group Ironman",
			ItemSourceAccountMode.fromVarbitValue(6).getDisplayName());
	}

	private static ItemSource source(
		String category,
		String detail,
		List<ItemSourceRequirement> requirements)
	{
		return new ItemSource(category, Collections.singletonList(detail), requirements);
	}
}
