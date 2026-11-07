package me.medieval.shops.currency;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

/** Item de moneda: se compara con TODOS sus datos (nombre, lore, modelo, NBT...) salvo la cantidad. */
public final class ItemData {

	private final ItemStack template; // cantidad 1

	ItemData(ItemStack template) {
		this.template = template.clone();
		this.template.setAmount(1);
	}

	public Material getType() {
		return template.getType();
	}

	/** Copia del item tal cual (para mostrarlo en menus). */
	public ItemStack getTemplate() {
		return template.clone();
	}

	public boolean matches(ItemStack item) {
		return item != null && item.getType() == template.getType() && item.isSimilar(template);
	}

	public ItemStack createItemStack(int amount) {
		ItemStack copy = template.clone();
		copy.setAmount(Math.max(1, amount));
		return copy;
	}
}
