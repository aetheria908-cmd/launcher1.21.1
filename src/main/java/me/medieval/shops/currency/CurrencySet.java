package me.medieval.shops.currency;

import org.bukkit.inventory.ItemStack;

/** Par de monedas (base + alta opcional) que usa un mercader o un banco. */
public final class CurrencySet {

	private final Currency base;
	private final Currency high; // puede ser null

	CurrencySet(Currency base, Currency high) {
		this.base = base;
		this.high = high;
	}

	public Currency getBase() {
		return base;
	}

	/** @return la moneda alta o null si no hay */
	public Currency getHighOrNull() {
		return high;
	}

	public Currency match(ItemStack item) {
		if (item == null) return null;
		if (base.getItemData().matches(item)) return base;
		if (high != null && high.getItemData().matches(item)) return high;
		return null;
	}
}
