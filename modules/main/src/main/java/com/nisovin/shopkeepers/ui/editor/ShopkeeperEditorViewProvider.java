package com.nisovin.shopkeepers.ui.editor;

import org.bukkit.entity.Player;
import org.checkerframework.checker.nullness.qual.Nullable;

import com.nisovin.shopkeepers.shopkeeper.AbstractShopkeeper;
import com.nisovin.shopkeepers.ui.ShopkeeperViewContext;
import com.nisovin.shopkeepers.ui.ShopkeeperViewProvider;
import com.nisovin.shopkeepers.ui.lib.AbstractUIType;
import com.nisovin.shopkeepers.ui.lib.UIState;

public abstract class ShopkeeperEditorViewProvider extends AbstractEditorViewProvider
		implements ShopkeeperViewProvider {

	protected ShopkeeperEditorViewProvider(
			AbstractUIType uiType,
			AbstractShopkeeper shopkeeper,
			TradingRecipesAdapter tradingRecipesAdapter
	) {
		super(uiType, new ShopkeeperViewContext(shopkeeper), tradingRecipesAdapter);
	}

	@Override
	public ShopkeeperViewContext getContext() {
		return (ShopkeeperViewContext) super.getContext();
	}

	@Override
	public AbstractShopkeeper getShopkeeper() {
		return this.getContext().getObject();
	}

	public @Nullable ShopkeeperEditorView createPreparedView(Player player, UIState uiState) {
		var view = this.createView(player, uiState);
		if (!(view instanceof ShopkeeperEditorView editor)) return null;
		editor.prepareOwnerState();
		return editor;
	}
}
