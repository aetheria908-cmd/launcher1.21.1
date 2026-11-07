package me.medieval.shops.currency;

import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import me.medieval.shops.MedievalEconomy;

public final class Currency {

	private final ItemData itemData;
	private final int value;

	Currency(ItemStack template, int value) {
		this.itemData = new ItemData(template);
		this.value = Math.max(1, value);
	}

	public ItemData getItemData() {
		return itemData;
	}

	/** Valor en monedas base. */
	public int getValue() {
		return value;
	}

	public int getMaxStackSize() {
		return Math.max(1, itemData.getType().getMaxStackSize());
	}

	/** Nombre del item si tiene uno propio, si no el del material. */
	public String getDisplayName() {
		ItemMeta meta = itemData.getTemplate().getItemMeta();
		if (meta != null && meta.hasDisplayName()) return meta.getDisplayName();
		return MedievalEconomy.pretty(itemData.getType().name());
	}
}
