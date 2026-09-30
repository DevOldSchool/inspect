package com.inspect.npc;

import com.inspect.item.ItemInspectInfo;
import com.inspect.item.ItemInspectService;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import javax.inject.Inject;
import javax.inject.Singleton;

@Singleton
public class BankEquipmentRecommendationService
{
	private static final int RECOMMENDATIONS_PER_SLOT = 3;

	private final ItemLookupService itemLookupService;
	private final BatchItemLookupService batchItemLookupService;

	@Inject
	BankEquipmentRecommendationService(ItemInspectService itemInspectService)
	{
		this(itemInspectService::inspect, itemInspectService::inspectEquipmentStats);
	}

	BankEquipmentRecommendationService(ItemLookupService itemLookupService)
	{
		this(itemLookupService, null);
	}

	BankEquipmentRecommendationService(ItemLookupService itemLookupService, BatchItemLookupService batchItemLookupService)
	{
		this.itemLookupService = itemLookupService;
		this.batchItemLookupService = batchItemLookupService;
	}

	public CompletableFuture<EquipmentRecommendation> recommend(NpcCombatInfo npc, Collection<BankItemCandidate> bankItems, int ttlDays)
	{
		if (npc == null || bankItems == null || bankItems.isEmpty())
		{
			return CompletableFuture.completedFuture(EquipmentRecommendation.preview(npc));
		}

		Map<Integer, BankItemCandidate> uniqueItems = new LinkedHashMap<>();
		for (BankItemCandidate item : bankItems)
		{
			BankItemCandidate existing = uniqueItems.get(item.getItemId());
			if (existing == null)
			{
				uniqueItems.put(item.getItemId(), item);
				continue;
			}

			uniqueItems.put(item.getItemId(), new BankItemCandidate(
				existing.getItemId(),
				existing.getItemName(),
				existing.isInBank() || item.isInBank(),
				existing.isEquipped() || item.isEquipped()
			));
		}

		if (batchItemLookupService != null)
		{
			return batchItemLookupService.inspect(uniqueItems.keySet())
				.exceptionally(throwable -> Collections.emptyMap())
				.thenApply(lookups -> recommendationFromLookups(npc, uniqueItems, lookups));
		}

		CompletableFuture<Void> lookupChain = CompletableFuture.completedFuture(null);
		List<EquipmentRecommendation.CandidateItem> items = new ArrayList<>();
		for (BankItemCandidate item : uniqueItems.values())
		{
			lookupChain = lookupChain.thenCompose(ignored -> itemLookupService.inspect(item.getItemId(), item.getItemName(), ttlDays)
				.exceptionally(throwable -> null)
				.thenAccept(info ->
				{
					if (info != null)
					{
						items.add(new EquipmentRecommendation.CandidateItem(info, item.isInBank(), item.isEquipped()));
					}
				}));
		}

		return lookupChain.thenApply(ignored -> EquipmentRecommendation.fromCandidates(npc, items, RECOMMENDATIONS_PER_SLOT));
	}

	private static EquipmentRecommendation recommendationFromLookups(NpcCombatInfo npc, Map<Integer, BankItemCandidate> candidates, Map<Integer, ItemInspectInfo> lookups)
	{
		List<EquipmentRecommendation.CandidateItem> items = new ArrayList<>();
		for (Map.Entry<Integer, BankItemCandidate> entry : candidates.entrySet())
		{
			ItemInspectInfo info = lookups.get(entry.getKey());
			if (info != null)
			{
				BankItemCandidate candidate = entry.getValue();
				items.add(new EquipmentRecommendation.CandidateItem(info, candidate.isInBank(), candidate.isEquipped()));
			}
		}
		return EquipmentRecommendation.fromCandidates(npc, items, RECOMMENDATIONS_PER_SLOT);
	}

	@FunctionalInterface
	interface ItemLookupService
	{
		CompletableFuture<ItemInspectInfo> inspect(int itemId, String itemName, int ttlDays);
	}

	@FunctionalInterface
	interface BatchItemLookupService
	{
		CompletableFuture<Map<Integer, ItemInspectInfo>> inspect(Collection<Integer> itemIds);
	}

	@lombok.Value
	public static class BankItemCandidate
	{
		int itemId;
		String itemName;
		boolean inBank;
		boolean equipped;
	}
}
