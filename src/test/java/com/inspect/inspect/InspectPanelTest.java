package com.inspect.inspect;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.inspect.item.ItemInspectInfo;
import com.inspect.item.ItemInspectVariant;
import com.inspect.item.ItemPriceSummary;
import com.inspect.item.ItemRequirementSummary;
import com.inspect.item.ItemSource;
import com.inspect.item.ItemSourceAccountMode;
import com.inspect.item.ItemSourceReadiness;
import com.inspect.item.ItemSourceReadinessEvaluator;
import com.inspect.item.ItemSourceRequirement;
import com.inspect.npc.EquipmentRecommendation;
import com.inspect.npc.NpcCombatInfo;
import com.inspect.npc.NpcSearchResults;
import com.inspect.npc.NpcItemRequirement;
import com.inspect.npc.NpcItemRequirementAlternativeStatus;
import com.inspect.npc.NpcItemRequirementStatus;
import com.inspect.player.PlayerEquipmentItem;
import com.inspect.player.PlayerInspectAnalysis;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.Point;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.AbstractButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.SwingUtilities;
import javax.swing.JTextArea;
import net.runelite.api.Quest;
import net.runelite.api.QuestState;
import net.runelite.api.Skill;
import net.runelite.api.gameval.ItemID;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.PluginPanel;
import net.runelite.client.ui.components.IconTextField;
import org.junit.Test;

public class InspectPanelTest
{
	private static PanelPreferences savedPreferences(Map<String, String> saved)
	{
		return new PanelPreferences(saved::get, saved::put,
			() -> saved.keySet().removeIf(key -> key.startsWith("panelCollapsed.")));
	}

	@Test
	public void resetLayoutExpandsAllTabsAndPersistsWithoutChangingFilterOrInspection() throws Exception
	{
		onEdt(() ->
		{
			Map<String, String> saved = new LinkedHashMap<>();
			saved.put("panelCollapsed.player.selfComparison", "true");
			saved.put("panelCollapsed.recent.recentItems", "true");
			saved.put("panelDropFilter", "Rare");
			saved.put("unrelatedSetting", "keep");
			InspectPanel panel = new InspectPanel(null, null);
			panel.setPanelPreferences(savedPreferences(saved));
			ItemInspectInfo item = ItemInspectInfo.builder().displayName("Test item")
				.sourceSummary("Shop").build();
			panel.showItemInfo(item, null, null, null);
			clickButton(panel, "Item info");
			clickButton(panel, "Sources");
			NpcCombatInfo npc = NpcCombatInfo.builder().displayName("Guard")
				.valuableDrops("Coins").rareDrops("Dragon dagger").build();
			panel.showInfo(npc, null, null, Collections.emptyList());
			clickButton(panel, "Combat info");
			clickButton(panel, "Drop filters");
			AbstractButton heading = findButton(panel, "Combat info");
			long revision = panel.getViewRevision();
			assertEquals(panel, findButton(panel, "Reset panel layout").getParent());
			clickButton(panel, "Reset panel layout");
			assertTrue(panel.isShowingNpc(npc));
			assertEquals(revision, panel.getViewRevision());
			assertSame(heading, findButton(panel, "Combat info"));
			assertTrue(heading.getParent().getComponent(1).isVisible());
			assertTrue(findButton(panel, "Drop filters").getParent().getComponent(1).isVisible());
			assertEquals(ColorScheme.MEDIUM_GRAY_COLOR, findButton(panel, "Rare").getBackground());
			assertFalse(saved.keySet().stream().anyMatch(key -> key.startsWith("panelCollapsed.")));
			assertEquals("Rare", saved.get("panelDropFilter"));
			assertEquals("keep", saved.get("unrelatedSetting"));
			clickButton(panel, "Item");
			assertTrue(findButton(panel, "Sources").getParent().getComponent(1).isVisible());
			assertTrue(findButton(panel, "Item info").getParent().getComponent(1).isVisible());
			InspectPanel restarted = new InspectPanel(null, null);
			restarted.setPanelPreferences(savedPreferences(saved));
			restarted.showItemInfo(item, null, null, null);
			assertTrue(findButton(restarted, "Sources").getParent().getComponent(1).isVisible());
			clickButton(restarted, "Sources");
			assertEquals("true", saved.get("panelCollapsed.item.sources"));
			return null;
		});
	}

	@Test
	public void collapseChoicesPersistWithoutHidingPageControls() throws Exception
	{
		onEdt(() ->
		{
			Map<String, String> saved = new LinkedHashMap<>();
			PanelPreferences preferences = savedPreferences(saved);
			InspectPanel panel = new InspectPanel(null, null);
			panel.setPanelPreferences(preferences);
			ItemInspectInfo info = ItemInspectInfo.builder().displayName("Test item")
				.sourceSummary("Shop").sourceUrl("https://oldschool.runescape.wiki/w/Test_item").build();
			panel.showItemInfo(info, null, null, null);
			AbstractButton heading = findButton(panel, "Sources");
			Container group = heading.getParent();
			int expandedHeight = panel.getPreferredSize().height;
			heading.doClick();
			assertEquals("true", saved.get("panelCollapsed.item.sources"));
			assertFalse(group.getComponent(1).isVisible());
			assertTrue(panel.getPreferredSize().height < expandedHeight);
			// Wiki navigation belongs to the page, outside the collapsible Sources group.
			assertEquals(panel, findButton(panel, "Open OSRS Wiki page").getParent());
			panel.refreshActiveView();
			assertFalse(findButton(panel, "Sources").getParent().getComponent(1).isVisible());

			InspectPanel restarted = new InspectPanel(null, null);
			restarted.setPanelPreferences(savedPreferences(saved));
			restarted.showItemInfo(info, null, null, null);
			assertFalse(findButton(restarted, "Sources").getParent().getComponent(1).isVisible());
			clickButton(restarted, "Sources");
			assertTrue(findButton(restarted, "Sources").getParent().getComponent(1).isVisible());
			assertEquals("false", saved.get("panelCollapsed.item.sources"));
			return null;
		});
	}

	@Test
	public void initialCachePreferenceIsRestoredAndOtherTabsRemainIndependent() throws Exception
	{
		onEdt(() ->
		{
			Map<String, String> saved = new LinkedHashMap<>();
			saved.put("panelCollapsed.item.cache", "true");
			InspectPanel panel = new InspectPanel(null, null);
			panel.setPanelPreferences(savedPreferences(saved));
			assertFalse(findButton(panel, "Cache").getParent().getComponent(1).isVisible());
			clickButton(panel, "NPC");
			panel.showEmpty();
			assertTrue(findButton(panel, "Cache").getParent().getComponent(1).isVisible());
			return null;
		});
	}

	@Test
	public void savedDropFilterSurvivesRestartAndNpcsWithoutThatFilter() throws Exception
	{
		onEdt(() ->
		{
			Map<String, String> saved = new LinkedHashMap<>();
			NpcCombatInfo info = NpcCombatInfo.builder().displayName("Guard")
				.valuableDrops("Coins").rareDrops("Dragon dagger").build();
			InspectPanel panel = new InspectPanel(null, null);
			panel.setPanelPreferences(savedPreferences(saved));
			panel.showInfo(info, null, null, Collections.emptyList());
			clickButton(panel, "Rare");
			assertEquals("Rare", saved.get("panelDropFilter"));
			InspectPanel restarted = new InspectPanel(null, null);
			restarted.setPanelPreferences(savedPreferences(saved));
			restarted.showInfo(info.toBuilder().rareDrops(null).build(), null, null, Collections.emptyList());
			restarted.showInfo(info, null, null, Collections.emptyList());
			assertEquals(net.runelite.client.ui.ColorScheme.MEDIUM_GRAY_COLOR,
				findButton(restarted, "Rare").getBackground());
			return null;
		});
	}

	@Test
	public void normalizesFuzzySearchAliases()
	{
		assertEquals("Dragon scimitar", SearchQueryNormalizer.normalize("Item", "d-scim"));
		assertEquals("Dragon dagger(p++)", SearchQueryNormalizer.normalize("Item", "dds"));
		assertEquals("Abyssal whip", SearchQueryNormalizer.normalize("Item", "whip"));
		assertEquals("black dragon scimitar", SearchQueryNormalizer.normalize("Item", "blk drag scim"));
		assertEquals("Abyssal demon", SearchQueryNormalizer.normalize("NPC", "abby-demons"));
	}

	@Test
	public void searchNotFoundShowsRecoveryActionsAndRecentQueryChips() throws Exception
	{
		AtomicReference<String> searchedType = new AtomicReference<>();
		AtomicReference<String> searchedQuery = new AtomicReference<>();

		UiSnapshot snapshot = onEdt(() ->
		{
			InspectPanel panel = new InspectPanel(null, null);
			panel.setSearchHandler((type, query) ->
			{
				searchedType.set(type);
				searchedQuery.set(query);
			});

			panel.showSearchNotFound("Item", "d scim");
			UiSnapshot notFoundSnapshot = UiSnapshot.capture(panel);
			clickButton(panel, "Item: Dragon scimitar");
			panel.showEmpty();
			UiSnapshot recentSnapshot = UiSnapshot.capture(panel);
			return new UiSnapshot(
				notFoundSnapshot.text + recentSnapshot.text,
				notFoundSnapshot.toolTips + recentSnapshot.toolTips,
				notFoundSnapshot.popupActions + recentSnapshot.popupActions,
				notFoundSnapshot.equipmentImageComponentCount + recentSnapshot.equipmentImageComponentCount);
		});

		assertEquals("Item", searchedType.get());
		assertEquals("Dragon scimitar", searchedQuery.get());
		assertTrue(snapshot.text.contains("Open exact Wiki page"));
		assertTrue(snapshot.text.contains("Item: Dragon scimitar"));
		assertTrue(snapshot.text.contains("Item: d scim"));
		assertTrue(snapshot.toolTips.contains("https://oldschool.runescape.wiki/w/d_scim"));
	}

	@Test
	public void searchControlsFitRecentChipsWithoutLargeTrailingGap() throws Exception
	{
		int preferredHeight = onEdt(() ->
		{
			InspectPanel panel = new InspectPanel(null, null);
			panel.setSearchHandler((type, query) ->
			{
			});
			for (String query : Arrays.asList("green dragon", "hespori", "obor", "rune platebody"))
			{
				panel.showSearchNotFound("NPC", query);
				clickButton(panel, "NPC: " + query);
			}
			panel.showEmpty();
			return panel.getComponent(0).getPreferredSize().height;
		});

		assertTrue("Search controls preferred height was " + preferredHeight, preferredHeight <= 160);
	}

	@Test
	public void itemVariantPickerShowsExactIdsAndDispatchesSelectedItem() throws Exception
	{
		AtomicReference<ItemInspectVariant> selectedItem = new AtomicReference<>();
		ItemInspectVariant unpoisoned = variant(1215, "Dragon dagger", "Dragon_dagger", "Unpoisoned");
		ItemInspectVariant poisonPlusPlus = variant(5698, "Dragon dagger(p++)", "Dragon_dagger", "Poison++");

		UiSnapshot snapshot = onEdt(() ->
		{
			InspectPanel panel = new InspectPanel(null, null);
			panel.setItemVariantInspectHandler(selectedItem::set);
			panel.showItemVariantPicker("Dragon dagger", Arrays.asList(unpoisoned, poisonPlusPlus));
			UiSnapshot itemSnapshot = UiSnapshot.capture(panel);
			clickButton(panel, "Dragon dagger(p++)");
			return itemSnapshot;
		});

		assertEquals(poisonPlusPlus, selectedItem.get());
		assertTrue(snapshot.text.contains("Choose item variant"));
		assertTrue(snapshot.text.contains("Poison++ \u00B7 ID 5698"));
	}

	@Test
	public void itemSearchNotFoundMessageWrapsWithinPanel() throws Exception
	{
		JTextArea message = onEdt(() ->
		{
			InspectPanel panel = new InspectPanel(null, null);
			panel.showSearchNotFound("Item", "green dragon");
			return findTextAreaContaining(panel, "No item info was found for green dragon.");
		});

		assertTrue(message.getLineWrap());
		assertTrue(message.getWrapStyleWord());
		assertTrue(message.getPreferredSize().width <= PluginPanel.PANEL_WIDTH - 24);
		assertTrue(message.getPreferredSize().height > 30);
	}

	@Test
	public void clearingSearchPreservesCurrentSearchTypeAndTab() throws Exception
	{
		String searchState = onEdt(() ->
		{
			InspectPanel panel = new InspectPanel(null, null);
			JComboBox<?> type = findComboBox(panel);
			type.setSelectedItem("NPC");
			findIconTextField(panel).setText("green dragon");
			clickButton(panel, "\u00D7");

			String selectedType = String.valueOf(findComboBox(panel).getSelectedItem());
			boolean npcTabActive = ColorScheme.BRAND_ORANGE.equals(findButton(panel, "NPC").getForeground());
			boolean itemTabActive = ColorScheme.BRAND_ORANGE.equals(findButton(panel, "Item").getForeground());
			return selectedType + "," + npcTabActive + "," + itemTabActive;
		});

		assertEquals("NPC,true,false", searchState);
	}

	@Test
	public void cacheClearCompletionKeepsAllCacheButtonsVisible() throws Exception
	{
		UiSnapshot snapshot = onEdt(() ->
		{
			InspectPanel panel = new InspectPanel(null, null);
			panel.setCacheManagementHandler(new InspectPanel.CacheManagementHandler()
			{
				@Override
				public void clearItemCache()
				{
					panel.showCacheManagementStatus("Item Inspect cache cleared.");
				}

				@Override
				public void clearNpcCache()
				{
				}

				@Override
				public void clearAllCache()
				{
				}
			});

			clickButton(panel, "Clear item cache");
			return UiSnapshot.capture(panel);
		});

		assertTrue(snapshot.text.contains("Item Inspect cache cleared."));
		assertTrue(snapshot.text.contains("Clear item cache"));
		assertTrue(snapshot.text.contains("Clear NPC cache"));
		assertTrue(snapshot.text.contains("Clear all Inspect cache"));
	}

	@Test
	public void completedCacheClearDoesNotReplaceAViewOpenedWhileClearing() throws Exception
	{
		UiSnapshot snapshot = onEdt(() ->
		{
			InspectPanel panel = new InspectPanel(null, null);
			panel.showCacheManagementStatus("Clearing Item Inspect cache...");
			panel.showSearchNotFound("Item", "dragon widget");
			panel.updateCacheManagementStatus("Item Inspect cache cleared.");
			return UiSnapshot.capture(panel);
		});

		assertTrue(snapshot.text.contains("No item info was found for dragon widget."));
		assertFalse(snapshot.text.contains("Item Inspect cache cleared."));
		assertFalse(snapshot.text.contains("Clear item cache"));
	}

	@Test
	public void itemAndNpcTabsSelectMatchingSearchType() throws Exception
	{
		String selectedTypes = onEdt(() ->
		{
			InspectPanel panel = new InspectPanel(null, null);
			panel.showSearchNotFound("NPC", "green dragon");

			clickButton(panel, "Item");
			String itemType = String.valueOf(findComboBox(panel).getSelectedItem());

			clickButton(panel, "NPC");
			String npcType = String.valueOf(findComboBox(panel).getSelectedItem());

			return itemType + "," + npcType;
		});

		assertEquals("Item,NPC", selectedTypes);
	}

	@Test
	public void itemSearchResultResetsScrollPositionToTop() throws Exception
	{
		AtomicReference<JScrollPane> scrollPane = new AtomicReference<>();

		onEdt(() ->
		{
			InspectPanel panel = new InspectPanel(null, null);
			JScrollPane pane = new JScrollPane(panel);
			pane.setSize(new Dimension(PluginPanel.PANEL_WIDTH, 120));
			panel.showItemInfo(scrollableItem("Rune platebody"), null, null, null);
			pane.doLayout();
			panel.doLayout();
			pane.getViewport().setViewPosition(new Point(0, 300));
			assertTrue(pane.getViewport().getViewPosition().y > 0);

			panel.showItemInfo(scrollableItem("Rune platelegs"), null, null, null);
			scrollPane.set(pane);
			return null;
		});
		onEdt(() -> null);

		int y = onEdt(() -> scrollPane.get().getViewport().getViewPosition().y);

		assertEquals(0, y);
	}

	@Test
	public void redactsEquipmentDetailsInPvpAreas() throws Exception
	{
		UiSnapshot snapshot = onEdt(() ->
		{
			// A null item manager makes the test fail if the blocked view requests a gear image.
			InspectPanel panel = new InspectPanel(null, null);
			PlayerEquipmentItem equipment = new PlayerEquipmentItem("Weapon", 4151, "Abyssal whip", 2_000_000);
			PlayerInspectAnalysis analysis = PlayerInspectAnalysis.message(
				"2,000,000 coins",
				"Abyssal whip is stronger than your weapon");

			panel.showPlayerEquipment(
				"Opponent",
				126,
				Collections.singletonList(equipment),
				analysis,
				true,
				Collections.emptyList(),
				Collections.emptyList());
			return UiSnapshot.capture(panel);
		});

		assertTrue(snapshot.text.contains("Player equipment inspect is disabled in PvP areas."));
		assertTrue(snapshot.text.contains("Disabled in PvP"));
		assertFalse(snapshot.text.contains("Abyssal whip"));
		assertFalse(snapshot.text.contains("2,000,000 coins"));
		assertFalse(snapshot.text.contains("Visible tags"));
		assertFalse(snapshot.text.contains("Melee"));
		assertFalse(snapshot.toolTips.contains("Abyssal whip"));
		assertFalse(snapshot.popupActions.contains("Inspect item"));
		assertEquals(0, snapshot.equipmentImageComponentCount);
	}

	@Test
	public void rendersVisibleEquipmentDetailsOutsidePvpAreas() throws Exception
	{
		UiSnapshot snapshot = onEdt(() ->
		{
			InspectPanel panel = new InspectPanel(null, null);
			PlayerEquipmentItem equipment = new PlayerEquipmentItem("Weapon", 0, "Abyssal whip", 2_000_000);

			panel.showPlayerEquipment(
				"Teammate",
				100,
				Collections.singletonList(equipment),
				PlayerInspectAnalysis.message("2,000,000 coins", null),
				false,
				Collections.emptyList(),
				Collections.emptyList());
			return UiSnapshot.capture(panel);
		});

		assertTrue(snapshot.text.contains("Visible tags"));
		assertTrue(snapshot.text.contains("Melee"));
		assertTrue(snapshot.text.contains("2,000,000 coins"));
		assertTrue(snapshot.toolTips.contains("Abyssal whip"));
		assertTrue(snapshot.equipmentImageComponentCount > 0);
	}

	@Test
	public void rendersNpcItemRequirementStatuses() throws Exception
	{
		UiSnapshot snapshot = onEdt(() ->
		{
			InspectPanel panel = new InspectPanel(null, null);
			NpcCombatInfo info = NpcCombatInfo.builder()
				.displayName("Gargoyle")
				.itemRequirements(Collections.singletonList(new NpcItemRequirement("Rock hammer or Granite hammer", Arrays.asList("Rock hammer", "Granite hammer"))))
				.build();

			panel.showInfo(info, EquipmentRecommendation.preview(info), null, Collections.singletonList(new NpcItemRequirementStatus(
				info.getItemRequirements().get(0),
				Arrays.asList(
					new NpcItemRequirementAlternativeStatus("Rock hammer", false, null),
					new NpcItemRequirementAlternativeStatus("Granite hammer", true, "Equipped")
				)
			)));
			return UiSnapshot.capture(panel);
		});

		assertTrue(snapshot.text.contains("Required items"));
		assertTrue(snapshot.text.contains("Any one of these"));
		assertTrue(snapshot.text.contains("Rock hammer"));
		assertTrue(snapshot.text.contains("Missing"));
		assertTrue(snapshot.text.contains("OR"));
		assertTrue(snapshot.text.contains("Granite hammer"));
		assertTrue(snapshot.text.contains("Equipped"));
		assertTrue(snapshot.text.contains("Can I kill this?"));
		assertFalse(snapshot.text.contains("Rock hammer or Granite hammer"));
	}

	@Test
	public void rendersAssignedByTagsAsWikiLinks() throws Exception
	{
		UiSnapshot snapshot = onEdt(() ->
		{
			InspectPanel panel = new InspectPanel(null, null);
			NpcCombatInfo info = NpcCombatInfo.builder()
				.displayName("Abyssal demon")
				.assignedBy("Vannaka, Chaeldar, Konar quo Maten")
				.build();

			panel.showInfo(info, EquipmentRecommendation.preview(info), null, Collections.emptyList());
			return UiSnapshot.capture(panel);
		});

		assertTrue(snapshot.text.contains("Assigned by"));
		assertTrue(snapshot.text.contains("Vannaka"));
		assertTrue(snapshot.text.contains("Chaeldar"));
		assertTrue(snapshot.text.contains("Konar Quo Maten"));
		assertTrue(snapshot.toolTips.contains("https://oldschool.runescape.wiki/w/Vannaka"));
		assertTrue(snapshot.toolTips.contains("https://oldschool.runescape.wiki/w/Chaeldar"));
		assertTrue(snapshot.toolTips.contains("https://oldschool.runescape.wiki/w/Konar_quo_Maten"));
	}

	@Test
	public void rendersNpcDropFilterButtons() throws Exception
	{
		UiSnapshot snapshot = onEdt(() ->
		{
			InspectPanel panel = new InspectPanel(null, null);
			NpcCombatInfo info = NpcCombatInfo.builder()
				.displayName("Abyssal demon")
				.valuableDrops("Abyssal whip, Brimstone key")
				.rareDrops("Abyssal whip")
				.slayerOnlyDrops("Brimstone key")
				.clueDrops("Clue scroll (hard)")
				.ironmanDrops("Grimy ranarr weed")
				.alchableDrops("Adamant platebody")
				.upgradeDrops("Abyssal head")
				.build();

			panel.showInfo(info, EquipmentRecommendation.preview(info), null, Collections.emptyList());
			return UiSnapshot.capture(panel);
		});

		assertTrue(snapshot.text.contains("Drop filters"));
		assertTrue(snapshot.text.contains("Valuable"));
		assertTrue(snapshot.text.contains("Rare"));
		assertTrue(snapshot.text.contains("Slayer-only"));
		assertTrue(snapshot.text.contains("Clue"));
		assertTrue(snapshot.text.contains("Ironman"));
		assertTrue(snapshot.text.contains("Alchable"));
		assertTrue(snapshot.text.contains("Upgrade"));
		assertTrue(snapshot.text.contains("Abyssal whip"));
		assertTrue(snapshot.text.contains("Brimstone key"));
		assertFalse(snapshot.text.contains("Abyssal whip, Brimstone key"));
	}

	@Test
	public void switchingNpcDropFiltersRerendersDropRows() throws Exception
	{
		UiSnapshot snapshot = onEdt(() ->
		{
			InspectPanel panel = new InspectPanel(null, null);
			NpcCombatInfo info = NpcCombatInfo.builder()
				.displayName("Abyssal demon")
				.valuableDrops("Abyssal whip, Brimstone key")
				.rareDrops("Abyssal whip")
				.ironmanDrops("Grimy ranarr weed")
				.build();

			panel.showInfo(info, EquipmentRecommendation.preview(info), null, Collections.emptyList());
			clickButton(panel, "Ironman");
			return UiSnapshot.capture(panel);
		});

		assertTrue(snapshot.text.contains("Drop filters"));
		assertTrue(snapshot.text.contains("Ironman"));
		assertTrue(snapshot.text.contains("Grimy ranarr weed"));
		assertFalse(snapshot.text.contains("Abyssal whip"));
		assertFalse(snapshot.text.contains("Brimstone key"));
	}

	@Test
	public void rendersNpcDropRowsFromPrecomputedItemIdsWithoutResolvingDefinitions() throws Exception
	{
		UiSnapshot snapshot = onEdt(() ->
		{
			InspectPanel panel = new InspectPanel(null, null);
			NpcCombatInfo info = NpcCombatInfo.builder()
				.displayName("Abyssal demon")
				.valuableDrops("Abyssal whip")
				.build();
			Map<String, Integer> dropItemIds = new LinkedHashMap<>();
			dropItemIds.put("abyssal whip", 4151);

			panel.showInfo(info, EquipmentRecommendation.preview(info), null, Collections.emptyList(), dropItemIds);
			return UiSnapshot.capture(panel);
		});

		assertTrue(snapshot.text.contains("Drop filters"));
		assertTrue(snapshot.text.contains("Abyssal whip"));
		assertTrue(snapshot.popupActions.contains("Inspect item"));
	}

	@Test
	public void npcDropItemInspectRestoresNpcScrollWhenReturningToNpcTab() throws Exception
	{
		AtomicReference<JScrollPane> scrollPane = new AtomicReference<>();
		AtomicReference<String> inspectedItem = new AtomicReference<>();

		onEdt(() ->
		{
			InspectPanel panel = new InspectPanel(null, null);
			panel.setItemInspectHandler((itemId, itemName) ->
			{
				inspectedItem.set(itemName);
				panel.showItemInfo(scrollableItem(itemName), null, null, null);
			});
			JScrollPane pane = new JScrollPane(panel);
			pane.setSize(new Dimension(PluginPanel.PANEL_WIDTH, 120));
			NpcCombatInfo info = NpcCombatInfo.builder()
				.displayName("Blue dragon")
				.combatLevel("111")
				.hitpoints("105")
				.attack("120")
				.strength("110")
				.defence("100")
				.magic("1")
				.ranged("1")
				.valuableDrops("Abyssal whip")
				.rareDrops("Abyssal whip")
				.build();
			Map<String, Integer> dropItemIds = new LinkedHashMap<>();
			dropItemIds.put("abyssal whip", 4151);
			panel.showInfo(info, EquipmentRecommendation.preview(info), null, Collections.emptyList(), dropItemIds);
			pane.doLayout();
			panel.doLayout();
			pane.getViewport().setViewPosition(new Point(0, 300));
			assertTrue(pane.getViewport().getViewPosition().y > 0);

			clickPopupAction(panel, "Inspect item");
			scrollPane.set(pane);
			return null;
		});
		onEdt(() -> null);

		onEdt(() ->
		{
			InspectPanel panel = (InspectPanel) scrollPane.get().getViewport().getView();
			clickButton(panel, "NPC");
			return null;
		});
		onEdt(() -> null);

		int y = onEdt(() -> scrollPane.get().getViewport().getViewPosition().y);

		assertEquals("Abyssal whip", inspectedItem.get());
		assertTrue(y > 0);
	}

	@Test
	public void bankGearLoadingAndResultsPreserveScrollButNewNpcStartsAtTop() throws Exception
	{
		NpcCombatInfo info = NpcCombatInfo.builder().displayName("Guard").stabDefence("0").build();
		InspectPanel panel = onEdt(() ->
		{
			InspectPanel result = new InspectPanel(null, null);
			JScrollPane pane = new JScrollPane(result);
			pane.setSize(new Dimension(PluginPanel.PANEL_WIDTH, 120));
			result.setGearRecommendationHandler(new InspectPanel.GearRecommendationHandler()
			{
				@Override
				public void findGear(NpcCombatInfo selected)
				{
					result.showInfo(selected, EquipmentRecommendation.preview(selected), "Scanning bank and equipped gear...",
						Collections.emptyList());
				}

				@Override
				public void clearGear(NpcCombatInfo selected)
				{
				}
			});
			result.showInfo(info, EquipmentRecommendation.preview(info), null, Collections.emptyList());
			pane.doLayout();
			result.doLayout();
			return result;
		});
		onEdt(() -> null);
		onEdt(() ->
		{
			((javax.swing.JViewport) panel.getParent()).setViewPosition(new Point(0, 300));
			clickButton(panel, "Find gear in bank");
			return null;
		});
		onEdt(() -> null);
		assertEquals(300, (int) onEdt(() -> ((javax.swing.JViewport) panel.getParent()).getViewPosition().y));
		for (String status : Arrays.asList("Ranked bank gear within each equipment slot.", "Selection cleared.",
			"Unable to load bank item stats.", "No matching equipment found."))
		{
			onEdt(() ->
			{
				panel.showInfo(info, EquipmentRecommendation.preview(info), status, Collections.emptyList());
				return null;
			});
			onEdt(() -> null);
			assertEquals(300, (int) onEdt(() -> ((javax.swing.JViewport) panel.getParent()).getViewPosition().y));
		}
		onEdt(() ->
		{
			panel.showInfo(info.toBuilder().displayName("Goblin").build(), null, null, Collections.emptyList());
			return null;
		});
		onEdt(() -> null);
		assertEquals(0, (int) onEdt(() -> ((javax.swing.JViewport) panel.getParent()).getViewPosition().y));
	}

	@Test
	public void npcRequirementRefreshPreservesScrollPosition() throws Exception
	{
		AtomicReference<JScrollPane> scrollPane = new AtomicReference<>();

		onEdt(() ->
		{
			InspectPanel panel = new InspectPanel(null, null);
			JScrollPane pane = new JScrollPane(panel);
			pane.setSize(new Dimension(PluginPanel.PANEL_WIDTH, 120));
			NpcItemRequirement requirement = new NpcItemRequirement(
				"Rock hammer",
				Collections.singletonList("Rock hammer"));
			NpcCombatInfo info = NpcCombatInfo.builder()
				.displayName("Gargoyle")
				.combatLevel("111")
				.hitpoints("105")
				.attack("120")
				.strength("110")
				.defence("100")
				.magic("1")
				.ranged("1")
				.itemRequirements(Collections.singletonList(requirement))
				.build();
			NpcItemRequirementStatus missing = new NpcItemRequirementStatus(
				requirement,
				Collections.singletonList(new NpcItemRequirementAlternativeStatus("Rock hammer", false, null)));
			panel.showInfo(
				info,
				EquipmentRecommendation.preview(info),
				null,
				Collections.singletonList(missing));
			pane.doLayout();
			panel.doLayout();
			pane.getViewport().setViewPosition(new Point(0, 300));
			assertTrue(pane.getViewport().getViewPosition().y > 0);

			NpcItemRequirementStatus carried = new NpcItemRequirementStatus(
				requirement,
				Collections.singletonList(new NpcItemRequirementAlternativeStatus(
					"Rock hammer",
					true,
					"Rock hammer")));
			panel.refreshNpcInfo(
				info,
				EquipmentRecommendation.preview(info),
				null,
				Collections.singletonList(carried),
				Collections.emptyMap());
			scrollPane.set(pane);
			return null;
		});
		onEdt(() -> null);

		int y = onEdt(() -> scrollPane.get().getViewport().getViewPosition().y);
		String text = onEdt(() -> UiSnapshot.capture(
			(InspectPanel) scrollPane.get().getViewport().getView()).text);

		assertTrue(y > 0);
		assertFalse(text.contains("Missing"));
	}

	@Test
	public void unresolvedNpcDropRowsRenderWithoutInspectPopup() throws Exception
	{
		UiSnapshot snapshot = onEdt(() ->
		{
			InspectPanel panel = new InspectPanel(null, null);
			NpcCombatInfo info = NpcCombatInfo.builder()
				.displayName("Kurask")
				.valuableDrops("Leaf-bladed battleaxe")
				.build();

			panel.showInfo(info, EquipmentRecommendation.preview(info), null, Collections.emptyList(), Collections.emptyMap());
			return UiSnapshot.capture(panel);
		});

		assertTrue(snapshot.text.contains("Drop filters"));
		assertTrue(snapshot.text.contains("Leaf-bladed battleaxe"));
		assertFalse(snapshot.popupActions.contains("Inspect item"));
	}

	@Test
	public void rendersSavedNpcCompareTrayAndComparison() throws Exception
	{
		UiSnapshot snapshot = onEdt(() ->
		{
			InspectPanel panel = new InspectPanel(null, null);
			NpcCombatInfo pinned = NpcCombatInfo.builder()
				.displayName("Gargoyle")
				.combatLevel("111")
				.hitpoints("105")
				.maxHit("11")
				.attack("120")
				.build();
			NpcCombatInfo current = NpcCombatInfo.builder()
				.displayName("Abyssal demon")
				.combatLevel("124")
				.hitpoints("150")
				.maxHit("8")
				.attack("97")
				.build();

			panel.setPinnedInspects(PinnedInspectState.empty().withNpc(pinned));
			panel.showInfo(current, EquipmentRecommendation.preview(current), null, Collections.emptyList());
			return UiSnapshot.capture(panel);
		});

		assertTrue(snapshot.text.contains("Compare"));
		assertTrue(snapshot.text.contains("NPC: Gargoyle"));
		assertTrue(snapshot.text.contains("Compare NPC"));
		assertTrue(snapshot.text.contains("Compared to NPC"));
		assertTrue(snapshot.text.contains("Combat"));
		assertTrue(snapshot.text.contains("+13"));
		assertTrue(snapshot.text.contains("HP"));
		assertTrue(snapshot.text.contains("+45"));
		assertTrue(snapshot.text.contains("Max hit"));
		assertTrue(snapshot.text.contains("-3"));
	}

	@Test
	public void rendersSavedItemCompareTrayAndComparison() throws Exception
	{
		UiSnapshot snapshot = onEdt(() ->
		{
			InspectPanel panel = new InspectPanel(null, null);
			ItemInspectInfo pinned = ItemInspectInfo.builder()
				.itemId(100)
				.displayName("Rune scimitar")
				.attackSlash("45")
				.strength("44")
				.build();
			ItemInspectInfo current = ItemInspectInfo.builder()
				.itemId(101)
				.displayName("Dragon scimitar")
				.attackSlash("67")
				.strength("66")
				.build();

			panel.setPinnedInspects(PinnedInspectState.empty().withItem(pinned));
			panel.showItemInfo(current, null, null, null);
			return UiSnapshot.capture(panel);
		});

		assertTrue(snapshot.text.contains("Compare"));
		assertTrue(snapshot.text.contains("Item: Rune scimitar"));
		assertTrue(snapshot.text.contains("Compare item"));
		assertTrue(snapshot.text.contains("Compared to item"));
		assertTrue(snapshot.text.contains("Slash attack"));
		assertTrue(snapshot.text.contains("Strength"));
		assertTrue(snapshot.text.contains("+22"));
	}

	@Test
	public void rendersItemSourcesAsSingleDetailedSection() throws Exception
	{
		UiSnapshot snapshot = onEdt(() ->
		{
			InspectPanel panel = new InspectPanel(null, null);
			ItemInspectInfo item = ItemInspectInfo.builder()
				.itemId(1079)
				.displayName("Rune platelegs")
				.examine("A pair of platelegs made from runite.")
				.defenceSlash("+51")
				.sourceSummary("Skilling, Quests")
				.sourcePlan(Arrays.asList(
					new ItemSource(
						"Skilling",
						Collections.singletonList("They can be created with level 99 Smithing and 3 runite bars."),
						Collections.singletonList(new ItemSourceRequirement("Smithing", 99, "Skilling"))),
					new ItemSource(
						"Quests",
						Collections.singletonList("It requires 40 Defence and completion of Dragon Slayer I to equip."),
						Collections.singletonList(new ItemSourceRequirement("Defence", 40, "Quests")))
				))
				.questRequirements("Dragon Slayer I")
				.build();

			panel.showItemInfo(item, null, null, null);
			return UiSnapshot.capture(panel);
		});

		assertEquals(1, countOccurrences(snapshot.text, "Sources"));
		assertTrue(snapshot.text.contains("Skilling"));
		assertTrue(snapshot.text.contains("They can be created with level 99 Smithing and 3 runite bars."));
		assertTrue(snapshot.text.contains("Quests"));
		assertTrue(snapshot.text.contains("It requires 40 Defence and completion of Dragon Slayer I to equip."));
		assertTrue(snapshot.text.indexOf("Defence bonuses") < snapshot.text.indexOf("Sources"));
		assertTrue(snapshot.text.indexOf("Sources") < snapshot.text.indexOf("Examine"));
		assertFalse(snapshot.text.contains("How to get"));
		assertFalse(snapshot.text.contains("Unlock notes"));
	}

	@Test
	public void rendersAccountAwareSourceStatusInIronmanPriorityOrder() throws Exception
	{
		UiSnapshot snapshot = onEdt(() ->
		{
			InspectPanel panel = new InspectPanel(null, null);
			ItemSource trading = new ItemSource(
				"Trading",
				Collections.singletonList("Purchased from the Grand Exchange."),
				Collections.emptyList());
			ItemSource skilling = new ItemSource(
				"Skilling",
				Collections.singletonList("Created with level 99 Smithing."),
				Collections.singletonList(new ItemSourceRequirement("Smithing", 99, "Skilling")));
			ItemSource quests = new ItemSource(
				"Quests",
				Collections.singletonList("Received as a reward after completing the Lost City quest."),
				Collections.emptyList());
			ItemInspectInfo item = ItemInspectInfo.builder()
				.itemId(1215)
				.displayName("Dragon dagger")
				.sourcePlan(Arrays.asList(trading, skilling, quests))
				.build();
			Map<Skill, Integer> skillLevels = new EnumMap<>(Skill.class);
			skillLevels.put(Skill.SMITHING, 80);
			Map<Quest, QuestState> questStates = new EnumMap<>(Quest.class);
			questStates.put(Quest.LOST_CITY, QuestState.FINISHED);
			ItemSourceReadiness readiness = ItemSourceReadinessEvaluator.evaluate(
				item.getSourcePlan(),
				skillLevels,
				questStates,
				ItemSourceAccountMode.IRONMAN);

			panel.showItemInfo(item, null, null, null, readiness);
			return UiSnapshot.capture(panel);
		});

		assertTrue(snapshot.text.contains(
			"Ironman account: available methods with met requirements are shown first."));
		assertTrue(snapshot.text.contains("Lost City quest"));
		assertTrue(snapshot.text.contains("Smithing 99 (80)"));
		assertTrue(snapshot.text.contains("Not available to Ironman accounts"));
		assertTrue(snapshot.text.indexOf("Quests") < snapshot.text.indexOf("Skilling"));
		assertTrue(snapshot.text.indexOf("Skilling") < snapshot.text.indexOf("Trading"));
	}

	@Test
	public void rendersItemRequirementsAsSingleReadinessSection() throws Exception
	{
		UiSnapshot snapshot = onEdt(() ->
		{
			InspectPanel panel = new InspectPanel(null, null);
			ItemInspectInfo item = ItemInspectInfo.builder()
				.itemId(1127)
				.displayName("Rune platebody")
				.requirementDefence("40")
				.build();
			ItemRequirementSummary requirements = new ItemRequirementSummary(
				Collections.singletonList("Defence 40 (81)"),
				Collections.singletonList("Smithing 99 for skilling (71)")
			);

			panel.showItemInfo(item, null, requirements, null);
			return UiSnapshot.capture(panel);
		});

		assertEquals(1, countOccurrences(snapshot.text, "Requirements"));
		assertTrue(snapshot.text.contains("Met"));
		assertTrue(snapshot.text.contains("Defence 40 (81)"));
		assertTrue(snapshot.text.contains("Missing"));
		assertTrue(snapshot.text.contains("Smithing 99 for skilling (71)"));
		assertFalse(snapshot.text.contains("Requirement check"));
	}

	@Test
	public void rendersSavedPlayerCompareTrayAndComparison() throws Exception
	{
		UiSnapshot snapshot = onEdt(() ->
		{
			InspectPanel panel = new InspectPanel(null, null);
			PlayerEquipmentItem pinnedWeapon = new PlayerEquipmentItem("Weapon", 0, "Rune scimitar", 15_000);
			PlayerEquipmentItem currentWeapon = new PlayerEquipmentItem("Weapon", 0, "Dragon scimitar", 60_000);

			panel.setPinnedInspects(PinnedInspectState.empty().withPlayer("Pinned player", 90, Collections.singletonList(pinnedWeapon)));
			panel.showPlayerEquipment(
				"Current player",
				100,
				Collections.singletonList(currentWeapon),
				PlayerInspectAnalysis.message("60,000 coins", null),
				false,
				Collections.emptyList(),
				Collections.emptyList());
			return UiSnapshot.capture(panel);
		});

		assertTrue(snapshot.text.contains("Compare"));
		assertTrue(snapshot.text.contains("Player: Pinned player"));
		assertTrue(snapshot.text.contains("Compare player"));
		assertTrue(snapshot.text.contains("Compared to player"));
		assertTrue(snapshot.text.contains("Combat"));
		assertTrue(snapshot.text.contains("+10"));
		assertTrue(snapshot.text.contains("Gear value"));
		assertTrue(snapshot.text.contains("+45000"));
		assertTrue(snapshot.text.contains("Different"));
		assertTrue(snapshot.text.contains("Weapon"));
	}

	@Test
	public void equipmentRecommendationsShowSlotRanksStatsAndInspectableItems() throws Exception
	{
		onEdt(() ->
		{
			InspectPanel panel = new InspectPanel(null, null);
			com.inspect.npc.EquipmentScore score = new com.inspect.npc.EquipmentScore("Stab", 20d, "Strength", 10d, 0d);
			NpcCombatInfo npc = NpcCombatInfo.builder().displayName("Guard").stabDefence("0").build();
			EquipmentRecommendation recommendation = new EquipmentRecommendation(npc, com.inspect.npc.CombatStyleRecommendation.STAB,
				Arrays.asList(new EquipmentRecommendation.RecommendedItem(10, "Spear", "Weapon", 35d, true, false, 1, score, true),
					new EquipmentRecommendation.RecommendedItem(11, "Helmet", "Head", 35d, false, true, 1, score, false)));
			AtomicInteger inspected = new AtomicInteger();
			panel.setItemInspectHandler((id, name) -> inspected.set(id));
			panel.showInfo(npc, recommendation, "Ranked bank gear within each equipment slot.", Collections.emptyList());
			UiSnapshot snapshot = UiSnapshot.capture(panel);
			assertTrue(snapshot.text.contains("Weapon"));
			assertTrue(snapshot.text.contains("Head"));
			assertTrue(snapshot.text.contains("1. Spear (2h)"));
			assertTrue(snapshot.text.contains("1. Helmet"));
			assertTrue(snapshot.text.contains("Stab +20 · Strength +10 · Prayer 0"));
			assertTrue(snapshot.text.contains("Score 35 (equipped)"));
			assertTrue(snapshot.text.contains("Two-handed weapons also occupy the shield slot"));
			clickButton(panel, "1. Spear (2h)");
			assertEquals(10, inspected.get());
			return null;
		});
	}

	@Test
	public void npcPickerLoadsThumbnailWithoutBlockingSelection() throws Exception
	{
		java.util.concurrent.CompletableFuture<java.awt.image.BufferedImage> loaded = new java.util.concurrent.CompletableFuture<>();
		AtomicReference<NpcCombatInfo> selected = new AtomicReference<>();
		InspectPanel panel = onEdt(() ->
		{
			InspectPanel result = new InspectPanel(null, null);
			result.setNpcThumbnailLoader(file ->
			{
				assertEquals("Guard.png", file);
				return loaded;
			});
			result.setNpcChoiceHandler(selected::set);
			NpcCombatInfo choice = NpcCombatInfo.builder().npcId(10).wikiPage("Guard").displayName("Guard")
				.imageFile("Guard.png").build();
			result.showNpcChoices(new NpcSearchResults("guard", Collections.singletonList(choice), 1000L, false));
			clickButton(result, "Guard");
			assertEquals(choice, selected.get());
			return result;
		});
		loaded.complete(new java.awt.image.BufferedImage(38, 38, java.awt.image.BufferedImage.TYPE_INT_ARGB));
		onEdt(() ->
		{
			java.awt.Container row = findButton(panel, "Guard").getParent();
			JLabel icon = (JLabel) row.getComponent(0);
			assertEquals(38, icon.getIcon().getIconWidth());
			assertEquals("", icon.getText());
			return null;
		});
	}

	@Test
	public void npcPickerShowsVariantContextAndSelectsExactResult() throws Exception
	{
		onEdt(() ->
		{
			InspectPanel panel = new InspectPanel(null, null);
			NpcCombatInfo first = NpcCombatInfo.builder().npcId(10).wikiPage("Guard").displayName("Guard")
				.wikiAnchor("Varrock").combatLevel("21").build();
			NpcCombatInfo second = NpcCombatInfo.builder().npcId(11).wikiPage("Guard").displayName("Guard")
				.wikiAnchor("Falador (sword, ornate armour)").combatLevel("22").cachedFallback(true).build();
			AtomicReference<NpcCombatInfo> selected = new AtomicReference<>();
			panel.setNpcChoiceHandler(selected::set);
			panel.showInfo(first, null, null, Collections.emptyList());
			assertTrue(panel.isShowingNpc(first));
			panel.showNpcChoices(new NpcSearchResults("guard", Arrays.asList(first, second), 1000L, true));
			assertFalse(panel.isShowingNpc(first));
			UiSnapshot snapshot = UiSnapshot.capture(panel);
			assertTrue(snapshot.text.contains("Choose NPC"));
			assertTrue(snapshot.text.contains("Varrock"));
			assertTrue(snapshot.text.contains("Falador"));
			assertTrue(snapshot.text.contains("Combat 22"));
			assertTrue(snapshot.text.contains("ID 11"));
			assertTrue(snapshot.text.contains("may be incomplete"));
			assertTrue(snapshot.text.contains("Showing saved data"));
			assertEquals(46, findButton(panel, "Guard - Falador (sword, ornate armour)").getParent().getPreferredSize().height);
			clickButton(panel, "Guard - Falador (sword, ornate armour)");
			assertEquals(second, selected.get());
			java.awt.Container row = findButton(panel, "Guard - Falador (sword, ornate armour)").getParent();
			row.setSize(row.getPreferredSize());
			row.doLayout();
			assertTrue(row.getComponent(0).getX() >= 0);
			assertTrue(row.getComponent(1).getWidth() > 0);
			assertTrue(row.getComponent(1).getX() + row.getComponent(1).getWidth() <= row.getWidth());
			long revision = panel.getViewRevision();
			panel.showItemLoading("Another item");
			assertTrue(panel.getViewRevision() > revision);
			return null;
		});
	}

	@Test
	public void showsFetchedTimeAndFallbackWarningAndRefreshesSelectedItem() throws Exception
	{
		onEdt(() ->
		{
			InspectPanel panel = new InspectPanel(null, null);
			ItemInspectInfo info = ItemInspectInfo.builder().displayName("Saved item")
				.wikiAnchor("Exact_variant").fetchedAtEpochSecond(1000L).cachedFallback(true).build();
			AtomicReference<ItemInspectInfo> selected = new AtomicReference<>();
			panel.setItemRefreshHandler(selected::set);
			panel.showItemInfo(info, null, null, null);
			UiSnapshot snapshot = UiSnapshot.capture(panel);
			assertTrue(snapshot.text.contains("Wiki data fetched:"));
			assertTrue(snapshot.text.contains("1970"));
			assertTrue(snapshot.text.contains("Showing saved data"));
			AbstractButton button = findButton(panel, "Refresh this item");
			button.doClick();
			assertEquals(info, selected.get());
			assertFalse(button.isEnabled());
			return null;
		});
	}

	@Test
	public void refreshesSelectedNpcAndDoesNotWarnForSuccessfulData() throws Exception
	{
		onEdt(() ->
		{
			InspectPanel panel = new InspectPanel(null, null);
			NpcCombatInfo info = NpcCombatInfo.builder().displayName("Saved NPC")
				.wikiAnchor("Exact_variant").fetchedAtEpochSecond(1000L).build();
			AtomicReference<NpcCombatInfo> selected = new AtomicReference<>();
			panel.setNpcRefreshHandler(selected::set);
			panel.showInfo(info, null, null, Collections.emptyList());
			assertFalse(UiSnapshot.capture(panel).text.contains("Showing saved data"));
			findButton(panel, "Refresh this NPC").doClick();
			assertEquals(info, selected.get());
			return null;
		});
	}

	@Test
	public void rendersAlchLossAboveIntegerRange() throws Exception
	{
		UiSnapshot snapshot = onEdt(() ->
		{
			InspectPanel panel = new InspectPanel(null, null);
			panel.showItemInfo(ItemInspectInfo.builder().displayName("Expensive item").build(), null, null,
				new ItemPriceSummary("3,000,000,000 gp", "60,000 gp", "40,000 gp",
					"-2,999,940,000 gp", -2_999_940_000L, "100 gp", "1 nature rune; unlimited fire runes equipped."));
			return UiSnapshot.capture(panel);
		});

		assertTrue(snapshot.text.contains("3,000,000,000 gp"));
		assertTrue(snapshot.text.contains("HA loss"));
		assertTrue(snapshot.text.contains("-2,999,940,000 gp"));
	}

	@Test
	public void updatesCastingCostsWithoutReplacingAnotherView() throws Exception
	{
		onEdt(() ->
		{
			InspectPanel panel = new InspectPanel(null, null);
			ItemInspectInfo info = ItemInspectInfo.builder().displayName("Test item").build();
			ItemPriceSummary original = new ItemPriceSummary("450 gp", "600 gp", "400 gp", "+25 gp", 25L,
				"125 gp", "1 nature rune + 5 fire runes.");
			ItemPriceSummary equipped = new ItemPriceSummary("450 gp", "600 gp", "400 gp", "+50 gp", 50L,
				"100 gp", "1 nature rune; unlimited fire runes equipped.");
			panel.showItemInfo(info, null, null, original);
			panel.updateItemPrices(info, equipped);
			assertTrue(UiSnapshot.capture(panel).text.contains("100 gp"));
			assertTrue(UiSnapshot.capture(panel).text.contains("unlimited fire runes equipped"));
			panel.showItemLoading("Another item");
			long revision = panel.getViewRevision();
			panel.updateItemPrices(info, original);
			assertEquals(revision, panel.getViewRevision());
			assertFalse(UiSnapshot.capture(panel).text.contains("125 gp"));
			panel.showItemInfo(ItemInspectInfo.builder().displayName("Different item").build(), null, null, original);
			panel.updateItemPrices(info, equipped);
			assertFalse(UiSnapshot.capture(panel).text.contains("unlimited fire runes equipped"));
			return null;
		});
	}

	@Test
	public void comparesPlayerGearValuesAboveIntegerRange() throws Exception
	{
		UiSnapshot snapshot = onEdt(() ->
		{
			InspectPanel panel = new InspectPanel(null, null);
			PlayerEquipmentItem pinnedWeapon = new PlayerEquipmentItem("Weapon", 0, "Pinned weapon", 1_000_000_000L);
			java.util.List<PlayerEquipmentItem> equipment = Arrays.asList(
				new PlayerEquipmentItem("Weapon", 0, "Current weapon", 3_000_000_000L),
				new PlayerEquipmentItem("Shield", 0, "Current shield", 2_000_000_000L));
			panel.setPinnedInspects(PinnedInspectState.empty().withPlayer("Pinned player", 90, Collections.singletonList(pinnedWeapon)));
			panel.showPlayerEquipment("Current player", 100, equipment,
				PlayerInspectAnalysis.message("5,000,000,000 gp", null), false,
				Collections.emptyList(), Collections.emptyList());
			return UiSnapshot.capture(panel);
		});

		assertTrue(snapshot.text.contains("+4000000000"));
	}

	@Test
	public void formatsElementalWeaknessAsPercentage()
	{
		assertEquals("40%", InspectPanel.elementalWeaknessPercentage("40"));
		assertEquals("40%", InspectPanel.elementalWeaknessPercentage("40%"));
		assertEquals(null, InspectPanel.elementalWeaknessPercentage("No elemental weakness"));
	}

	@Test
	public void selectsRuneIconForElementalWeaknessType()
	{
		assertEquals(ItemID.AIRRUNE, InspectPanel.elementalWeaknessRune("air"));
		assertEquals(ItemID.WATERRUNE, InspectPanel.elementalWeaknessRune("Water"));
		assertEquals(ItemID.EARTHRUNE, InspectPanel.elementalWeaknessRune("EARTH"));
		assertEquals(ItemID.FIRERUNE, InspectPanel.elementalWeaknessRune("fire"));
		assertEquals(ItemID.BLANKRUNE_HIGH, InspectPanel.elementalWeaknessRune("unknown"));
	}

	@Test
	public void rendersElementalWeaknessTypeAndPercentageInNpcSummary() throws Exception
	{
		UiSnapshot snapshot = onEdt(() ->
		{
			InspectPanel panel = new InspectPanel(null, null);
			NpcCombatInfo info = NpcCombatInfo.builder()
				.displayName("Lesser demon")
				.elementalWeaknessType("water")
				.elementalWeakness("40")
				.build();

			panel.showInfo(info, EquipmentRecommendation.preview(info), null, Collections.emptyList());
			return UiSnapshot.capture(panel);
		});

		assertTrue(snapshot.text.contains("Weakness summary"));
		assertTrue(snapshot.text.contains("Elemental weakness"));
		assertTrue(snapshot.text.contains("Water 40% weakness"));
	}

	@Test
	public void clickingCompareRowsReopensSavedSelections() throws Exception
	{
		AtomicReference<NpcCombatInfo> openedNpc = new AtomicReference<>();
		AtomicReference<ItemInspectInfo> openedItem = new AtomicReference<>();
		AtomicReference<String> openedPlayer = new AtomicReference<>();
		AtomicInteger clearedNpc = new AtomicInteger();

		onEdt(() ->
		{
			InspectPanel panel = new InspectPanel(null, null);
			NpcCombatInfo pinnedNpc = NpcCombatInfo.builder()
				.displayName("Gargoyle")
				.combatLevel("111")
				.build();
			ItemInspectInfo pinnedItem = ItemInspectInfo.builder()
				.itemId(100)
				.displayName("Rune scimitar")
				.build();
			PlayerEquipmentItem pinnedWeapon = new PlayerEquipmentItem("Weapon", 0, "Rune scimitar", 15_000);
			NpcCombatInfo current = NpcCombatInfo.builder()
				.displayName("Abyssal demon")
				.combatLevel("124")
				.build();

			panel.setPinnedInspectHandler(new InspectPanel.PinnedInspectHandler()
			{
				@Override
				public void pinNpc(NpcCombatInfo info)
				{
				}

				@Override
				public void pinItem(ItemInspectInfo info)
				{
				}

				@Override
				public void pinPlayer(String playerName, int combatLevel, java.util.List<PlayerEquipmentItem> equipment)
				{
				}

				@Override
				public void openNpc(NpcCombatInfo info)
				{
					openedNpc.set(info);
				}

				@Override
				public void openItem(ItemInspectInfo info)
				{
					openedItem.set(info);
				}

				@Override
				public void openPlayer(String playerName, int combatLevel, java.util.List<PlayerEquipmentItem> equipment)
				{
					openedPlayer.set(playerName);
				}

				@Override
				public void clearNpc()
				{
					clearedNpc.incrementAndGet();
				}

				@Override
				public void clearItem()
				{
				}

				@Override
				public void clearPlayer()
				{
				}
			});
			panel.setPinnedInspects(PinnedInspectState.empty()
				.withNpc(pinnedNpc)
				.withItem(pinnedItem)
				.withPlayer("Pinned player", 90, Collections.singletonList(pinnedWeapon)));
			panel.showInfo(current, EquipmentRecommendation.preview(current), null, Collections.emptyList());

			clickButton(panel, "NPC: Gargoyle");
			clickButton(panel, "Item: Rune scimitar");
			clickButton(panel, "Player: Pinned player");
			clickButton(panel, "X");
			return null;
		});

		assertEquals("Gargoyle", openedNpc.get().getDisplayName());
		assertEquals("Rune scimitar", openedItem.get().getDisplayName());
		assertEquals("Pinned player", openedPlayer.get());
		assertEquals(1, clearedNpc.get());
	}

	private static <T> T onEdt(Callable<T> action) throws Exception
	{
		AtomicReference<T> result = new AtomicReference<>();
		AtomicReference<Throwable> failure = new AtomicReference<>();
		SwingUtilities.invokeAndWait(() ->
		{
			try
			{
				result.set(action.call());
			}
			catch (Throwable throwable)
			{
				failure.set(throwable);
			}
		});

		if (failure.get() != null)
		{
			throw new AssertionError("Swing action failed", failure.get());
		}
		return result.get();
	}

	private static void clickButton(Component root, String text)
	{
		AbstractButton button = findButton(root, text);
		if (button == null)
		{
			throw new AssertionError("Button not found: " + text);
		}
		button.doClick();
	}

	private static ItemInspectVariant variant(int id, String name, String page, String anchor)
	{
		return new ItemInspectVariant(id, name, page, anchor, "https://wiki.test/w/" + page);
	}

	private static void clickPopupAction(Component root, String text)
	{
		AbstractButton button = findPopupAction(root, text);
		if (button == null)
		{
			throw new AssertionError("Popup action not found: " + text);
		}
		button.doClick();
	}

	private static AbstractButton findButton(Component root, String text)
	{
		Deque<Component> components = new ArrayDeque<>();
		components.add(root);
		while (!components.isEmpty())
		{
			Component component = components.removeFirst();
			if (component instanceof AbstractButton && text.equals(((AbstractButton) component).getText()))
			{
				return (AbstractButton) component;
			}
			if (component instanceof Container)
			{
				Collections.addAll(components, ((Container) component).getComponents());
			}
		}
		return null;
	}

	private static AbstractButton findPopupAction(Component root, String text)
	{
		Deque<Component> components = new ArrayDeque<>();
		components.add(root);
		while (!components.isEmpty())
		{
			Component component = components.removeFirst();
			if (component instanceof JComponent)
			{
				JPopupMenu popupMenu = ((JComponent) component).getComponentPopupMenu();
				if (popupMenu != null)
				{
					components.addLast(popupMenu);
				}
			}
			if (component instanceof AbstractButton && text.equals(((AbstractButton) component).getText()))
			{
				return (AbstractButton) component;
			}
			if (component instanceof Container)
			{
				Collections.addAll(components, ((Container) component).getComponents());
			}
		}
		return null;
	}

	private static JTextArea findTextAreaContaining(Component root, String text)
	{
		Deque<Component> components = new ArrayDeque<>();
		components.add(root);
		while (!components.isEmpty())
		{
			Component component = components.removeFirst();
			if (component instanceof JTextArea && ((JTextArea) component).getText().contains(text))
			{
				return (JTextArea) component;
			}
			if (component instanceof Container)
			{
				Collections.addAll(components, ((Container) component).getComponents());
			}
		}
		throw new AssertionError("Text area not found: " + text);
	}

	private static JComboBox<?> findComboBox(Component root)
	{
		Deque<Component> components = new ArrayDeque<>();
		components.add(root);
		while (!components.isEmpty())
		{
			Component component = components.removeFirst();
			if (component instanceof JComboBox)
			{
				return (JComboBox<?>) component;
			}
			if (component instanceof Container)
			{
				Collections.addAll(components, ((Container) component).getComponents());
			}
		}
		throw new AssertionError("Combo box not found");
	}

	private static IconTextField findIconTextField(Component root)
	{
		Deque<Component> components = new ArrayDeque<>();
		components.add(root);
		while (!components.isEmpty())
		{
			Component component = components.removeFirst();
			if (component instanceof IconTextField)
			{
				return (IconTextField) component;
			}
			if (component instanceof Container)
			{
				Collections.addAll(components, ((Container) component).getComponents());
			}
		}
		throw new AssertionError("Search field not found");
	}

	private static ItemInspectInfo scrollableItem(String name)
	{
		return ItemInspectInfo.builder()
			.itemId(1079)
			.displayName(name)
			.members("Yes")
			.tradeable("Yes")
			.equipable("Yes")
			.stackable("No")
			.noteable("Yes")
			.weight("9.071 kg")
			.slot("Legs")
			.attackSpeed("4 ticks")
			.attackRange("1")
			.attackStab("+1")
			.attackSlash("+2")
			.attackCrush("+3")
			.attackMagic("-21")
			.attackRanged("-7")
			.defenceStab("+51")
			.defenceSlash("+49")
			.defenceCrush("+47")
			.defenceMagic("-4")
			.defenceRanged("+48")
			.strength("+1")
			.prayer("+1")
			.questRequirements("Dragon Slayer I")
			.sourcePlan(Collections.singletonList(new ItemSource(
				"Skilling",
				Collections.singletonList("They can be created with level 99 Smithing and 3 runite bars."),
				Collections.singletonList(new ItemSourceRequirement("Smithing", 99, "Skilling")))))
			.examine("A pair of platelegs made from runite.")
			.build();
	}

	private static int countOccurrences(String value, String needle)
	{
		int count = 0;
		int index = 0;
		while ((index = value.indexOf(needle, index)) >= 0)
		{
			count++;
			index += needle.length();
		}
		return count;
	}

	private static final class UiSnapshot
	{
		private final String text;
		private final String toolTips;
		private final String popupActions;
		private final int equipmentImageComponentCount;

		private UiSnapshot(String text, String toolTips, String popupActions, int equipmentImageComponentCount)
		{
			this.text = text;
			this.toolTips = toolTips;
			this.popupActions = popupActions;
			this.equipmentImageComponentCount = equipmentImageComponentCount;
		}

		private static UiSnapshot capture(Component root)
		{
			StringBuilder text = new StringBuilder();
			StringBuilder toolTips = new StringBuilder();
			StringBuilder popupActions = new StringBuilder();
			int equipmentImageComponentCount = 0;
			Deque<Component> components = new ArrayDeque<>();
			components.add(root);

			while (!components.isEmpty())
			{
				Component component = components.removeFirst();
				if (component instanceof JLabel)
				{
					append(text, ((JLabel) component).getText());
				}
				else if (component instanceof AbstractButton)
				{
					append(text, ((AbstractButton) component).getText());
				}
				else if (component instanceof JTextArea)
				{
					append(text, ((JTextArea) component).getText());
				}

				if (component instanceof JComponent)
				{
					JComponent swingComponent = (JComponent) component;
					append(toolTips, swingComponent.getToolTipText());
					JPopupMenu popupMenu = swingComponent.getComponentPopupMenu();
					if (popupMenu != null)
					{
						components.addLast(popupMenu);
					}
				}

				if (component instanceof JPopupMenu)
				{
					for (Component child : ((JPopupMenu) component).getComponents())
					{
						if (child instanceof AbstractButton)
						{
							append(popupActions, ((AbstractButton) child).getText());
						}
					}
				}

				if ("EquipmentSlotComponent".equals(component.getClass().getSimpleName()))
				{
					equipmentImageComponentCount++;
				}

				if (component instanceof Container)
				{
					Collections.addAll(components, ((Container) component).getComponents());
				}
			}

			return new UiSnapshot(text.toString(), toolTips.toString(), popupActions.toString(), equipmentImageComponentCount);
		}

		private static void append(StringBuilder destination, String value)
		{
			if (value != null)
			{
				destination.append('\n').append(value);
			}
		}
	}
}
