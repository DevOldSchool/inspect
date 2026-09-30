package com.inspect.item;

import java.util.List;
import lombok.Builder;
import lombok.Value;

@Value
@Builder(toBuilder = true)
public class ItemInspectInfo
{
	int itemId;
	String wikiPage;
	String wikiAnchor;
	String displayName;
	String examine;
	String members;
	String tradeable;
	String equipable;
	String stackable;
	String noteable;
	String value;
	String weight;
	String highAlch;
	String lowAlch;
	String attackStab;
	String attackSlash;
	String attackCrush;
	String attackMagic;
	String attackRanged;
	String defenceStab;
	String defenceSlash;
	String defenceCrush;
	String defenceMagic;
	String defenceRanged;
	String strength;
	String rangedStrength;
	String magicDamage;
	String prayer;
	String slot;
	String attackSpeed;
	String attackRange;
	String requirementAttack;
	String requirementStrength;
	String requirementDefence;
	String requirementRanged;
	String requirementMagic;
	String requirementPrayer;
	String requirementHitpoints;
	String requirementSlayer;
	String questRequirements;
	String sourceSummary;
	List<ItemSource> sourcePlan;
	long fetchedAtEpochSecond;
	transient boolean cachedFallback;
	String sourceUrl;

	public boolean isExpired(long nowEpochSecond, int ttlDays)
	{
		return ttlDays <= 0 || nowEpochSecond - fetchedAtEpochSecond > ttlDays * 24L * 60L * 60L;
	}

	public String cacheKey()
	{
		String page = wikiPage == null ? "unknown" : wikiPage.replaceAll("[^A-Za-z0-9._-]", "_");
		String anchor = wikiAnchor == null ? "default" : wikiAnchor.replaceAll("[^A-Za-z0-9._-]", "_");
		return itemId + "-" + page + "-" + anchor;
	}
}
