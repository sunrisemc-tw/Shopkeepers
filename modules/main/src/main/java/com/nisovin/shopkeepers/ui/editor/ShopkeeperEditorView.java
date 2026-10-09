package com.nisovin.shopkeepers.ui.editor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.checkerframework.checker.nullness.qual.Nullable;

import com.nisovin.shopkeepers.SKShopkeepersPlugin;
import com.nisovin.shopkeepers.api.events.ShopkeeperEditedEvent;
import com.nisovin.shopkeepers.api.ui.DefaultUITypes;
import com.nisovin.shopkeepers.lang.Messages;
import com.nisovin.shopkeepers.shopkeeper.TradingRecipeDraft;
import com.nisovin.shopkeepers.ui.lib.UISessionManager;
import com.nisovin.shopkeepers.ui.lib.UIState;
import com.nisovin.shopkeepers.util.inventory.ItemUtils;
import com.nisovin.shopkeepers.util.logging.Log;

public abstract class ShopkeeperEditorView extends EditorView {

	private @Nullable List<TradingRecipeDraft> initialRecipes;
	private volatile Map<Object, ItemStack> ownerIcons = Map.of();

	protected ShopkeeperEditorView(
			ShopkeeperEditorViewProvider viewProvider,
			Player player,
			UIState uiState
	) {
		super(viewProvider, player, uiState);
	}

	@Override
	protected ShopkeeperEditorLayout createLayout() {
		return new ShopkeeperEditorLayout(this);
	}

	@Override
	protected String getTitle() {
		return Messages.editorTitle;
	}

	public void prepareOwnerState() {
		initialRecipes = this.copyRecipes(this.getTradingRecipesAdapter().getTradingRecipes());
		this.captureOwnerIcons();
	}

	private void captureOwnerIcons() {
		Map<Object, ItemStack> icons = new HashMap<>();
		var layout = this.getLayout();
		for (Button button : layout.getBakedButtons()) {
			if (button == null) continue;
			ItemStack icon = button.getIcon();
			if (icon != null) icons.put(button.getIdentity(), icon.clone());
		}

		for (Button button : layout.getTradesPageBarButtons()) {
			if (button == null || (button.getSlot() != EditorLayout.SHOP_INFORMATION_ICON
					&& button.getSlot() != EditorLayout.TRADES_SETUP_ICON)) continue;
			// Page controls depend on player-owned editor state, not the shop.
			ItemStack icon = button.getIcon();
			if (icon != null) icons.put(button.getIdentity(), icon.clone());
		}

		ownerIcons = Map.copyOf(icons);
	}

	@Override
	protected List<TradingRecipeDraft> getInitialRecipes() {
		List<TradingRecipeDraft> recipes = initialRecipes;
		return recipes != null ? recipes : super.getInitialRecipes();
	}

	@Override
	protected @Nullable ItemStack getButtonIcon(Button button) {
		if (!SKShopkeepersPlugin.getInstance().getFoliaLib().isFolia()
				|| (button.getSlot() < EditorLayout.BUTTONS_START
						&& button.getSlot() != EditorLayout.SHOP_INFORMATION_ICON
						&& button.getSlot() != EditorLayout.TRADES_SETUP_ICON)) {
			return super.getButtonIcon(button);
		}

		return ItemUtils.cloneOrNullIfEmpty(ownerIcons.get(button.getIdentity()));
	}

	@Override
	protected void updateButtons() {
		if (!SKShopkeepersPlugin.getInstance().getFoliaLib().isFolia()) {
			super.updateButtons();
			return;
		}

		this.refreshOwnerIcons(() -> super.updateButtons());
	}

	@Override
	void updateButton(Object buttonIdentity) {
		if (!SKShopkeepersPlugin.getInstance().getFoliaLib().isFolia()) {
			super.updateButton(buttonIdentity);
			return;
		}

		this.refreshOwnerIcons(() -> super.updateButton(buttonIdentity));
	}

	private void refreshOwnerIcons(Runnable update) {
		var registry = SKShopkeepersPlugin.getInstance().getShopkeeperRegistry();
		registry.runOnOwner(this.getShopkeeperNonNull(), () -> {
			if (!this.isValid() || !this.getShopkeeperNonNull().isValid()) return false;
			this.captureOwnerIcons();
			return true;
		}).thenAccept(success -> {
			if (success) registry.runOnSender(this.getPlayer(), () -> {
				if (this.isValid()) update.run();
			});
		});
	}

	private List<TradingRecipeDraft> copyRecipes(List<TradingRecipeDraft> recipes) {
		List<TradingRecipeDraft> snapshot = new ArrayList<>(recipes.size());
		recipes.forEach(recipe -> snapshot.add(new TradingRecipeDraft(
				ItemUtils.copyOrNull(recipe.getResultItem()),
				ItemUtils.copyOrNull(recipe.getItem1()),
				ItemUtils.copyOrNull(recipe.getItem2()))));
		return snapshot;
	}

	@Override
	protected void saveRecipes() {
		List<TradingRecipeDraft> recipes = this.getRecipes();
		if (SKShopkeepersPlugin.getInstance().getFoliaLib().isFolia()) {
			List<TradingRecipeDraft> snapshot = this.copyRecipes(recipes);
			SKShopkeepersPlugin.getInstance().getShopkeeperRegistry()
					.runOnOwner(this.getShopkeeperNonNull(), () -> {
						if (!this.getShopkeeperNonNull().isValid()) return false;
						this.applyRecipes(snapshot);
						return true;
					}).whenComplete((success, error) -> {
						if (error != null) {
							SKShopkeepersPlugin.getInstance().getShopkeeperRegistry()
									.runOnSender(this.getPlayer(), () -> this.getPlayer()
											.sendMessage("Shopkeeper offers could not be saved."));
						}
					});
			return;
		}

		this.applyRecipes(recipes);
	}

	private void applyRecipes(List<TradingRecipeDraft> recipes) {
		var player = this.getPlayer();
		var shopkeeper = this.getShopkeeperNonNull();

		// UI sessions are aborted (i.e. not saved) when the shopkeeper is removed:
		assert shopkeeper.isValid();

		int changedOffers = this.getTradingRecipesAdapter().updateTradingRecipes(
				player,
				recipes
		);
		if (changedOffers == 0) {
			Log.debug(() -> this.getContext().getLogPrefix() + "No offers have changed.");
		} else {
			Log.debug(() -> this.getContext().getLogPrefix() + changedOffers
					+ " offers have changed.");

			// Call event:
			Bukkit.getPluginManager().callEvent(new ShopkeeperEditedEvent(shopkeeper, player));

			// Close any open trading UIs, so that players do not continue trading based on outdated
			// offers:
			// Note: Any trade attempts during the UI closing delay are catched by the trading view
			// implementations by validating that the found offer still matches the selected trading
			// recipe.
			// TODO Also send a message to them?
			UISessionManager.getInstance().abortUISessionsForContextDelayed(
					shopkeeper.getLocation(),
					shopkeeper,
					DefaultUITypes.TRADING()
			);
		}

		// Even if no trades have changed, the shopkeeper might have been marked as dirty due to
		// other editor options. If this is the case, we trigger a save here. Otherwise, we omit the
		// save.
		if (shopkeeper.isDirty()) {
			shopkeeper.save();
		}
	}
}
