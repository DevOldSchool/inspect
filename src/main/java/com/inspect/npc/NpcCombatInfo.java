package com.inspect.npc;

import java.util.List;
import java.util.Objects;
import lombok.Builder;
import lombok.Value;

@Value
@Builder(toBuilder = true)
public class NpcCombatInfo
{
	public static final int CACHE_SCHEMA_VERSION = 2;

	int npcId;
	@Builder.Default
	int cacheSchemaVersion = CACHE_SCHEMA_VERSION;
	String wikiPage;
	String wikiAnchor;
	String displayName;
	String combatLevel;
	String xpBonus;
	String maxHit;
	String aggressive;
	String poisonous;
	String attackStyle;
	String attackSpeed;
	String respawnTime;
	String hitpoints;
	String attack;
	String strength;
	String defence;
	String magic;
	String ranged;
	String attackBonus;
	String strengthBonus;
	String magicAttack;
	String magicStrength;
	String rangedAttack;
	String rangedStrength;
	String stabDefence;
	String slashDefence;
	String crushDefence;
	String magicDefence;
	String elementalWeaknessType;
	String elementalWeakness;
	String lightRangedDefence;
	String standardRangedDefence;
	String heavyRangedDefence;
	String poisonResistance;
	String venomResistance;
	String cannonImmunity;
	String thrallImmunity;
	String slayerLevel;
	String slayerCategory;
	String superiorVariant;
	String notableDrops;
	String rareDrops;
	String valuableDrops;
	String slayerOnlyDrops;
	String uniqueDrops;
	String alchableDrops;
	String clueDrops;
	String ironmanDrops;
	String upgradeDrops;
	String resourceDrops;
	String supplyDrops;
	String assignedBy;
	String taskOnly;
	@Builder.Default
	List<NpcItemRequirement> itemRequirements = java.util.Collections.emptyList();
	long fetchedAtEpochSecond;
	transient boolean cachedFallback;
	String sourceUrl;

	public String cacheKey()
	{
		String page = wikiPage == null ? "unknown" : wikiPage;
		String anchor = wikiAnchor == null ? "default" : wikiAnchor;
		return npcId + "-" + sanitize(page) + "-" + sanitize(anchor);
	}

	public boolean isExpired(long nowEpochSecond, int ttlDays)
	{
		if (ttlDays <= 0)
		{
			return true;
		}

		long ttlSeconds = ttlDays * 24L * 60L * 60L;
		return nowEpochSecond - fetchedAtEpochSecond > ttlSeconds;
	}

	public boolean hasCurrentCacheSchema()
	{
		return cacheSchemaVersion == CACHE_SCHEMA_VERSION;
	}

	private static String sanitize(String value)
	{
		StringBuilder builder = new StringBuilder(value.length());
		for (int i = 0; i < value.length(); i++)
		{
			char c = value.charAt(i);
			builder.append(Character.isLetterOrDigit(c) ? Character.toLowerCase(c) : '-');
		}
		return builder.toString().replaceAll("-+", "-").replaceAll("^-|-$", "");
	}

	public String valueOrDash(String value)
	{
		return Objects.toString(value, "--");
	}
}
