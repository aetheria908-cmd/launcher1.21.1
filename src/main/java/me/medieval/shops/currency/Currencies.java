package me.medieval.shops.currency;

import java.io.File;
import java.util.Map;
import java.util.UUID;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import me.medieval.shops.MedievalEconomy;

/**
 * Monedas del servidor. Por defecto se leen de la config de Shopkeepers (currency-item,
 * high-currency-item, high-currency-value), asi coinciden con las de tus tiendas.
 */
public final class Currencies {

	private static CurrencySet global = new CurrencySet(new Currency(new ItemStack(Material.EMERALD), 1),
			new Currency(new ItemStack(Material.EMERALD_BLOCK), 9));

	/** Monedas globales del servidor (config de Shopkeepers / addon). */
	public static CurrencySet global() {
		return global;
	}

	public static Currency getBase() {
		return global.getBase();
	}

	/** @return la moneda alta global o null si esta desactivada */
	public static Currency getHighOrNull() {
		return global.getHighOrNull();
	}

	public static Currency match(ItemStack item) {
		return global.match(item);
	}

	/** Crea un par de monedas a partir de items completos. high puede ser null (= sin moneda alta). */
	public static CurrencySet create(ItemStack base, ItemStack high, int highValue) {
		Currency b = new Currency(base, 1);
		Currency h = (high == null || high.getType() == Material.AIR) ? null
				: new Currency(high, Math.max(2, highValue));
		return new CurrencySet(b, h);
	}

	/** Crea un par de monedas simples (solo por material). */
	public static CurrencySet create(Material base, Material high, int highValue) {
		return create(new ItemStack(base), (high == null || high == Material.AIR) ? null : new ItemStack(high),
				highValue);
	}

	/** Fija las monedas globales (se guardan en currencies.yml y ganan a la config de Shopkeepers). */
	public static void setGlobal(JavaPlugin plugin, ItemStack base, ItemStack highOrNull, int value) {
		global = create(base, highOrNull, value);
		YamlConfiguration yaml = new YamlConfiguration();
		ItemStack b = base.clone();
		b.setAmount(1);
		yaml.set("global.base", b);
		if (highOrNull != null && highOrNull.getType() != Material.AIR) {
			ItemStack h = highOrNull.clone();
			h.setAmount(1);
			yaml.set("global.alta", h);
		}
		yaml.set("global.valor", Math.max(2, value));
		try {
			plugin.getDataFolder().mkdirs();
			yaml.save(new File(plugin.getDataFolder(), "currencies.yml"));
		} catch (java.io.IOException e) {
			plugin.getLogger().warning("No se pudo guardar currencies.yml: " + e.getMessage());
		}
	}

	/** Borra la moneda global elegida por comando (vuelve a la config de Shopkeepers / addon). */
	public static void clearGlobal(JavaPlugin plugin, FileConfiguration config) {
		new File(plugin.getDataFolder(), "currencies.yml").delete();
		load(plugin, config);
	}

	/** Monedas de un mercader concreto (las suyas si las configuraste, si no las globales). */
	public static CurrencySet of(UUID shopkeeperId) {
		MedievalEconomy economy = MedievalEconomy.get();
		if (economy == null || economy.profiles() == null || shopkeeperId == null) return global;
		return economy.profiles().currencies(shopkeeperId, global);
	}

	private static Material material(Object value) {
		if (value == null) return null;
		String name = null;
		if (value instanceof String s) {
			name = s;
		} else if (value instanceof Map<?, ?> map) {
			Object type = map.get("type");
			if (type != null) name = String.valueOf(type);
		} else if (value instanceof ConfigurationSection section) {
			name = section.getString("type");
		}
		if (name == null) return null;
		name = name.trim();
		if (name.equalsIgnoreCase("AIR") || name.equalsIgnoreCase("minecraft:air")) return Material.AIR;
		return Material.matchMaterial(name);
	}

	public static void load(JavaPlugin plugin, FileConfiguration config) {
		// 1) Moneda elegida con /mercadoadmin moneda (item completo, con su nombre y datos)
		File chosen = new File(plugin.getDataFolder(), "currencies.yml");
		if (chosen.exists()) {
			YamlConfiguration y = YamlConfiguration.loadConfiguration(chosen);
			ItemStack b = y.getItemStack("global.base");
			if (b != null && b.getType() != Material.AIR) {
				global = create(b, y.getItemStack("global.alta"), y.getInt("global.valor", 9));
				plugin.getLogger().info("Moneda global (elegida por comando): " + global.getBase().getDisplayName());
				return;
			}
		}
		Material baseType = Material.matchMaterial(config.getString("moneda.base", "EMERALD"));
		Material highType = Material.matchMaterial(config.getString("moneda.alta", "EMERALD_BLOCK"));
		int highValue = config.getInt("moneda.valor-alta", 9);

		if (config.getBoolean("moneda.leer-de-shopkeepers", true)) {
			File file = new File(plugin.getDataFolder().getParentFile(), "Shopkeepers/config.yml");
			if (file.exists()) {
				YamlConfiguration sk = YamlConfiguration.loadConfiguration(file);
				Material b = material(sk.get("currency-item"));
				Material h = material(sk.get("high-currency-item"));
				if (b != null && b != Material.AIR) baseType = b;
				if (sk.contains("high-currency-item") && h != null) highType = h;
				if (sk.contains("high-currency-value")) highValue = sk.getInt("high-currency-value", 9);
				plugin.getLogger().info("Monedas leidas de la config de Shopkeepers: " + baseType
						+ (highType == Material.AIR ? "" : " / " + highType + " (x" + highValue + ")"));
			} else {
				plugin.getLogger().info("No se encontro plugins/Shopkeepers/config.yml, usando monedas del addon.");
			}
		}
		if (baseType == null) baseType = Material.EMERALD;
		global = create(baseType, highType, highValue);
	}

	private Currencies() {
	}
}
