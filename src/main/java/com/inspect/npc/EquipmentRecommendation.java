package com.inspect.npc;

import com.inspect.item.ItemInspectInfo;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Arrays;
import java.util.Locale;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.Value;

@Value
public class EquipmentRecommendation
{
	private static final List<String> SLOT_ORDER = Arrays.asList(
		"Weapon", "Shield", "Head", "Cape", "Neck", "Body", "Legs", "Hands", "Feet", "Ring", "Ammo");

	NpcCombatInfo npc;
	CombatStyleRecommendation style;
	List<RecommendedItem> items;

	public String getStyleName()
	{
		return style == null ? null : style.getDisplayName();
	}

	public String getDefenceLabel()
	{
		return style == null ? null : style.getDefenceLabel();
	}

	public boolean hasItems()
	{
		return items != null && !items.isEmpty();
	}

	public Set<Integer> itemIds()
	{
		Set<Integer> itemIds = new HashSet<>();
		if (items != null)
		{
			for (RecommendedItem item : items)
			{
				itemIds.add(item.getItemId());
			}
		}
		return itemIds;
	}

	public Map<Integer, Integer> bankItemRanks()
	{
		Map<Integer, Integer> ranks = new LinkedHashMap<>();
		if (items != null)
		{
			for (RecommendedItem item : items)
			{
				if (item.isInBank())
				{
					ranks.put(item.getItemId(), item.getRank());
				}
			}
		}
		return ranks;
	}

	public static EquipmentRecommendation preview(NpcCombatInfo npc)
	{
		return new EquipmentRecommendation(npc, CombatStyleRecommendation.forNpc(npc), Collections.emptyList());
	}

	static EquipmentRecommendation fromBank(NpcCombatInfo npc, Collection<ItemInspectInfo> bankItems, int limitPerSlot)
	{
		List<CandidateItem> candidates = new ArrayList<>();
		for (ItemInspectInfo item : bankItems)
		{
			candidates.add(new CandidateItem(item, true, false));
		}
		return fromCandidates(npc, candidates, limitPerSlot);
	}

	static EquipmentRecommendation fromCandidates(NpcCombatInfo npc, Collection<CandidateItem> candidates, int limitPerSlot)
	{
		CombatStyleRecommendation style = CombatStyleRecommendation.forNpc(npc);
		if (style == null)
		{
			return new EquipmentRecommendation(npc, null, Collections.emptyList());
		}

		Map<String, List<RecommendedItem>> bySlot = new LinkedHashMap<>();
		for (CandidateItem candidate : candidates)
		{
			ItemInspectInfo item = candidate.getInfo();
			String slot = item == null ? null : normalizedSlot(item.getSlot());
			if (item == null || slot == null)
			{
				continue;
			}
			EquipmentScore score = style.scoreBreakdown(item);
			if (score.getTotal() <= 0 || !Double.isFinite(score.getTotal()))
			{
				continue;
			}
			bySlot.computeIfAbsent(slot, ignored -> new ArrayList<>()).add(new RecommendedItem(
				item.getItemId(), item.getDisplayName(), slot, score.getTotal(), candidate.isInBank(),
				candidate.isEquipped(), 0, score, isTwoHanded(item.getSlot())));
		}

		List<RecommendedItem> recommendations = new ArrayList<>();
		int boundedLimit = Math.max(0, limitPerSlot);
		for (String slot : SLOT_ORDER)
		{
			List<RecommendedItem> ranked = bySlot.getOrDefault(slot, Collections.emptyList());
			ranked.sort(Comparator.comparingDouble(RecommendedItem::getScore).reversed()
				.thenComparing(RecommendedItem::getDisplayName, Comparator.nullsLast(String::compareToIgnoreCase))
				.thenComparingInt(RecommendedItem::getItemId));
			for (int i = 0; i < Math.min(boundedLimit, ranked.size()); i++)
			{
				RecommendedItem item = ranked.get(i);
				recommendations.add(new RecommendedItem(item.getItemId(), item.getDisplayName(), item.getSlot(),
					item.getScore(), item.isInBank(), item.isEquipped(), i + 1, item.getBreakdown(), item.isTwoHanded()));
			}
		}

		return new EquipmentRecommendation(npc, style, Collections.unmodifiableList(recommendations));
	}

	public Map<String, List<RecommendedItem>> getItemsBySlot()
	{
		Map<String, List<RecommendedItem>> groups = new LinkedHashMap<>();
		if (items != null)
		{
			for (RecommendedItem item : items)
			{
				groups.computeIfAbsent(item.getSlot(), ignored -> new ArrayList<>()).add(item);
			}
		}
		groups.replaceAll((slot, entries) -> Collections.unmodifiableList(entries));
		return Collections.unmodifiableMap(groups);
	}

	private static boolean isTwoHanded(String slot)
	{
		String normalized = slot == null ? "" : slot.trim().toLowerCase(Locale.ROOT).replace('-', ' ');
		return normalized.equals("2h") || normalized.equals("2 handed") || normalized.equals("two handed");
	}

	private static String normalizedSlot(String slot)
	{
		if (isTwoHanded(slot))
		{
			return "Weapon";
		}
		String normalized = slot == null ? "" : slot.trim().toLowerCase(Locale.ROOT);
		switch (normalized)
		{
			case "head": return "Head";
			case "cape": return "Cape";
			case "neck": case "amulet": return "Neck";
			case "weapon": return "Weapon";
			case "body": case "torso": return "Body";
			case "shield": return "Shield";
			case "legs": return "Legs";
			case "hands": case "gloves": return "Hands";
			case "feet": case "boots": return "Feet";
			case "ring": return "Ring";
			case "ammo": case "ammunition": return "Ammo";
			default: return null;
		}
	}

	@Value
	static class CandidateItem
	{
		ItemInspectInfo info;
		boolean inBank;
		boolean equipped;
	}

	@Value
	public static class RecommendedItem
	{
		int itemId;
		String displayName;
		String slot;
		double score;
		boolean inBank;
		boolean equipped;
		int rank;
		EquipmentScore breakdown;
		boolean twoHanded;
	}
}
