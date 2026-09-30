package com.inspect.npc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.inspect.item.ItemInspectInfo;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

public class BankEquipmentRecommendationServiceTest
{
	@Test
	public void emptyInputReturnsPreviewWithoutItemLookups() throws Exception
	{
		AtomicInteger calls = new AtomicInteger();
		BankEquipmentRecommendationService service = new BankEquipmentRecommendationService((itemId, itemName, ttlDays) ->
		{
			calls.incrementAndGet();
			return CompletableFuture.completedFuture(null);
		});

		EquipmentRecommendation nullItems = service.recommend(stabNpc(), null, 7).get(5, TimeUnit.SECONDS);
		EquipmentRecommendation emptyItems = service.recommend(stabNpc(), Collections.emptyList(), 7).get(5, TimeUnit.SECONDS);

		assertEquals("Stab melee", nullItems.getStyleName());
		assertFalse(nullItems.hasItems());
		assertEquals("Stab melee", emptyItems.getStyleName());
		assertEquals(0, calls.get());
	}

	@Test
	public void duplicateCandidatesMergeSourcesAndOnlyInspectOnce() throws Exception
	{
		AtomicInteger calls = new AtomicInteger();
		BankEquipmentRecommendationService service = new BankEquipmentRecommendationService((itemId, itemName, ttlDays) ->
		{
			calls.incrementAndGet();
			return CompletableFuture.completedFuture(item(itemId, itemName, 25));
		});

		EquipmentRecommendation recommendation = service.recommend(stabNpc(), Arrays.asList(
			new BankEquipmentRecommendationService.BankItemCandidate(100, "Sword", true, false),
			new BankEquipmentRecommendationService.BankItemCandidate(100, "Sword", false, true)
		), 7).get(5, TimeUnit.SECONDS);

		assertEquals(1, calls.get());
		assertEquals(1, recommendation.getItems().size());
		assertTrue(recommendation.getItems().get(0).isInBank());
		assertTrue(recommendation.getItems().get(0).isEquipped());
		assertEquals(Integer.valueOf(1), recommendation.bankItemRanks().get(100));
	}

	@Test
	public void batchLookupAvoidsPerItemLookups() throws Exception
	{
		AtomicInteger itemLookupCalls = new AtomicInteger();
		Map<Integer, ItemInspectInfo> batchItems = new LinkedHashMap<>();
		batchItems.put(1, item(1, "Bronze sword", 10));
		batchItems.put(2, item(2, "Iron sword", 20));
		BankEquipmentRecommendationService service = new BankEquipmentRecommendationService(
			(itemId, itemName, ttlDays) ->
			{
				itemLookupCalls.incrementAndGet();
				return CompletableFuture.completedFuture(null);
			},
			itemIds -> CompletableFuture.completedFuture(batchItems));

		EquipmentRecommendation recommendation = service.recommend(stabNpc(), Arrays.asList(
			new BankEquipmentRecommendationService.BankItemCandidate(1, "Bronze sword", true, false),
			new BankEquipmentRecommendationService.BankItemCandidate(2, "Iron sword", true, false)
		), 7).get(5, TimeUnit.SECONDS);

		assertEquals(0, itemLookupCalls.get());
		assertEquals(2, recommendation.getItems().size());
		assertEquals(2, recommendation.getItems().get(0).getItemId());
		assertEquals(Integer.valueOf(1), recommendation.bankItemRanks().get(2));
	}

	@Test
	public void failedLookupDoesNotDiscardSuccessfulCandidates() throws Exception
	{
		BankEquipmentRecommendationService service = new BankEquipmentRecommendationService((itemId, itemName, ttlDays) ->
		{
			if (itemId == 1)
			{
				CompletableFuture<ItemInspectInfo> failed = new CompletableFuture<>();
				failed.completeExceptionally(new IOException("offline"));
				return failed;
			}
			return CompletableFuture.completedFuture(item(itemId, itemName, 20));
		});

		EquipmentRecommendation recommendation = service.recommend(stabNpc(), Arrays.asList(
			new BankEquipmentRecommendationService.BankItemCandidate(1, "Unavailable", true, false),
			new BankEquipmentRecommendationService.BankItemCandidate(2, "Available", true, false)
		), 7).get(5, TimeUnit.SECONDS);

		assertEquals(1, recommendation.getItems().size());
		assertEquals(2, recommendation.getItems().get(0).getItemId());
	}

	@Test
	public void startsNextLookupOnlyAfterPreviousLookupCompletes() throws Exception
	{
		List<Integer> started = new ArrayList<>();
		CompletableFuture<ItemInspectInfo> firstLookup = new CompletableFuture<>();
		CompletableFuture<ItemInspectInfo> secondLookup = new CompletableFuture<>();
		BankEquipmentRecommendationService service = new BankEquipmentRecommendationService((itemId, itemName, ttlDays) ->
		{
			started.add(itemId);
			return itemId == 1 ? firstLookup : secondLookup;
		});

		CompletableFuture<EquipmentRecommendation> recommendation = service.recommend(stabNpc(), Arrays.asList(
			new BankEquipmentRecommendationService.BankItemCandidate(1, "First", true, false),
			new BankEquipmentRecommendationService.BankItemCandidate(2, "Second", true, false)
		), 7);

		assertEquals(Collections.singletonList(1), started);
		assertFalse(recommendation.isDone());

		firstLookup.complete(item(1, "First", 10));

		assertEquals(Arrays.asList(1, 2), started);
		assertFalse(recommendation.isDone());

		secondLookup.complete(item(2, "Second", 20));

		assertEquals(2, recommendation.get(5, TimeUnit.SECONDS).getItems().get(0).getItemId());
	}

	@Test
	public void recommendationIsCappedAtThreePerSlotWithConsecutiveRanks() throws Exception
	{
		AtomicInteger calls = new AtomicInteger();
		BankEquipmentRecommendationService service = new BankEquipmentRecommendationService((itemId, itemName, ttlDays) ->
		{
			calls.incrementAndGet();
			return CompletableFuture.completedFuture(item(itemId, itemName, itemId));
		});
		List<BankEquipmentRecommendationService.BankItemCandidate> candidates = new ArrayList<>();
		for (int itemId = 1; itemId <= 15; itemId++)
		{
			candidates.add(new BankEquipmentRecommendationService.BankItemCandidate(itemId, "Item " + itemId, true, false));
		}

		EquipmentRecommendation recommendation = service.recommend(stabNpc(), candidates, 7).get(5, TimeUnit.SECONDS);

		assertEquals(15, calls.get());
		assertEquals(3, recommendation.getItems().size());
		assertEquals(15, recommendation.getItems().get(0).getItemId());
		assertEquals(13, recommendation.getItems().get(2).getItemId());
		for (int index = 0; index < recommendation.getItems().size(); index++)
		{
			assertEquals(index + 1, recommendation.getItems().get(index).getRank());
		}
	}

	private static NpcCombatInfo stabNpc()
	{
		return NpcCombatInfo.builder()
			.stabDefence("1")
			.slashDefence("20")
			.crushDefence("30")
			.magicDefence("40")
			.lightRangedDefence("50")
			.standardRangedDefence("60")
			.heavyRangedDefence("70")
			.build();
	}

	private static ItemInspectInfo item(int itemId, String name, int attackStab)
	{
		return ItemInspectInfo.builder()
			.itemId(itemId)
			.displayName(name)
			.slot("Weapon")
			.attackStab(Integer.toString(attackStab))
			.build();
	}
}
