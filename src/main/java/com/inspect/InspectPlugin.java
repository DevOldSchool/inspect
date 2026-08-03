package com.inspect;

import com.inspect.item.ItemInspectInfo;
import com.inspect.item.ItemInspectVariant;
import com.inspect.item.ItemInspectVariantSelector;
import com.inspect.item.ItemPriceSummary;
import com.inspect.item.ItemRequirementSummary;
import com.inspect.item.ItemInspectService;
import com.inspect.item.ItemSourceAccountMode;
import com.inspect.item.ItemSourceReadiness;
import com.inspect.item.ItemSourceReadinessEvaluator;
import com.inspect.inspect.InspectPanel;
import com.inspect.inspect.PinnedInspectState;
import com.inspect.inspect.RecentInspectEntry;
import com.inspect.inspect.SearchQueryNormalizer;
import com.inspect.npc.BankEquipmentOverlay;
import com.inspect.npc.BankEquipmentRecommendationService;
import com.inspect.npc.EquipmentRecommendation;
import com.inspect.npc.NpcCombatInfo;
import com.inspect.npc.NpcItemRequirement;
import com.inspect.npc.NpcItemRequirementAlternativeStatus;
import com.inspect.npc.NpcItemRequirementStatus;
import com.inspect.npc.NpcInspectService;
import com.inspect.player.PlayerEquipmentComparison;
import com.inspect.player.PlayerEquipmentItem;
import com.inspect.player.PlayerInspectAnalysis;
import com.google.inject.Provides;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import javax.inject.Inject;
import javax.swing.SwingUtilities;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.EquipmentInventorySlot;
import net.runelite.api.GameState;
import net.runelite.api.Item;
import net.runelite.api.ItemComposition;
import net.runelite.api.ItemContainer;
import net.runelite.api.MenuAction;
import net.runelite.api.NPC;
import net.runelite.api.NPCComposition;
import net.runelite.api.Player;
import net.runelite.api.PlayerComposition;
import net.runelite.api.Quest;
import net.runelite.api.QuestState;
import net.runelite.api.Skill;
import net.runelite.api.WorldType;
import net.runelite.api.events.ClientTick;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.events.MenuEntryAdded;
import net.runelite.api.events.MenuOpened;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.gameval.ItemID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.api.kit.KitType;
import net.runelite.api.widgets.Widget;
import net.runelite.api.widgets.WidgetUtil;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.game.ItemManager;
import net.runelite.client.menus.MenuManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.client.util.Text;
import net.runelite.http.api.item.ItemPrice;

@Slf4j
@PluginDescriptor(
	name = "Inspect",
	description = "Enhances OSRS gameplay with optional inspect information tools.",
	tags = {"npc", "item", "equipment", "inspect", "wiki"}
)
public class InspectPlugin extends Plugin
{
	private static final String CONFIG_GROUP = "inspect";
	private static final String INSPECT = "Inspect";
	private static final String PLAYER_INSPECT = "Inspect";
	private static final int INVENTORY_EXAMINE_ITEM_ACTION_ID = 1005;

	@Inject
	private Client client;

	@Inject
	private ClientToolbar clientToolbar;

	@Inject
	private ClientThread clientThread;

	@Inject
	private InspectConfig config;

	@Inject
	private NpcInspectService npcInspectService;

	@Inject
	private ItemInspectService itemInspectService;

	@Inject
	private BankEquipmentRecommendationService bankEquipmentRecommendationService;

	@Inject
	private BankEquipmentOverlay bankEquipmentOverlay;

	@Inject
	private ItemManager itemManager;

	@Inject
	private OverlayManager overlayManager;

	@Inject
	private MenuManager menuManager;

	private NavigationButton inspectNavButton;
	private InspectPanel inspectPanel;
	private MenuMarker lastNpcInspectMenu = MenuMarker.empty();
	private MenuMarker lastItemInspectMenu = MenuMarker.empty();
	private NpcCombatInfo currentNpcInfo;
	private EquipmentRecommendation currentNpcRecommendation;
	private String currentNpcRecommendationMessage;
	private Map<String, Integer> currentNpcDropItemIds = Collections.emptyMap();
	private PinnedInspectState pinnedInspectState = PinnedInspectState.empty();
	private final Deque<String> recentNpcInspects = new ArrayDeque<>();
	private final Deque<String> recentPlayerInspects = new ArrayDeque<>();
	private final Deque<RecentInspectEntry> recentItemInspects = new ArrayDeque<>();

	@Override
	protected void startUp()
	{
		inspectPanel = injector.getInstance(InspectPanel.class);
		inspectPanel.setPinnedInspects(pinnedInspectState);
		inspectPanel.setSearchHandler(this::searchInspect);
		inspectPanel.setItemVariantInspectHandler(this::inspectItemVariant);
		inspectPanel.setItemInspectHandler(this::inspectPlayerEquipmentItem);
		inspectPanel.setPinnedInspectHandler(new InspectPanel.PinnedInspectHandler()
		{
			@Override
			public void pinNpc(NpcCombatInfo info)
			{
				updatePinnedInspects(pinnedInspectState.withNpc(info));
			}

			@Override
			public void pinItem(ItemInspectInfo info)
			{
				updatePinnedInspects(pinnedInspectState.withItem(info));
			}

			@Override
			public void pinPlayer(String playerName, int combatLevel, List<PlayerEquipmentItem> equipment)
			{
				updatePinnedInspects(pinnedInspectState.withPlayer(playerName, combatLevel, equipment));
			}

			@Override
			public void openNpc(NpcCombatInfo info)
			{
				showNpcInfoWithLocalChecks(info, true);
			}

			@Override
			public void openItem(ItemInspectInfo info)
			{
				showPinnedItem(info);
			}

			@Override
			public void openPlayer(String playerName, int combatLevel, List<PlayerEquipmentItem> equipment)
			{
				showPinnedPlayer(playerName, combatLevel, equipment);
			}

			@Override
			public void clearNpc()
			{
				updatePinnedInspects(pinnedInspectState.withoutNpc());
			}

			@Override
			public void clearItem()
			{
				updatePinnedInspects(pinnedInspectState.withoutItem());
			}

			@Override
			public void clearPlayer()
			{
				updatePinnedInspects(pinnedInspectState.withoutPlayer());
			}
		});
		inspectPanel.setCacheManagementHandler(new InspectPanel.CacheManagementHandler()
		{
			@Override
			public void clearItemCache()
			{
				inspectPanel.showCacheManagementStatus("Clearing Item Inspect cache...");
				showCacheClearResult(itemInspectService.clearCacheAsync(), "Item Inspect cache cleared.");
			}

			@Override
			public void clearNpcCache()
			{
				inspectPanel.showCacheManagementStatus("Clearing NPC Inspect cache...");
				showCacheClearResult(npcInspectService.clearCacheAsync(), "NPC Inspect cache cleared.");
			}

			@Override
			public void clearAllCache()
			{
				inspectPanel.showCacheManagementStatus("Clearing Inspect caches...");
				showCacheClearResult(
					CompletableFuture.allOf(itemInspectService.clearCacheAsync(), npcInspectService.clearCacheAsync()),
					"Inspect caches cleared.");
			}
		});
		inspectPanel.setRecentInspectHandler(new InspectPanel.RecentInspectHandler()
		{
			@Override
			public void inspectNpc(String npcName)
			{
				inspectRecentNpc(npcName);
			}

			@Override
			public void inspectPlayer(String playerName)
			{
				inspectRecentPlayer(playerName);
			}

			@Override
			public void inspectItem(int itemId, String itemName)
			{
				inspectPlayerEquipmentItem(itemId, itemName);
			}
		});
		inspectPanel.setGearRecommendationHandler(new InspectPanel.GearRecommendationHandler()
		{
			@Override
			public void findGear(NpcCombatInfo info)
			{
				findRecommendedBankGear(info);
			}

			@Override
			public void clearGear(NpcCombatInfo info)
			{
				clearRecommendedBankGear(info);
			}
		});
		inspectNavButton = NavigationButton.builder()
			.tooltip("Inspect")
			.icon(createIcon())
			.priority(7)
			.panel(inspectPanel)
			.build();

		clientToolbar.addNavigation(inspectNavButton);
		overlayManager.add(bankEquipmentOverlay);
		npcInspectService.startUp(config.clearNpcInspectCacheOnStartup());
		itemInspectService.startUp(config.clearNpcInspectCacheOnStartup());
		addPlayerInspectMenuOptionIfEnabled();
		log.debug("Inspect plugin started");
	}

	@Override
	protected void shutDown()
	{
		menuManager.removePlayerMenuItem(PLAYER_INSPECT);
		clientToolbar.removeNavigation(inspectNavButton);
		overlayManager.remove(bankEquipmentOverlay);
		bankEquipmentOverlay.clear();
		npcInspectService.shutDown();
		itemInspectService.shutDown();
		inspectPanel = null;
		inspectNavButton = null;
		currentNpcInfo = null;
		currentNpcRecommendation = null;
		currentNpcRecommendationMessage = null;
		currentNpcDropItemIds = Collections.emptyMap();
		pinnedInspectState = PinnedInspectState.empty();
		resetInspectMenuMarker();
		log.debug("Inspect plugin stopped");
	}

	private void updatePinnedInspects(PinnedInspectState state)
	{
		pinnedInspectState = state == null ? PinnedInspectState.empty() : state;
		if (inspectPanel != null)
		{
			inspectPanel.setPinnedInspects(pinnedInspectState);
			inspectPanel.refreshActiveView();
		}
	}

	private void showCacheClearResult(CompletableFuture<Void> clearTask, String successMessage)
	{
		clearTask.whenComplete((ignored, error) ->
		{
			if (error != null)
			{
				log.debug("Unable to clear Inspect cache", error);
			}

			String statusMessage = error == null
				? successMessage
				: "Unable to clear the Inspect cache. Check the RuneLite log for details.";
			SwingUtilities.invokeLater(() ->
			{
				if (inspectPanel != null)
				{
					inspectPanel.updateCacheManagementStatus(statusMessage);
				}
			});
		});
	}

	private void showPinnedItem(ItemInspectInfo info)
	{
		if (info == null)
		{
			return;
		}

		clientThread.invokeLater(() ->
		{
			Map<Skill, Integer> skillLevels = snapshotSkillLevels();
			ItemRequirementSummary requirementSummary = itemRequirementSummary(info, skillLevels);
			ItemPriceSummary priceSummary = itemPriceSummary(info);
			Map<Quest, QuestState> questStates = snapshotItemSourceQuestStates(info);
			ItemSourceAccountMode accountMode = itemSourceAccountMode();
			ItemSourceReadiness sourceReadiness = ItemSourceReadinessEvaluator.evaluate(
				info.getSourcePlan(),
				skillLevels,
				questStates,
				accountMode);
			SwingUtilities.invokeLater(() ->
			{
				addRecentItem(recentItemInspects, info.getItemId(), info.getDisplayName());
				bankEquipmentOverlay.clear();
				inspectPanel.setRecentItems(recentItems());
				inspectPanel.showItemInfo(info, null, requirementSummary, priceSummary, sourceReadiness);
			});
		});
	}

	private void showPinnedPlayer(String playerName, int combatLevel, List<PlayerEquipmentItem> equipment)
	{
		if (playerName == null || equipment == null)
		{
			return;
		}

		clientThread.invokeLater(() ->
		{
			List<PlayerEquipmentItem> equipmentSnapshot = new ArrayList<>(equipment);
			Map<String, PlayerEquipmentItem> localEquipment = localPlayerEquipmentBySlot();
			boolean pvpBlocked = isPvpEquipmentInspectBlocked();
			PlayerInspectAnalysis loadingAnalysis = pvpBlocked
				? null
				: PlayerInspectAnalysis.loading(formatCoins(totalVisibleValue(equipmentSnapshot)));
			addRecent(recentPlayerInspects, playerName);
			bankEquipmentOverlay.clear();
			SwingUtilities.invokeLater(() -> inspectPanel.showPlayerEquipment(
				playerName,
				combatLevel,
				equipmentSnapshot,
				loadingAnalysis,
				pvpBlocked,
				recentPlayers(),
				recentItems()
			));
			loadPlayerInspectAnalysis(playerName, combatLevel, equipmentSnapshot, localEquipment, pvpBlocked);
		});
	}

	@Subscribe
	public void onConfigChanged(ConfigChanged event)
	{
		if (!CONFIG_GROUP.equals(event.getGroup()))
		{
			return;
		}

		if ("showPlayerEquipmentInspectOption".equals(event.getKey()))
		{
			menuManager.removePlayerMenuItem(PLAYER_INSPECT);
			addPlayerInspectMenuOptionIfEnabled();
		}

		if ("clearNpcInspectCacheOnStartup".equals(event.getKey()) && config.clearNpcInspectCacheOnStartup())
		{
			npcInspectService.clearCacheAsync();
			itemInspectService.clearCacheAsync();
		}
	}

	@Subscribe
	public void onClientTick(ClientTick event)
	{
		resetInspectMenuMarker();
	}

	@Subscribe
	public void onItemContainerChanged(ItemContainerChanged event)
	{
		if (event.getContainerId() != InventoryID.INV && event.getContainerId() != InventoryID.WORN)
		{
			return;
		}

		refreshCurrentNpcItemRequirements();
	}

	@Subscribe
	public void onMenuEntryAdded(MenuEntryAdded event)
	{
		if (isNpcMenuAction(event.getType()))
		{
			addNpcInspectEntry(event);
			return;
		}

		if (isItemMenuAction(event.getType()))
		{
			addItemInspectEntry(event);
			return;
		}
	}

	@Subscribe
	public void onMenuOptionClicked(MenuOptionClicked event)
	{
		if (!config.showPlayerEquipmentInspectOption()
			|| !isPlayerInspectMenuClick(event.getMenuAction(), event.getMenuOption()))
		{
			return;
		}

		inspectPlayerEquipment(event.getMenuEntry().getPlayer());
	}

	private void addPlayerInspectMenuOptionIfEnabled()
	{
		if (config.showPlayerEquipmentInspectOption())
		{
			menuManager.addPlayerMenuItem(PLAYER_INSPECT);
		}
	}

	@Subscribe
	public void onMenuOpened(MenuOpened event)
	{
		if (!config.enableWikiLookups() || !config.showItemInspectOption())
		{
			return;
		}

		for (int index = event.getMenuEntries().length - 1; index >= 0; index--)
		{
			if (addWornItemInspectEntry(event.getMenuEntries()[index], index))
			{
				return;
			}
		}
	}

	private void addNpcInspectEntry(MenuEntryAdded event)
	{
		if (!config.enableWikiLookups() || !config.showNpcInspectOption() || alreadyAddedNpcInspectFor(event))
		{
			return;
		}

		NPC npc = event.getMenuEntry().getNpc();
		if (npc == null)
		{
			return;
		}

		NPCComposition composition = npc.getTransformedComposition();
		if (composition == null || composition.getName() == null || composition.getName().isEmpty())
		{
			return;
		}

		final int npcId = composition.getId();
		final String npcName = Text.removeTags(composition.getName());
		if (npcId < 0 || "null".equalsIgnoreCase(npcName))
		{
			return;
		}

		client.getMenu().createMenuEntry(-1)
			.setOption(INSPECT)
			.setTarget(event.getTarget())
			.setType(MenuAction.RUNELITE)
			.onClick(menuEntry -> inspectNpc(npcId, npcName));

		lastNpcInspectMenu = MenuMarker.ofNpc(event);
	}

	private void addItemInspectEntry(MenuEntryAdded event)
	{
		if (!config.enableWikiLookups() || !config.showItemInspectOption() || alreadyAddedItemInspectFor(event) || !isInspectableItemWidget(event))
		{
			return;
		}

		int itemId = itemManager.canonicalize(event.getItemId());
		ItemComposition composition = itemManager.getItemComposition(itemId);
		String itemName = composition.getMembersName();
		if (itemId < 0 || itemName == null || itemName.isEmpty() || "null".equalsIgnoreCase(itemName))
		{
			return;
		}

		client.getMenu().createMenuEntry(-1)
			.setOption(INSPECT)
			.setTarget(event.getTarget())
			.setType(MenuAction.RUNELITE)
			.onClick(menuEntry -> inspectItem(itemId, itemName));

		lastItemInspectMenu = MenuMarker.ofItem(event);
	}

	private boolean addWornItemInspectEntry(net.runelite.api.MenuEntry entry, int index)
	{
		Widget widget = entry.getWidget();
		if (widget == null
			|| WidgetUtil.componentToInterface(widget.getId()) != InterfaceID.WORNITEMS
			|| !"Examine".equals(entry.getOption())
			|| entry.getIdentifier() != 10)
		{
			return false;
		}

		Widget itemWidget = widget.getChild(1);
		if (itemWidget == null || itemWidget.getItemId() < 0)
		{
			return false;
		}

		int itemId = itemManager.canonicalize(itemWidget.getItemId());
		ItemComposition composition = itemManager.getItemComposition(itemId);
		String itemName = composition.getMembersName();
		if (itemId < 0 || itemName == null || itemName.isEmpty() || "null".equalsIgnoreCase(itemName))
		{
			return false;
		}

		client.getMenu().createMenuEntry(index)
			.setOption(INSPECT)
			.setTarget(entry.getTarget())
			.setType(MenuAction.RUNELITE)
			.onClick(menuEntry -> inspectItem(itemId, itemName));
		return true;
	}

	private boolean alreadyAddedNpcInspectFor(MenuEntryAdded event)
	{
		return lastNpcInspectMenu.matches(event);
	}

	private boolean alreadyAddedItemInspectFor(MenuEntryAdded event)
	{
		return lastItemInspectMenu.matches(event);
	}

	private void resetInspectMenuMarker()
	{
		lastNpcInspectMenu = MenuMarker.empty();
		lastItemInspectMenu = MenuMarker.empty();
	}

	static boolean isPlayerInspectMenuClick(MenuAction action, String option)
	{
		return action == MenuAction.RUNELITE_PLAYER && PLAYER_INSPECT.equals(option);
	}

	private static void addRecent(Deque<String> recentInspects, String label)
	{
		if (label == null || label.isEmpty())
		{
			return;
		}

		recentInspects.removeIf(existing -> existing.equalsIgnoreCase(label));
		recentInspects.addFirst(label);
		while (recentInspects.size() > 4)
		{
			recentInspects.removeLast();
		}
	}

	private static void addRecentItem(Deque<RecentInspectEntry> recentInspects, int itemId, String itemName)
	{
		if (itemId < 0 || itemName == null || itemName.isEmpty())
		{
			return;
		}

		recentInspects.removeIf(existing -> existing.getItemId() == itemId || existing.getLabel().equalsIgnoreCase(itemName));
		recentInspects.addFirst(new RecentInspectEntry(itemId, itemName));
		while (recentInspects.size() > 4)
		{
			recentInspects.removeLast();
		}
	}

	private List<String> recentPlayers()
	{
		return new ArrayList<>(recentPlayerInspects);
	}

	private List<String> recentNpcs()
	{
		return new ArrayList<>(recentNpcInspects);
	}

	private List<RecentInspectEntry> recentItems()
	{
		return new ArrayList<>(recentItemInspects);
	}

	private void inspectRecentNpc(String npcName)
	{
		searchInspect("NPC", npcName);
	}

	private void inspectRecentPlayer(String playerName)
	{
		clientThread.invokeLater(() ->
		{
			Player player = findPlayer(playerName);
			if (player == null)
			{
				SwingUtilities.invokeLater(() -> inspectPanel.showSearchDisabled(playerName + " is not available to inspect right now."));
				return;
			}

			inspectPlayerEquipment(player);
		});
	}

	private Player findPlayer(String playerName)
	{
		if (playerName == null || playerName.isEmpty())
		{
			return null;
		}

		String normalized = playerName.replace('\u00A0', ' ');
		for (Player player : client.getTopLevelWorldView().players())
		{
			if (player == null || player.getName() == null)
			{
				continue;
			}

			String candidate = Text.removeTags(player.getName()).replace('\u00A0', ' ');
			if (candidate.equalsIgnoreCase(normalized))
			{
				return player;
			}
		}
		return null;
	}

	private void inspectNpc(int npcId, String npcName)
	{
		addRecent(recentNpcInspects, npcName);
		SwingUtilities.invokeLater(() ->
		{
			clientToolbar.openPanel(inspectNavButton);
			inspectPanel.showLoading(npcName);
		});

		npcInspectService.inspect(npcId, npcName, config.npcInspectCacheTtlDays())
			.whenComplete((info, throwable) -> SwingUtilities.invokeLater(() ->
			{
				if (throwable != null)
				{
					log.debug("NPC Inspect lookup failed for {} ({})", npcName, npcId, throwable);
					inspectPanel.showError(npcName, "Unable to load combat info from the OSRS Wiki.");
					return;
				}

				if (info == null)
				{
					inspectPanel.showNotFound(npcName);
					return;
				}

				showNpcInfoWithLocalChecks(info, true);
			}));
	}

	private void inspectItem(int itemId, String itemName)
	{
		Map<EquipmentInventorySlot, EquippedItem> equippedItems = snapshotEquippedItems();
		addRecentItem(recentItemInspects, itemId, itemName);
		SwingUtilities.invokeLater(() ->
		{
			clientToolbar.openPanel(inspectNavButton);
			inspectPanel.showItemLoading(itemName);
		});

		renderItemLookup(itemInspectService.inspect(itemId, itemName, config.npcInspectCacheTtlDays()), itemName, equippedItems, false);
	}

	private void inspectPlayerEquipmentItem(int itemId, String itemName)
	{
		if (!config.enableWikiLookups())
		{
			SwingUtilities.invokeLater(() -> inspectPanel.showSearchDisabled("Enable OSRS Wiki lookups in the Inspect config to inspect items from player equipment."));
			return;
		}

		if (!config.showItemInspectOption())
		{
			SwingUtilities.invokeLater(() -> inspectPanel.showSearchDisabled("Enable Item inspect in the Inspect config to inspect items from player equipment."));
			return;
		}

		clientThread.invokeLater(() -> inspectItem(itemId, itemName));
	}

	private void inspectPlayerEquipment(Player player)
	{
		if (player == null || player.getName() == null || player.getPlayerComposition() == null)
		{
			return;
		}

		String playerName = Text.removeTags(player.getName());
		List<PlayerEquipmentItem> equipment = playerEquipment(player);
		Map<String, PlayerEquipmentItem> localEquipment = localPlayerEquipmentBySlot();
		boolean pvpBlocked = isPvpEquipmentInspectBlocked();
		int combatLevel = player.getCombatLevel();
		PlayerInspectAnalysis loadingAnalysis = pvpBlocked
			? null
			: PlayerInspectAnalysis.loading(formatCoins(totalVisibleValue(equipment)));
		addRecent(recentPlayerInspects, playerName);
		bankEquipmentOverlay.clear();
		SwingUtilities.invokeLater(() ->
		{
			clientToolbar.openPanel(inspectNavButton);
			inspectPanel.showPlayerEquipment(playerName, combatLevel, equipment, loadingAnalysis, pvpBlocked, recentPlayers(), recentItems());
		});
		loadPlayerInspectAnalysis(playerName, combatLevel, equipment, localEquipment, pvpBlocked);
	}

	private boolean isPvpEquipmentInspectBlocked()
	{
		Collection<WorldType> worldTypes = client.getWorldType();
		if (WorldType.isPvpWorld(worldTypes)
			|| worldTypes.contains(WorldType.DEADMAN)
			|| worldTypes.contains(WorldType.LAST_MAN_STANDING)
			|| worldTypes.contains(WorldType.PVP_ARENA))
		{
			return true;
		}

		return client.getVarbitValue(VarbitID.PVP_AREA_CLIENT) > 0
			|| client.getVarbitValue(VarbitID.PVP_ADJACENT_AREA_CLIENT) > 0
			|| client.getVarbitValue(VarbitID.INSIDE_WILDERNESS) > 0
			|| client.getVarbitValue(VarbitID.DEADMAN_INWILDERNESS) > 0
			|| client.getVarbitValue(VarbitID.THIS_IS_A_PVP_WORLD) > 0
			|| client.getVarbitValue(VarbitID.THIS_IS_A_PVP_OR_BH_WORLD) > 0;
	}

	private void loadPlayerInspectAnalysis(String playerName, int combatLevel, List<PlayerEquipmentItem> equipment,
		Map<String, PlayerEquipmentItem> localEquipment, boolean pvpBlocked)
	{
		if (pvpBlocked)
		{
			return;
		}

		String visibleValue = formatCoins(totalVisibleValue(equipment));
			if (!config.enableWikiLookups())
			{
				SwingUtilities.invokeLater(() -> inspectPanel.showPlayerEquipment(
					playerName,
					combatLevel,
					equipment,
					PlayerInspectAnalysis.message(visibleValue, "Enable OSRS Wiki lookups to compare visible gear bonuses."),
					false,
					recentPlayers(),
					recentItems()
				));
			return;
		}

		List<CompletableFuture<PlayerEquipmentComparison>> comparisons = new ArrayList<>();
		for (PlayerEquipmentItem item : equipment)
		{
			PlayerEquipmentItem localItem = localEquipment.get(item.getSlot());
			comparisons.add(playerEquipmentComparison(item, localItem));
		}

		CompletableFuture.allOf(comparisons.toArray(new CompletableFuture[0]))
			.whenComplete((ignored, throwable) -> SwingUtilities.invokeLater(() ->
			{
				if (throwable != null)
				{
					log.debug("Player equipment comparison failed for {}", playerName, throwable);
						inspectPanel.showPlayerEquipment(
							playerName,
							combatLevel,
							equipment,
							PlayerInspectAnalysis.message(visibleValue, "Unable to load visible gear comparison."),
							false,
						recentPlayers(),
						recentItems()
					);
					return;
				}

				List<PlayerEquipmentComparison> rows = new ArrayList<>();
				for (CompletableFuture<PlayerEquipmentComparison> comparison : comparisons)
				{
					PlayerEquipmentComparison row = comparison.join();
					if (row != null)
					{
						rows.add(row);
					}
				}

				String message = rows.isEmpty()
					? "No visible gear could be compared to your current equipment."
					: "Compared to your current equipment by visible slot.";
					inspectPanel.showPlayerEquipment(
						playerName,
						combatLevel,
						equipment,
						new PlayerInspectAnalysis(visibleValue, message, rows),
						false,
					recentPlayers(),
					recentItems()
				);
			}));
	}

	private CompletableFuture<PlayerEquipmentComparison> playerEquipmentComparison(PlayerEquipmentItem item, PlayerEquipmentItem localItem)
	{
		CompletableFuture<ItemInspectInfo> inspectedLookup = itemInspectService.inspect(item.getItemId(), item.getItemName(), config.npcInspectCacheTtlDays())
			.exceptionally(throwable ->
			{
				log.debug("Player inspect item stat lookup failed for {} ({})", item.getItemName(), item.getItemId(), throwable);
				return null;
			});
		CompletableFuture<ItemInspectInfo> localLookup = localItem == null
			? CompletableFuture.completedFuture(null)
			: itemInspectService.inspect(localItem.getItemId(), localItem.getItemName(), config.npcInspectCacheTtlDays())
				.exceptionally(throwable ->
				{
					log.debug("Local item stat lookup failed for {} ({})", localItem.getItemName(), localItem.getItemId(), throwable);
					return null;
				});

		return inspectedLookup.thenCombine(localLookup, (inspectedInfo, localInfo) ->
		{
			if (inspectedInfo == null)
			{
				return null;
			}

			return new PlayerEquipmentComparison(
				item.getSlot(),
				item.getItemName(),
				localItem == null ? "None" : localItem.getItemName(),
				formatDelta(attackTotal(inspectedInfo) - attackTotal(localInfo)),
				formatDelta(defenceTotal(inspectedInfo) - defenceTotal(localInfo)),
				formatDelta(strengthTotal(inspectedInfo) - strengthTotal(localInfo)),
				formatDelta(numericValue(inspectedInfo.getPrayer()) - numericValue(localInfo == null ? null : localInfo.getPrayer()))
			);
		});
	}

	private void searchInspect(String type, String query)
	{
		String resolvedQuery = SearchQueryNormalizer.normalize(type, query);
		if (!config.enableWikiLookups())
		{
			SwingUtilities.invokeLater(() -> inspectPanel.showSearchDisabled("Enable OSRS Wiki lookups in the Inspect config to search the OSRS Wiki from this panel."));
			return;
		}

		if (!config.showInspectSearch())
		{
			SwingUtilities.invokeLater(() -> inspectPanel.showSearchDisabled("Enable Inspect search in the Inspect config to search from this panel."));
			return;
		}

		final String lookupQuery = resolvedQuery;
		SwingUtilities.invokeLater(() -> inspectPanel.showSearchLoading(type, lookupQuery));
		if ("NPC".equals(type))
		{
			npcInspectService.search(lookupQuery, config.npcInspectCacheTtlDays())
				.whenComplete((info, throwable) -> SwingUtilities.invokeLater(() ->
				{
					if (throwable != null)
					{
						log.debug("NPC Inspect search failed for {}", lookupQuery, throwable);
						inspectPanel.showSearchError(type, lookupQuery);
						return;
					}

					if (info == null)
					{
						inspectPanel.showSearchNotFound(type, lookupQuery);
						return;
					}

					addRecent(recentNpcInspects, info.getDisplayName() == null ? lookupQuery : info.getDisplayName());
					showNpcInfoWithLocalChecks(info, false);
				}));
			return;
		}

		searchItemVariants(lookupQuery);
	}

	private void searchItemVariants(String query)
	{
		itemInspectService.searchVariants(query, config.npcInspectCacheTtlDays())
			.whenComplete((variants, throwable) ->
			{
				if (throwable != null)
				{
					log.debug("Item Inspect variant search failed for {}", query, throwable);
					SwingUtilities.invokeLater(() -> inspectPanel.showSearchError("Item", query));
					return;
				}

				if (variants == null || variants.isEmpty())
				{
					clientThread.invokeLater(() -> renderItemLookup(
						itemInspectService.search(query, config.npcInspectCacheTtlDays()),
						query,
						snapshotEquippedItems(),
						true));
					return;
				}

				ItemInspectVariant exact = ItemInspectVariantSelector.exactMatch(query, variants);
				if (exact != null)
				{
					inspectItemVariant(exact);
					return;
				}

				SwingUtilities.invokeLater(() -> inspectPanel.showItemVariantPicker(query, variants));
			});
	}

	private void inspectItemVariant(ItemInspectVariant variant)
	{
		if (variant == null)
		{
			return;
		}

		SwingUtilities.invokeLater(() -> inspectPanel.showSearchLoading("Item", variant.getDisplayName()));
		clientThread.invokeLater(() -> renderItemLookup(
			itemInspectService.inspect(variant, config.npcInspectCacheTtlDays()),
			variant.getDisplayName(),
			snapshotEquippedItems(),
			true));
	}

	private void renderItemLookup(CompletableFuture<ItemInspectInfo> lookup, String itemName, Map<EquipmentInventorySlot, EquippedItem> equippedItems, boolean search)
	{
		Map<Skill, Integer> skillLevels = snapshotSkillLevels();
		lookup
			.thenCompose(info ->
			{
				if (info == null)
				{
					return CompletableFuture.completedFuture(new ItemInspectResult(null, null));
				}

					EquippedItem equippedItem = findEquippedComparison(info, equippedItems);
				if (equippedItem == null)
				{
					return CompletableFuture.completedFuture(new ItemInspectResult(info, null));
				}

				return itemInspectService.inspect(equippedItem.itemId, equippedItem.itemName, config.npcInspectCacheTtlDays())
					.exceptionally(throwable ->
					{
						log.debug("Item comparison lookup failed for {} ({})", equippedItem.itemName, equippedItem.itemId, throwable);
						return null;
					})
					.thenApply(equippedInfo -> new ItemInspectResult(info, equippedInfo));
			})
			.whenComplete((result, throwable) ->
			{
				if (throwable != null)
				{
					SwingUtilities.invokeLater(() ->
					{
						log.debug("Item Inspect lookup failed for {}", itemName, throwable);
						if (search)
						{
							inspectPanel.showSearchError("Item", itemName);
						}
						else
						{
							inspectPanel.showItemError(itemName, "Unable to load item info from the OSRS Wiki.");
						}
					});
					return;
				}

				if (result == null || result.info == null)
				{
					SwingUtilities.invokeLater(() ->
					{
						if (search)
						{
							inspectPanel.showSearchNotFound("Item", itemName);
						}
						else
						{
							inspectPanel.showItemNotFound(itemName);
						}
					});
					return;
				}

				clientThread.invokeLater(() ->
				{
					ItemPriceSummary priceSummary = itemPriceSummary(result.info);
					ItemRequirementSummary requirementSummary = itemRequirementSummary(result.info, skillLevels);
					Map<Quest, QuestState> questStates = snapshotItemSourceQuestStates(result.info);
					ItemSourceAccountMode accountMode = itemSourceAccountMode();
					ItemSourceReadiness sourceReadiness = ItemSourceReadinessEvaluator.evaluate(
						result.info.getSourcePlan(),
						skillLevels,
						questStates,
						accountMode);
					SwingUtilities.invokeLater(() ->
					{
						addRecentItem(recentItemInspects, result.info.getItemId(),
							result.info.getDisplayName() == null ? itemName : result.info.getDisplayName());
						bankEquipmentOverlay.clear();
						inspectPanel.setRecentItems(recentItems());
						inspectPanel.showItemInfo(
							result.info,
							result.equippedInfo,
							requirementSummary,
							priceSummary,
							sourceReadiness);
					});
				});
			});
	}

	private void findRecommendedBankGear(NpcCombatInfo info)
	{
		if (!config.enableWikiLookups())
		{
			SwingUtilities.invokeLater(() -> showNpcInfo(info, EquipmentRecommendation.preview(info), "Enable OSRS Wiki lookups first.", Collections.emptyList()));
			return;
		}

		if (!config.showEquipmentRecommendations())
		{
			SwingUtilities.invokeLater(() -> showNpcInfo(info, EquipmentRecommendation.preview(info), "Enable Equipment recommendations in the Enhanced config.", Collections.emptyList()));
			return;
		}

		clientThread.invokeLater(() ->
		{
			List<NpcItemRequirementStatus> itemRequirementStatuses = npcItemRequirementStatuses(info);
			Map<String, Integer> dropItemIds = npcDropItemIds(info);
			Collection<BankEquipmentRecommendationService.BankItemCandidate> bankItems = snapshotBankEquipmentCandidates();
			if (bankItems.isEmpty())
			{
				bankEquipmentOverlay.clear();
				SwingUtilities.invokeLater(() -> showNpcInfo(info, EquipmentRecommendation.preview(info), "No bank or equipped gear found.", itemRequirementStatuses, dropItemIds));
				return;
			}

			SwingUtilities.invokeLater(() -> showNpcInfo(info, EquipmentRecommendation.preview(info), "Scanning bank and equipped gear...", itemRequirementStatuses, dropItemIds));
			bankEquipmentRecommendationService.recommend(info, bankItems, config.npcInspectCacheTtlDays())
				.whenComplete((recommendation, throwable) -> SwingUtilities.invokeLater(() ->
				{
					if (throwable != null)
					{
						log.debug("Bank equipment recommendation failed for {}", info.getDisplayName(), throwable);
						bankEquipmentOverlay.clear();
						showNpcInfo(info, EquipmentRecommendation.preview(info), "Unable to load bank item stats.", itemRequirementStatuses, dropItemIds);
						return;
					}

					if (recommendation == null || !recommendation.hasItems())
					{
						bankEquipmentOverlay.clear();
						showNpcInfo(info, recommendation == null ? EquipmentRecommendation.preview(info) : recommendation, "No matching equipment found.", itemRequirementStatuses, dropItemIds);
						return;
					}

					bankEquipmentOverlay.setHighlightedItemRanks(recommendation.bankItemRanks());
					showNpcInfo(info, recommendation, recommendation.bankItemRanks().isEmpty()
						? "Checked current equipment. Open your bank to highlight gear."
						: "Ranked matching bank gear.", itemRequirementStatuses, dropItemIds);
				}));
		});
	}

	private void clearRecommendedBankGear(NpcCombatInfo info)
	{
		bankEquipmentOverlay.clear();
		clientThread.invokeLater(() ->
		{
			List<NpcItemRequirementStatus> itemRequirementStatuses = npcItemRequirementStatuses(info);
			Map<String, Integer> dropItemIds = npcDropItemIds(info);
			SwingUtilities.invokeLater(() -> showNpcInfo(info, EquipmentRecommendation.preview(info), "Selection cleared.", itemRequirementStatuses, dropItemIds));
		});
	}

	private void showNpcInfo(NpcCombatInfo info)
	{
		showNpcInfo(info, Collections.emptyList());
	}

	private void showNpcInfo(NpcCombatInfo info, List<NpcItemRequirementStatus> itemRequirementStatuses)
	{
		EquipmentRecommendation recommendation = config.showEquipmentRecommendations()
			? EquipmentRecommendation.preview(info)
			: null;
		showNpcInfo(info, recommendation, null, itemRequirementStatuses, Collections.emptyMap());
	}

	private void showNpcInfo(NpcCombatInfo info, EquipmentRecommendation recommendation, String recommendationMessage,
		List<NpcItemRequirementStatus> itemRequirementStatuses)
	{
		showNpcInfo(info, recommendation, recommendationMessage, itemRequirementStatuses, Collections.emptyMap());
	}

	private void showNpcInfo(NpcCombatInfo info, EquipmentRecommendation recommendation, String recommendationMessage,
		List<NpcItemRequirementStatus> itemRequirementStatuses, Map<String, Integer> dropItemIds)
	{
		currentNpcInfo = info;
		currentNpcRecommendation = recommendation;
		currentNpcRecommendationMessage = recommendationMessage;
		currentNpcDropItemIds = dropItemIds == null ? Collections.emptyMap() : new LinkedHashMap<>(dropItemIds);
		inspectPanel.setRecentNpcs(recentNpcs());
		inspectPanel.showInfo(info, recommendation, recommendationMessage, itemRequirementStatuses, currentNpcDropItemIds);
	}

	private void showNpcInfoWithLocalChecks(NpcCombatInfo info, boolean clearBankOverlay)
	{
		clientThread.invokeLater(() ->
		{
			List<NpcItemRequirementStatus> itemRequirementStatuses = npcItemRequirementStatuses(info);
			Map<String, Integer> dropItemIds = npcDropItemIds(info);
			SwingUtilities.invokeLater(() ->
			{
				if (clearBankOverlay)
				{
					bankEquipmentOverlay.clear();
				}
				EquipmentRecommendation recommendation = config.showEquipmentRecommendations()
					? EquipmentRecommendation.preview(info)
					: null;
				showNpcInfo(info, recommendation, null, itemRequirementStatuses, dropItemIds);
			});
		});
	}

	private List<NpcItemRequirementStatus> npcItemRequirementStatuses(NpcCombatInfo info)
	{
		if (info == null || info.getItemRequirements() == null || info.getItemRequirements().isEmpty())
		{
			return Collections.emptyList();
		}

		Map<String, String> carriedItemNames = snapshotCarriedItemNames();
		List<NpcItemRequirementStatus> statuses = new ArrayList<>();
		for (NpcItemRequirement requirement : info.getItemRequirements())
		{
			NpcItemRequirement resolvedRequirement = resolvedNpcItemRequirement(requirement);
			if (resolvedRequirement == null)
			{
				continue;
			}
			statuses.add(new NpcItemRequirementStatus(
				resolvedRequirement,
				npcItemRequirementAlternativeStatuses(resolvedRequirement, carriedItemNames)));
		}
		return statuses;
	}

	private NpcItemRequirement resolvedNpcItemRequirement(NpcItemRequirement requirement)
	{
		if (requirement == null || requirement.getAlternatives() == null || requirement.getAlternatives().isEmpty())
		{
			return null;
		}

		List<String> alternatives = new ArrayList<>();
		for (String alternative : requirement.getAlternatives())
		{
			String itemName = requiredItemName(alternative);
			if (itemName != null && !containsIgnoreCase(alternatives, itemName))
			{
				alternatives.add(itemName);
			}
		}
		return npcItemRequirementWithAlternatives(alternatives);
	}

	static NpcItemRequirement npcItemRequirementWithAlternatives(List<String> alternatives)
	{
		if (alternatives == null || alternatives.isEmpty())
		{
			return null;
		}
		return new NpcItemRequirement(String.join(" or ", alternatives), alternatives);
	}

	private String requiredItemName(String itemName)
	{
		if (itemManager == null || itemName == null || itemName.isEmpty())
		{
			return null;
		}

		int fallbackItemId = dropItemIdFallback(itemName);
		if (fallbackItemId > 0)
		{
			ItemComposition composition = itemManager.getItemComposition(fallbackItemId);
			return composition == null ? itemName : composition.getName();
		}

		List<ItemPrice> matches = itemManager.search(itemName);
		if (matches == null || matches.isEmpty())
		{
			return null;
		}

		String normalizedItemName = normalizeItemName(itemName);
		for (ItemPrice match : matches)
		{
			if (normalizedItemName.equals(normalizeItemName(match.getName())))
			{
				return match.getName();
			}
		}
		return null;
	}

	private static boolean containsIgnoreCase(List<String> values, String value)
	{
		for (String existing : values)
		{
			if (existing.equalsIgnoreCase(value))
			{
				return true;
			}
		}
		return false;
	}

	private Map<String, Integer> npcDropItemIds(NpcCombatInfo info)
	{
		Map<String, Integer> itemIds = new LinkedHashMap<>();
		if (info == null)
		{
			return itemIds;
		}

		addDropItemIds(itemIds, info.getValuableDrops());
		addDropItemIds(itemIds, info.getRareDrops());
		addDropItemIds(itemIds, info.getSlayerOnlyDrops());
		addDropItemIds(itemIds, info.getClueDrops());
		addDropItemIds(itemIds, info.getIronmanDrops());
		addDropItemIds(itemIds, info.getAlchableDrops());
		addDropItemIds(itemIds, info.getUpgradeDrops());
		return itemIds;
	}

	private void addDropItemIds(Map<String, Integer> itemIds, String drops)
	{
		for (String drop : splitDropTags(drops))
		{
			String normalizedDrop = normalizeItemName(drop);
			if (normalizedDrop.isEmpty() || itemIds.containsKey(normalizedDrop))
			{
				continue;
			}

			int itemId = dropItemId(drop);
			if (itemId > 0)
			{
				itemIds.put(normalizedDrop, itemId);
			}
		}
	}

	private int dropItemId(String itemName)
	{
		if (itemManager == null || itemName == null || itemName.isEmpty())
		{
			return -1;
		}

		int fallbackItemId = dropItemIdFallback(itemName);
		if (fallbackItemId > 0)
		{
			return fallbackItemId;
		}

		List<ItemPrice> matches = itemManager.search(itemName);
		if (matches == null || matches.isEmpty())
		{
			return -1;
		}

		String normalizedItemName = normalizeItemName(itemName);
		for (ItemPrice match : matches)
		{
			if (normalizedItemName.equals(normalizeItemName(match.getName())))
			{
				return itemManager.canonicalize(match.getId());
			}
		}

		return itemManager.canonicalize(matches.get(0).getId());
	}

	static int dropItemIdFallback(String itemName)
	{
		switch (normalizeItemName(itemName))
		{
			case "coins":
				return ItemID.COINS;
			case "tooth half of key":
				return ItemID.KEYHALF1;
			case "loop half of key":
				return ItemID.KEYHALF2;
			case "tooth half of key (moon key)":
				return ItemID.VARLAMORE_KEY_HALF_1;
			case "loop half of key (moon key)":
				return ItemID.VARLAMORE_KEY_HALF_2;
			case "attas seed":
				return ItemID.ATTAS_SEED;
			case "iasor seed":
				return ItemID.IASOR_SEED;
			case "kronos seed":
				return ItemID.KRONOS_SEED;
			case "watermelon seed":
				return ItemID.WATERMELON_SEED;
			case "snape grass seed":
				return ItemID.SNAPE_GRASS_SEED;
			case "white lily seed":
				return ItemID.WHITE_LILY_SEED;
			case "brimstone key":
				return ItemID.KONAR_KEY;
			case "brittle key":
				return ItemID.SLAYER_ROOF_KEY;
			default:
				return -1;
		}
	}

	private static List<String> splitDropTags(String value)
	{
		List<String> tags = new ArrayList<>();
		if (value == null)
		{
			return tags;
		}

		for (String tag : value.split(","))
		{
			String trimmed = tag.trim();
			if (!trimmed.isEmpty())
			{
				tags.add(trimmed);
			}
		}
		return tags;
	}

	private void refreshCurrentNpcItemRequirements()
	{
		if (inspectPanel == null || !inspectPanel.isNpcActive()
			|| currentNpcInfo == null || currentNpcInfo.getItemRequirements() == null || currentNpcInfo.getItemRequirements().isEmpty())
		{
			return;
		}

		NpcCombatInfo info = currentNpcInfo;
		EquipmentRecommendation recommendation = currentNpcRecommendation;
		String recommendationMessage = currentNpcRecommendationMessage;
		Map<String, Integer> dropItemIds = currentNpcDropItemIds;
		List<NpcItemRequirementStatus> itemRequirementStatuses = npcItemRequirementStatuses(info);
		SwingUtilities.invokeLater(() ->
		{
			if (inspectPanel != null && inspectPanel.isNpcActive() && currentNpcInfo == info)
			{
				inspectPanel.refreshNpcInfo(
					info,
					recommendation,
					recommendationMessage,
					itemRequirementStatuses,
					dropItemIds);
			}
		});
	}

	private static List<NpcItemRequirementAlternativeStatus> npcItemRequirementAlternativeStatuses(
		NpcItemRequirement requirement, Map<String, String> carriedItemNames)
	{
		if (requirement == null || requirement.getAlternatives() == null)
		{
			return Collections.emptyList();
		}

		List<NpcItemRequirementAlternativeStatus> alternatives = new ArrayList<>();
		for (String alternative : requirement.getAlternatives())
		{
			String matchedItemName = carriedItemNames.get(normalizeItemName(alternative));
			alternatives.add(new NpcItemRequirementAlternativeStatus(alternative, matchedItemName != null, matchedItemName));
		}
		return alternatives;
	}

	private Map<String, String> snapshotCarriedItemNames()
	{
		Map<String, String> itemNames = new LinkedHashMap<>();
		addCarriedItemNames(itemNames, client.getItemContainer(InventoryID.INV), "In Inventory");
		addCarriedItemNames(itemNames, client.getItemContainer(InventoryID.WORN), "Equipped");
		return itemNames;
	}

	private void addCarriedItemNames(Map<String, String> itemNames, ItemContainer itemContainer, String source)
	{
		if (itemContainer == null)
		{
			return;
		}

		for (Item item : itemContainer.getItems())
		{
			if (item == null || item.getId() <= 0)
			{
				continue;
			}

			int itemId = itemManager.canonicalize(item.getId());
			ItemComposition composition = itemManager.getItemComposition(itemId);
			String itemName = composition.getMembersName();
			if (itemName == null || itemName.isEmpty() || "null".equalsIgnoreCase(itemName))
			{
				continue;
			}

			itemNames.putIfAbsent(normalizeItemName(itemName), source);
		}
	}

	private static String normalizeItemName(String itemName)
	{
		return itemName == null
			? ""
			: itemName.replace('\u00A0', ' ').trim().replaceAll("\\s+", " ").toLowerCase(Locale.ENGLISH);
	}

	private Collection<BankEquipmentRecommendationService.BankItemCandidate> snapshotBankEquipmentCandidates()
	{
		Map<Integer, BankEquipmentRecommendationService.BankItemCandidate> candidates = new java.util.LinkedHashMap<>();
		ItemContainer bank = client.getItemContainer(InventoryID.BANK);
		if (bank != null)
		{
			for (Item item : bank.getItems())
			{
				addEquipmentCandidate(candidates, item, true, false);
			}
		}

		ItemContainer equipment = client.getItemContainer(InventoryID.WORN);
		if (equipment != null)
		{
			for (Item item : equipment.getItems())
			{
				addEquipmentCandidate(candidates, item, false, true);
			}
		}
		return new ArrayList<>(candidates.values());
	}

	private List<PlayerEquipmentItem> playerEquipment(Player player)
	{
		PlayerComposition composition = player.getPlayerComposition();
		List<PlayerEquipmentItem> equipment = new ArrayList<>();
		addPlayerEquipmentItem(equipment, composition, KitType.HEAD, "Head");
		addPlayerEquipmentItem(equipment, composition, KitType.CAPE, "Cape");
		addPlayerEquipmentItem(equipment, composition, KitType.AMULET, "Amulet");
		addPlayerEquipmentItem(equipment, composition, KitType.WEAPON, "Weapon");
		addPlayerEquipmentItem(equipment, composition, KitType.TORSO, "Body");
		addPlayerEquipmentItem(equipment, composition, KitType.SHIELD, "Shield");
		addPlayerEquipmentItem(equipment, composition, KitType.LEGS, "Legs");
		addPlayerEquipmentItem(equipment, composition, KitType.HANDS, "Hands");
		addPlayerEquipmentItem(equipment, composition, KitType.BOOTS, "Feet");
		if (isLocalPlayer(player))
		{
			addLocalPlayerEquipmentItem(equipment, EquipmentInventorySlot.RING, "Ring");
			addLocalPlayerEquipmentItem(equipment, EquipmentInventorySlot.AMMO, "Ammo");
		}
		return equipment;
	}

	private Map<String, PlayerEquipmentItem> localPlayerEquipmentBySlot()
	{
		Map<String, PlayerEquipmentItem> equipment = new java.util.LinkedHashMap<>();
		ItemContainer wornItems = client.getItemContainer(InventoryID.WORN);
		if (wornItems == null)
		{
			return equipment;
		}

		for (EquipmentInventorySlot slot : EquipmentInventorySlot.values())
		{
			String slotName = slotName(slot);
			if (slotName == null)
			{
				continue;
			}

			Item item = wornItems.getItem(slot.getSlotIdx());
			if (item == null || item.getId() <= 0)
			{
				continue;
			}

			PlayerEquipmentItem equipmentItem = playerEquipmentItem(slotName, item.getId());
			if (equipmentItem != null)
			{
				equipment.put(slotName, equipmentItem);
			}
		}
		return equipment;
	}

	private void addPlayerEquipmentItem(List<PlayerEquipmentItem> equipment, PlayerComposition composition, KitType kitType, String slot)
	{
		int itemId = composition.getEquipmentId(kitType);
		if (itemId < 0)
		{
			return;
		}

		PlayerEquipmentItem equipmentItem = playerEquipmentItem(slot, itemId);
		if (equipmentItem != null)
		{
			equipment.add(equipmentItem);
		}
	}

	private boolean isLocalPlayer(Player player)
	{
		Player localPlayer = client.getLocalPlayer();
		if (localPlayer == null || player == null)
		{
			return false;
		}

		String playerName = player.getName();
		String localPlayerName = localPlayer.getName();
		return player == localPlayer
			|| (playerName != null && localPlayerName != null && Text.removeTags(playerName).equals(Text.removeTags(localPlayerName)));
	}

	private void addLocalPlayerEquipmentItem(List<PlayerEquipmentItem> equipment, EquipmentInventorySlot slot, String slotName)
	{
		ItemContainer wornItems = client.getItemContainer(InventoryID.WORN);
		if (wornItems == null)
		{
			return;
		}

		Item item = wornItems.getItem(slot.getSlotIdx());
		if (item == null || item.getId() <= 0)
		{
			return;
		}

		addPlayerEquipmentItem(equipment, item.getId(), slotName);
	}

	private void addPlayerEquipmentItem(List<PlayerEquipmentItem> equipment, int itemId, String slot)
	{
		PlayerEquipmentItem equipmentItem = playerEquipmentItem(slot, itemId);
		if (equipmentItem != null)
		{
			equipment.add(equipmentItem);
		}
	}

	private PlayerEquipmentItem playerEquipmentItem(String slot, int itemId)
	{
		if (itemId < 0)
		{
			return null;
		}

		int canonicalItemId = itemManager.canonicalize(itemId);
		ItemComposition itemComposition = itemManager.getItemComposition(canonicalItemId);
		String itemName = itemComposition.getMembersName();
		if (itemName == null || itemName.isEmpty() || "null".equalsIgnoreCase(itemName))
		{
			return null;
		}

		return new PlayerEquipmentItem(slot, canonicalItemId, itemName, Math.max(0, itemManager.getItemPrice(canonicalItemId)));
	}

	private static String slotName(EquipmentInventorySlot slot)
	{
		switch (slot)
		{
			case HEAD:
				return "Head";
			case CAPE:
				return "Cape";
			case AMULET:
				return "Amulet";
			case WEAPON:
				return "Weapon";
			case BODY:
				return "Body";
			case SHIELD:
				return "Shield";
			case LEGS:
				return "Legs";
			case GLOVES:
				return "Hands";
			case BOOTS:
				return "Feet";
			case RING:
				return "Ring";
			case AMMO:
				return "Ammo";
			default:
				return null;
		}
	}

	private static int totalVisibleValue(List<PlayerEquipmentItem> equipment)
	{
		int total = 0;
		for (PlayerEquipmentItem item : equipment)
		{
			total += item.getPrice();
		}
		return total;
	}

	private static String formatCoins(int value)
	{
		if (value <= 0)
		{
			return "0 gp";
		}

		return String.format(Locale.ENGLISH, "%,d gp", value);
	}

	private static String formatDelta(double delta)
	{
		if (delta == 0)
		{
			return "0";
		}

		return (delta > 0 ? "+" : "") + Math.round(delta);
	}

	private static double attackTotal(ItemInspectInfo info)
	{
		if (info == null)
		{
			return 0;
		}

		return numericValue(info.getAttackStab())
			+ numericValue(info.getAttackSlash())
			+ numericValue(info.getAttackCrush())
			+ numericValue(info.getAttackMagic())
			+ numericValue(info.getAttackRanged());
	}

	private static double defenceTotal(ItemInspectInfo info)
	{
		if (info == null)
		{
			return 0;
		}

		return numericValue(info.getDefenceStab())
			+ numericValue(info.getDefenceSlash())
			+ numericValue(info.getDefenceCrush())
			+ numericValue(info.getDefenceMagic())
			+ numericValue(info.getDefenceRanged());
	}

	private static double strengthTotal(ItemInspectInfo info)
	{
		if (info == null)
		{
			return 0;
		}

		return numericValue(info.getStrength())
			+ numericValue(info.getRangedStrength())
			+ numericValue(info.getMagicDamage());
	}

	private static double numericValue(String value)
	{
		if (value == null || value.isEmpty())
		{
			return 0;
		}

		StringBuilder number = new StringBuilder();
		boolean started = false;
		boolean hasDecimal = false;
		for (int i = 0; i < value.length(); i++)
		{
			char c = value.charAt(i);
			if (!started && (c == '+' || c == '-' || Character.isDigit(c)))
			{
				number.append(c);
				started = true;
				continue;
			}

			if (started && Character.isDigit(c))
			{
				number.append(c);
				continue;
			}

			if (started && c == '.' && !hasDecimal)
			{
				number.append(c);
				hasDecimal = true;
				continue;
			}

			if (started)
			{
				break;
			}
		}

		if (number.length() == 0 || "+".contentEquals(number) || "-".contentEquals(number))
		{
			return 0;
		}

		try
		{
			return Double.parseDouble(number.toString());
		}
		catch (NumberFormatException ex)
		{
			return 0;
		}
	}

	private void addEquipmentCandidate(Map<Integer, BankEquipmentRecommendationService.BankItemCandidate> candidates, Item item, boolean inBank, boolean equipped)
	{
		if (item == null || item.getId() <= 0)
		{
			return;
		}

		int itemId = itemManager.canonicalize(item.getId());
		ItemComposition composition = itemManager.getItemComposition(itemId);
		String itemName = composition.getMembersName();
		if (itemName == null || itemName.isEmpty() || "null".equalsIgnoreCase(itemName) || !isEquipmentCandidate(composition))
		{
			return;
		}

		BankEquipmentRecommendationService.BankItemCandidate existing = candidates.get(itemId);
		if (existing == null)
		{
			candidates.put(itemId, new BankEquipmentRecommendationService.BankItemCandidate(itemId, itemName, inBank, equipped));
			return;
		}

		candidates.put(itemId, new BankEquipmentRecommendationService.BankItemCandidate(
			itemId,
			existing.getItemName(),
			existing.isInBank() || inBank,
			existing.isEquipped() || equipped
		));
	}

	private static boolean isEquipmentCandidate(ItemComposition composition)
	{
		String[] actions = composition.getInventoryActions();
		if (actions == null)
		{
			return false;
		}

		for (String action : actions)
		{
			if ("Wear".equals(action) || "Wield".equals(action) || "Equip".equals(action))
			{
				return true;
			}
		}
		return false;
	}

	private Map<EquipmentInventorySlot, EquippedItem> snapshotEquippedItems()
	{
		Map<EquipmentInventorySlot, EquippedItem> equippedItems = new EnumMap<>(EquipmentInventorySlot.class);
		ItemContainer equipment = client.getItemContainer(InventoryID.WORN);
		if (equipment == null)
		{
			return equippedItems;
		}

		for (EquipmentInventorySlot slot : EquipmentInventorySlot.values())
		{
			Item item = equipment.getItem(slot.getSlotIdx());
			if (item == null || item.getId() < 0)
			{
				continue;
			}

			int itemId = itemManager.canonicalize(item.getId());
			ItemComposition composition = itemManager.getItemComposition(itemId);
			String itemName = composition.getMembersName();
			if (itemName == null || itemName.isEmpty() || "null".equalsIgnoreCase(itemName))
			{
				continue;
			}

			equippedItems.put(slot, new EquippedItem(itemId, itemName));
		}
		return equippedItems;
	}

	private Map<Skill, Integer> snapshotSkillLevels()
	{
		Map<Skill, Integer> levels = new EnumMap<>(Skill.class);
		for (Skill skill : Skill.values())
		{
			levels.put(skill, client.getRealSkillLevel(skill));
		}
		return levels;
	}

	private Map<Quest, QuestState> snapshotItemSourceQuestStates(ItemInspectInfo info)
	{
		Map<Quest, QuestState> states = new EnumMap<>(Quest.class);
		if (info == null || client.getGameState() != GameState.LOGGED_IN)
		{
			return states;
		}

		for (Quest quest : ItemSourceReadinessEvaluator.referencedQuests(info.getSourcePlan()))
		{
			try
			{
				states.put(quest, quest.getState(client));
			}
			catch (RuntimeException ex)
			{
				log.debug("Unable to read quest state for {}", quest.getName(), ex);
			}
		}
		return states;
	}

	private ItemSourceAccountMode itemSourceAccountMode()
	{
		return ItemSourceAccountMode.fromVarbitValue(client.getVarbitValue(VarbitID.IRONMAN));
	}

	static ItemRequirementSummary itemRequirementSummary(ItemInspectInfo info, Map<Skill, Integer> skillLevels)
	{
		if (info == null)
		{
			return ItemRequirementSummary.empty();
		}

		List<String> met = new ArrayList<>();
		List<String> missing = new ArrayList<>();
		addRequirementCheck(met, missing, "Attack", info.getRequirementAttack(), Skill.ATTACK, skillLevels);
		addRequirementCheck(met, missing, "Strength", info.getRequirementStrength(), Skill.STRENGTH, skillLevels);
		addRequirementCheck(met, missing, "Defence", info.getRequirementDefence(), Skill.DEFENCE, skillLevels);
		addRequirementCheck(met, missing, "Ranged", info.getRequirementRanged(), Skill.RANGED, skillLevels);
		addRequirementCheck(met, missing, "Magic", info.getRequirementMagic(), Skill.MAGIC, skillLevels);
		addRequirementCheck(met, missing, "Prayer", info.getRequirementPrayer(), Skill.PRAYER, skillLevels);
		addRequirementCheck(met, missing, "Hitpoints", info.getRequirementHitpoints(), Skill.HITPOINTS, skillLevels);
		addRequirementCheck(met, missing, "Slayer", info.getRequirementSlayer(), Skill.SLAYER, skillLevels);
		return new ItemRequirementSummary(met, missing);
	}

	private ItemPriceSummary itemPriceSummary(ItemInspectInfo info)
	{
		if (info == null)
		{
			return null;
		}

		int gePrice = Math.max(0, itemManager.getItemPrice(info.getItemId()));
		Integer highAlch = coinValue(info.getHighAlch());
		Integer lowAlch = coinValue(info.getLowAlch());
		Integer itemValue = coinValue(info.getValue());
		if (highAlch == null && itemValue != null)
		{
			highAlch = (int) Math.floor(itemValue * 0.6d);
		}
		if (lowAlch == null && itemValue != null)
		{
			lowAlch = (int) Math.floor(itemValue * 0.4d);
		}
		Integer profitValue = null;
		String profit = null;
		if (gePrice > 0 && highAlch != null)
		{
			profitValue = highAlch - gePrice;
			profit = formatSignedCoins(profitValue);
		}

		return new ItemPriceSummary(
			gePrice > 0 ? formatCoins(gePrice) : null,
			highAlch == null ? info.getHighAlch() : formatCoins(highAlch),
			lowAlch == null ? info.getLowAlch() : formatCoins(lowAlch),
			profit,
			profitValue
		);
	}

	private static void addRequirementCheck(List<String> met, List<String> missing, String label, String requirement,
		Skill skill, Map<Skill, Integer> skillLevels)
	{
		Double required = requirementLevel(requirement);
		if (required == null)
		{
			return;
		}

		int current = skillLevels.getOrDefault(skill, 1);
		String summary = label + " " + required.intValue() + " (" + current + ")";
		if (current >= required)
		{
			met.add(summary);
			return;
		}

		missing.add(summary);
	}

	private static Double requirementLevel(String requirement)
	{
		if (requirement == null || requirement.isEmpty())
		{
			return null;
		}

		StringBuilder number = new StringBuilder();
		for (int i = 0; i < requirement.length(); i++)
		{
			char c = requirement.charAt(i);
			if (Character.isDigit(c))
			{
				number.append(c);
				continue;
			}

			if (number.length() > 0)
			{
				break;
			}
		}
		if (number.length() == 0)
		{
			return null;
		}
		return Double.parseDouble(number.toString());
	}

	private static Integer coinValue(String value)
	{
		if (value == null || value.isEmpty())
		{
			return null;
		}

		String digits = value.replace(",", "").replaceAll("[^0-9-]", "");
		if (digits.isEmpty() || "-".equals(digits))
		{
			return null;
		}

		try
		{
			return Integer.parseInt(digits);
		}
		catch (NumberFormatException ex)
		{
			return null;
		}
	}

	private static String formatSignedCoins(int value)
	{
		if (value == 0)
		{
			return "0 gp";
		}

		return (value > 0 ? "+" : "-") + formatCoins(Math.abs(value));
	}

	private static EquippedItem findEquippedComparison(ItemInspectInfo info, Map<EquipmentInventorySlot, EquippedItem> equippedItems)
	{
		EquipmentInventorySlot slot = equipmentSlot(info.getSlot());
		if (slot == null)
		{
			return null;
		}

		EquippedItem equippedItem = equippedItems.get(slot);
		if (equippedItem == null || equippedItem.itemId == info.getItemId())
		{
			return null;
		}

		return equippedItem;
	}

	private static EquipmentInventorySlot equipmentSlot(String wikiSlot)
	{
		if (wikiSlot == null)
		{
			return null;
		}

		switch (wikiSlot.toLowerCase(Locale.ENGLISH).trim())
		{
			case "head":
				return EquipmentInventorySlot.HEAD;
			case "cape":
				return EquipmentInventorySlot.CAPE;
			case "neck":
			case "amulet":
				return EquipmentInventorySlot.AMULET;
			case "weapon":
			case "2h":
			case "2h weapon":
				return EquipmentInventorySlot.WEAPON;
			case "body":
				return EquipmentInventorySlot.BODY;
			case "shield":
				return EquipmentInventorySlot.SHIELD;
			case "legs":
				return EquipmentInventorySlot.LEGS;
			case "hands":
			case "gloves":
				return EquipmentInventorySlot.GLOVES;
			case "feet":
			case "boots":
				return EquipmentInventorySlot.BOOTS;
			case "ring":
				return EquipmentInventorySlot.RING;
			case "ammo":
			case "ammunition":
				return EquipmentInventorySlot.AMMO;
			default:
				return null;
		}
	}

	private static boolean isNpcMenuAction(int type)
	{
		return type == MenuAction.NPC_FIRST_OPTION.getId()
			|| type == MenuAction.NPC_SECOND_OPTION.getId()
			|| type == MenuAction.NPC_THIRD_OPTION.getId()
			|| type == MenuAction.NPC_FOURTH_OPTION.getId()
			|| type == MenuAction.NPC_FIFTH_OPTION.getId()
			|| type == MenuAction.EXAMINE_NPC.getId();
	}

	private static boolean isItemMenuAction(int type)
	{
		return type == MenuAction.CC_OP.getId()
			|| type == MenuAction.CC_OP_LOW_PRIORITY.getId()
			|| type == MenuAction.WIDGET_FIRST_OPTION.getId()
			|| type == MenuAction.WIDGET_SECOND_OPTION.getId()
			|| type == MenuAction.WIDGET_THIRD_OPTION.getId()
			|| type == MenuAction.WIDGET_FOURTH_OPTION.getId()
			|| type == MenuAction.WIDGET_FIFTH_OPTION.getId()
			|| type == MenuAction.WIDGET_TARGET_ON_WIDGET.getId()
			|| type == INVENTORY_EXAMINE_ITEM_ACTION_ID;
	}

	private static boolean isInspectableItemWidget(MenuEntryAdded event)
	{
		if (event.getItemId() <= 0 || event.getActionParam1() < 0)
		{
			return false;
		}

		int interfaceId = WidgetUtil.componentToInterface(event.getActionParam1());
		return interfaceId == InterfaceID.INVENTORY
			|| interfaceId == InterfaceID.INVENTORY_NOOPS
			|| interfaceId == InterfaceID.BANKMAIN
			|| interfaceId == InterfaceID.BANKSIDE
			|| interfaceId == InterfaceID.BANK_DEPOSITBOX
			|| interfaceId == InterfaceID.SHARED_BANK
			|| interfaceId == InterfaceID.SHARED_BANK_SIDE
			|| interfaceId == InterfaceID.EQUIPMENT
			|| interfaceId == InterfaceID.EQUIPMENT_SIDE
			|| interfaceId == InterfaceID.WORNITEMS
			|| interfaceId == InterfaceID.GE_OFFERS
			|| interfaceId == InterfaceID.GE_OFFERS_SIDE
			|| interfaceId == InterfaceID.GE_PRICELIST
			|| interfaceId == InterfaceID.GE_VIEWONLY
			|| interfaceId == InterfaceID.SHOPMAIN
			|| interfaceId == InterfaceID.SHOPSIDE
			|| interfaceId == InterfaceID.OMNISHOP_MAIN
			|| interfaceId == InterfaceID.OMNISHOP_SIDE
			|| interfaceId == InterfaceID.MAGICTRAINING_SHOP
			|| interfaceId == InterfaceID.PEST_REWARDSHOP
			|| interfaceId == InterfaceID.BARBASSAULT_REWARD_SHOP
			|| interfaceId == InterfaceID.FOSSIL_VOLCANIC_SHOP
			|| interfaceId == InterfaceID.BR_REWARD_SHOP
			|| interfaceId == InterfaceID.LEAGUE_SKILLCAPES_SHOP
			|| interfaceId == InterfaceID.CONSTRUCTION_CONTRACT_SHOP
			|| interfaceId == InterfaceID.CAMDOZAAL_RAMARNO_SHOP
			|| interfaceId == InterfaceID.GIANTS_FOUNDRY_REWARD_SHOP
			|| interfaceId == InterfaceID.COLLECTION;
	}

	private static BufferedImage createIcon()
	{
		BufferedImage image = new BufferedImage(32, 32, BufferedImage.TYPE_INT_ARGB);
		Graphics2D graphics = image.createGraphics();
		graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

		graphics.setStroke(new BasicStroke(4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
		graphics.setColor(new Color(32, 32, 32, 160));
		graphics.drawOval(7, 6, 13, 13);
		graphics.drawLine(19, 19, 26, 26);

		graphics.setStroke(new BasicStroke(3f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
		graphics.setColor(new Color(235, 235, 235));
		graphics.drawOval(7, 6, 13, 13);
		graphics.drawLine(19, 19, 26, 26);

		graphics.setColor(new Color(255, 152, 31));
		graphics.setStroke(new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
		graphics.drawArc(9, 8, 9, 9, 85, 120);
		graphics.dispose();
		return image;
	}

	@Provides
	InspectConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(InspectConfig.class);
	}

	private static final class MenuMarker
	{
		private static final MenuMarker EMPTY = new MenuMarker(Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE, true);

		private final int param0;
		private final int param1;
		private final int identifier;
		private final int itemId;
		private final boolean matchIdentifier;

		private MenuMarker(int param0, int param1, int identifier, int itemId, boolean matchIdentifier)
		{
			this.param0 = param0;
			this.param1 = param1;
			this.identifier = identifier;
			this.itemId = itemId;
			this.matchIdentifier = matchIdentifier;
		}

		private static MenuMarker empty()
		{
			return EMPTY;
		}

		private static MenuMarker ofNpc(MenuEntryAdded event)
		{
			return new MenuMarker(event.getActionParam0(), event.getActionParam1(), event.getIdentifier(), event.getItemId(), true);
		}

		private static MenuMarker ofItem(MenuEntryAdded event)
		{
			return new MenuMarker(event.getActionParam0(), event.getActionParam1(), Integer.MIN_VALUE, event.getItemId(), false);
		}

		private boolean matches(MenuEntryAdded event)
		{
			return param0 == event.getActionParam0()
				&& param1 == event.getActionParam1()
				&& (!matchIdentifier || identifier == event.getIdentifier())
				&& itemId == event.getItemId();
		}
	}

	private static final class EquippedItem
	{
		private final int itemId;
		private final String itemName;

		private EquippedItem(int itemId, String itemName)
		{
			this.itemId = itemId;
			this.itemName = itemName;
		}
	}

	private static final class ItemInspectResult
	{
		private final ItemInspectInfo info;
		private final ItemInspectInfo equippedInfo;

		private ItemInspectResult(ItemInspectInfo info, ItemInspectInfo equippedInfo)
		{
			this.info = info;
			this.equippedInfo = equippedInfo;
		}
	}
}
