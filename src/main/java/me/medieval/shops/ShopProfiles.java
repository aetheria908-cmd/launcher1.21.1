package me.medieval.shops;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;

import me.medieval.shops.currency.Currencies;
import me.medieval.shops.currency.CurrencySet;

/**
 * Ajustes propios de cada mercader: si es banquero y que moneda usa (para su banco y para sus
 * precios dinamicos). Las monedas son items COMPLETOS (con su nombre, lore, modelo, etc.).
 */
public final class ShopProfiles {

	private static final class Profile {
		boolean banker = false;
		boolean custom = false; // true = usa moneda propia
		ItemStack base = null;
		ItemStack high = null;  // null = sin moneda alta
		int value = 9;
		Boolean stockOn = null; // null = usa la config
		int stockMax = 0;       // 0 = usa la config
		CurrencySet cached = null;
	}

	private final MedievalEconomy economy;
	private final File file;
	private final Map<UUID, Profile> profiles = new HashMap<>();

	public ShopProfiles(MedievalEconomy economy) {
		this.economy = economy;
		this.file = new File(economy.getPlugin().getDataFolder(), "shop-profiles.yml");
	}

	private static ItemStack one(ItemStack item) {
		if (item == null || item.getType() == Material.AIR) return null;
		ItemStack copy = item.clone();
		copy.setAmount(1);
		return copy;
	}

	public void load() {
		profiles.clear();
		if (!file.exists()) return;
		YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
		ConfigurationSection section = yaml.getConfigurationSection("mercaderes");
		if (section == null) return;
		for (String key : section.getKeys(false)) {
			try {
				UUID id = UUID.fromString(key);
				ConfigurationSection s = section.getConfigurationSection(key);
				if (s == null) continue;
				Profile p = new Profile();
				p.banker = s.getBoolean("banquero", false);
				p.custom = s.getBoolean("moneda-propia", false);
				p.base = s.getItemStack("base");
				p.high = s.getItemStack("alta");
				p.value = Math.max(2, s.getInt("valor", 9));
				if (s.contains("stock")) p.stockOn = s.getBoolean("stock");
				p.stockMax = Math.max(0, s.getInt("stock-maximo", 0));
				profiles.put(id, p);
			} catch (IllegalArgumentException ignored) {
				// Clave invalida.
			}
		}
	}

	public void save() {
		YamlConfiguration yaml = new YamlConfiguration();
		for (Map.Entry<UUID, Profile> entry : profiles.entrySet()) {
			Profile p = entry.getValue();
			if (!p.banker && !p.custom && p.stockOn == null && p.stockMax <= 0) continue;
			String base = "mercaderes." + entry.getKey();
			yaml.set(base + ".banquero", p.banker);
			yaml.set(base + ".moneda-propia", p.custom);
			if (p.base != null) yaml.set(base + ".base", p.base);
			if (p.high != null) yaml.set(base + ".alta", p.high);
			yaml.set(base + ".valor", p.value);
			if (p.stockOn != null) yaml.set(base + ".stock", p.stockOn);
			if (p.stockMax > 0) yaml.set(base + ".stock-maximo", p.stockMax);
		}
		try {
			economy.getPlugin().getDataFolder().mkdirs();
			yaml.save(file);
		} catch (IOException e) {
			economy.getPlugin().getLogger().warning("No se pudo guardar shop-profiles.yml: " + e.getMessage());
		}
	}

	private Profile profile(UUID id) {
		return profiles.computeIfAbsent(id, k -> new Profile());
	}

	// ---------------------------------------------------------------- Banquero

	public boolean isBanker(UUID id) {
		Profile p = profiles.get(id);
		return p != null && p.banker;
	}

	public void setBanker(UUID id, boolean banker) {
		profile(id).banker = banker;
		save();
	}

	// ---------------------------------------------------------------- Stock

	public boolean stockEnabled(UUID id, boolean def) {
		Profile p = profiles.get(id);
		return p == null || p.stockOn == null ? def : p.stockOn;
	}

	public int stockMax(UUID id, int def) {
		Profile p = profiles.get(id);
		return p == null || p.stockMax <= 0 ? def : p.stockMax;
	}

	public void setStockEnabled(UUID id, Boolean on) {
		profile(id).stockOn = on;
		save();
	}

	public void setStockMax(UUID id, int max) {
		profile(id).stockMax = Math.max(0, Math.min(2304, max));
		save();
	}

	// ---------------------------------------------------------------- Moneda

	public boolean hasCustomCurrency(UUID id) {
		Profile p = profiles.get(id);
		return p != null && p.custom;
	}

	public int getHighValue(UUID id) {
		Profile p = profiles.get(id);
		return p == null ? 9 : p.value;
	}

	public CurrencySet currencies(UUID id, CurrencySet global) {
		Profile p = profiles.get(id);
		if (p == null || !p.custom) return global;
		if (p.cached == null) {
			ItemStack base = p.base != null ? p.base : global.getBase().getItemData().getTemplate();
			p.cached = Currencies.create(base, p.high, p.value);
		}
		return p.cached;
	}

	/** Parte de las monedas globales la primera vez que se personaliza. */
	private void startCustom(Profile p) {
		if (p.custom) return;
		CurrencySet g = Currencies.global();
		p.base = g.getBase().getItemData().getTemplate();
		p.high = g.getHighOrNull() == null ? null : g.getHighOrNull().getItemData().getTemplate();
		p.value = g.getHighOrNull() == null ? 9 : g.getHighOrNull().getValue();
		p.custom = true;
	}

	/** Moneda base = el item completo dado (nombre, lore, modelo, NBT). */
	public void setBase(UUID id, ItemStack item) {
		Profile p = profile(id);
		startCustom(p);
		p.base = one(item);
		p.cached = null;
		save();
	}

	/** high = null para quitar la moneda alta. */
	public void setHigh(UUID id, ItemStack item, int value) {
		Profile p = profile(id);
		startCustom(p);
		p.high = one(item);
		if (value >= 2) p.value = value;
		p.cached = null;
		save();
	}

	public void setHighValue(UUID id, int value) {
		Profile p = profile(id);
		startCustom(p);
		p.value = Math.max(2, Math.min(64, value));
		p.cached = null;
		save();
	}

	public void clearCurrency(UUID id) {
		Profile p = profiles.get(id);
		if (p == null) return;
		p.custom = false;
		p.cached = null;
		save();
	}

	public void invalidateCaches() {
		for (Profile p : profiles.values()) p.cached = null;
	}
}
