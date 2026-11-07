package me.medieval.shops.bank;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

import me.medieval.shops.MedievalEconomy;
import me.medieval.shops.currency.Currencies;
import me.medieval.shops.currency.Currency;
import me.medieval.shops.currency.CurrencySet;

/**
 * Bancos. Cada mercader banquero es un banco independiente (con su propia moneda y sus propias
 * cuentas). Ademas existe un banco "global" que se usa con el comando /banco.
 * Los saldos se guardan en "monedas base" de ese banco.
 */
public final class BankManager {

	public static final String GLOBAL = "global";

	private final MedievalEconomy economy;
	private final File file;
	// banco -> (jugador -> saldo)
	private final Map<String, Map<UUID, Long>> banks = new HashMap<>();
	private final Map<UUID, String> names = new HashMap<>();
	private boolean dirty = false;
	private int minuteCounter = 0;

	private long balanceLimit;
	private double interestPercent;
	private long interestMax;
	private double transferTaxPercent;

	public BankManager(MedievalEconomy economy) {
		this.economy = economy;
		this.file = new File(economy.getPlugin().getDataFolder(), "bank-data.yml");
		reloadConfig();
	}

	public void reloadConfig() {
		FileConfiguration c = economy.config();
		balanceLimit = c.getLong("banco.limite-saldo", 0L);
		interestPercent = c.getDouble("banco.interes-por-hora-porcentaje", 0.0D);
		interestMax = c.getLong("banco.interes-maximo-por-hora", 1000L);
		transferTaxPercent = c.getDouble("banco.impuesto-transferencia-porcentaje", 0.0D);
	}

	public boolean isEnabled() {
		return economy.config().getBoolean("banco.habilitado", true);
	}

	public boolean isGlobalAllowed() {
		return economy.config().getBoolean("banco.permitir-comando-global", true);
	}

	public static String keyOf(UUID shopkeeperId) {
		return shopkeeperId.toString();
	}

	/** Monedas que usa un banco. */
	public CurrencySet currencies(String bank) {
		if (GLOBAL.equals(bank)) return Currencies.global();
		try {
			return Currencies.of(UUID.fromString(bank));
		} catch (IllegalArgumentException e) {
			return Currencies.global();
		}
	}

	// ---------------------------------------------------------------- Persistencia

	public void load() {
		banks.clear();
		names.clear();
		if (!file.exists()) return;
		YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
		ConfigurationSection bankSection = yaml.getConfigurationSection("bancos");
		if (bankSection != null) {
			for (String bank : bankSection.getKeys(false)) {
				ConfigurationSection accounts = bankSection.getConfigurationSection(bank);
				if (accounts == null) continue;
				Map<UUID, Long> map = new HashMap<>();
				for (String key : accounts.getKeys(false)) {
					try {
						map.put(UUID.fromString(key), accounts.getLong(key, 0L));
					} catch (IllegalArgumentException ignored) {
						// Clave invalida.
					}
				}
				banks.put(bank, map);
			}
		}
		// Datos de la version anterior (un solo banco): pasan al banco global.
		ConfigurationSection legacy = yaml.getConfigurationSection("cuentas");
		if (legacy != null) {
			Map<UUID, Long> map = banks.computeIfAbsent(GLOBAL, k -> new HashMap<>());
			for (String key : legacy.getKeys(false)) {
				try {
					UUID id = UUID.fromString(key);
					map.putIfAbsent(id, legacy.getLong(key + ".saldo", 0L));
					String name = legacy.getString(key + ".nombre");
					if (name != null) names.put(id, name);
				} catch (IllegalArgumentException ignored) {
					// Clave invalida.
				}
			}
		}
		ConfigurationSection nameSection = yaml.getConfigurationSection("nombres");
		if (nameSection != null) {
			for (String key : nameSection.getKeys(false)) {
				try {
					names.put(UUID.fromString(key), nameSection.getString(key, key));
				} catch (IllegalArgumentException ignored) {
					// Clave invalida.
				}
			}
		}
	}

	public void save() {
		YamlConfiguration yaml = new YamlConfiguration();
		for (Map.Entry<String, Map<UUID, Long>> bank : banks.entrySet()) {
			for (Map.Entry<UUID, Long> account : bank.getValue().entrySet()) {
				yaml.set("bancos." + bank.getKey() + "." + account.getKey(), account.getValue());
			}
		}
		for (Map.Entry<UUID, String> name : names.entrySet()) {
			yaml.set("nombres." + name.getKey(), name.getValue());
		}
		try {
			economy.getPlugin().getDataFolder().mkdirs();
			yaml.save(file);
			dirty = false;
		} catch (IOException e) {
			economy.getPlugin().getLogger().warning("No se pudo guardar bank-data.yml: " + e.getMessage());
		}
	}

	public void tickMinute() {
		minuteCounter++;
		if (minuteCounter % 60 == 0 && interestPercent > 0.0D) {
			for (Map<UUID, Long> accounts : banks.values()) {
				for (Map.Entry<UUID, Long> entry : accounts.entrySet()) {
					long interest = (long) Math.floor(entry.getValue() * interestPercent / 100.0D);
					interest = Math.min(interest, interestMax);
					if (interest > 0) {
						entry.setValue(clamp(entry.getValue() + interest));
						dirty = true;
					}
				}
			}
		}
		if (dirty && minuteCounter % 2 == 0) save();
	}

	// ---------------------------------------------------------------- Cuentas

	private long clamp(long value) {
		if (balanceLimit > 0 && value > balanceLimit) return balanceLimit;
		return Math.max(0L, value);
	}

	private Map<UUID, Long> accounts(String bank) {
		return banks.computeIfAbsent(bank, k -> new HashMap<>());
	}

	public long getBalance(String bank, UUID id) {
		Map<UUID, Long> map = banks.get(bank);
		return map == null ? 0L : map.getOrDefault(id, 0L);
	}

	public long getLimit() {
		return balanceLimit;
	}

	public double getTransferTaxPercent() {
		return transferTaxPercent;
	}

	public double getInterestPercent() {
		return interestPercent;
	}

	public void remember(UUID id, String name) {
		if (name != null && !name.equals(names.put(id, name))) dirty = true;
	}

	public UUID findByName(String name) {
		for (Map.Entry<UUID, String> entry : names.entrySet()) {
			if (entry.getValue().equalsIgnoreCase(name)) return entry.getKey();
		}
		Player online = Bukkit.getPlayerExact(name);
		if (online != null) return online.getUniqueId();
		return null;
	}

	public void setBalance(String bank, UUID id, long value) {
		accounts(bank).put(id, clamp(value));
		dirty = true;
	}

	/** @return false si el saldo resultante seria negativo o supera el limite. */
	public boolean add(String bank, UUID id, long delta) {
		long result = getBalance(bank, id) + delta;
		if (result < 0) return false;
		if (balanceLimit > 0 && result > balanceLimit) return false;
		accounts(bank).put(id, result);
		dirty = true;
		return true;
	}

	public Map<UUID, Long> all(String bank) {
		return accounts(bank);
	}

	public String nameOf(UUID id) {
		String name = names.get(id);
		return name != null ? name : id.toString().substring(0, 8);
	}

	// ---------------------------------------------------------------- Inventario

	public static int countCurrency(PlayerInventory inventory, Currency currency) {
		int count = 0;
		for (ItemStack item : inventory.getStorageContents()) {
			if (item != null && currency.getItemData().matches(item)) {
				count += item.getAmount();
			}
		}
		return count;
	}

	/** Quita hasta {@code amount} monedas. @return cuantas se quitaron. */
	public static int removeCurrency(PlayerInventory inventory, Currency currency, int amount) {
		int remaining = amount;
		ItemStack[] contents = inventory.getStorageContents();
		for (int i = 0; i < contents.length && remaining > 0; i++) {
			ItemStack item = contents[i];
			if (item == null || !currency.getItemData().matches(item)) continue;
			int take = Math.min(item.getAmount(), remaining);
			remaining -= take;
			if (take >= item.getAmount()) {
				contents[i] = null;
			} else {
				item.setAmount(item.getAmount() - take);
			}
		}
		inventory.setStorageContents(contents);
		return amount - remaining;
	}

	/** Cuantas monedas caben ahora mismo en el inventario (huecos vacios + pilas incompletas). */
	public static int capacity(PlayerInventory inventory, Currency currency) {
		long total = 0;
		int stackSize = Math.max(1, currency.getMaxStackSize());
		for (ItemStack item : inventory.getStorageContents()) {
			if (item == null || item.getType() == org.bukkit.Material.AIR) total += stackSize;
			else if (currency.getItemData().matches(item)) total += Math.max(0, stackSize - item.getAmount());
		}
		return (int) Math.min(Integer.MAX_VALUE, total);
	}

	/** Entrega monedas. @return cuantas NO cupieron en el inventario. */
	public static int giveCurrency(PlayerInventory inventory, Currency currency, long amount) {
		long remaining = amount;
		int notFit = 0;
		int stackSize = Math.max(1, currency.getMaxStackSize());
		while (remaining > 0) {
			int give = (int) Math.min(stackSize, remaining);
			ItemStack stack = currency.getItemData().createItemStack(give);
			Map<Integer, ItemStack> left = inventory.addItem(stack);
			for (ItemStack leftover : left.values()) {
				notFit += leftover.getAmount();
			}
			remaining -= give;
		}
		return notFit;
	}

	/** Deposita monedas de un tipo. @return cuantas monedas se depositaron realmente. */
	public int deposit(Player player, String bank, Currency currency, int requested) {
		if (requested <= 0) return 0;
		int available = countCurrency(player.getInventory(), currency);
		int amount = Math.min(requested, available);
		if (amount <= 0) return 0;
		long current = getBalance(bank, player.getUniqueId());
		if (balanceLimit > 0 && current + (long) amount * currency.getValue() > balanceLimit) {
			long room = balanceLimit - current;
			amount = (int) Math.min(amount, room / Math.max(1, currency.getValue()));
			if (amount <= 0) return 0;
		}
		int removed = removeCurrency(player.getInventory(), currency, amount);
		if (removed <= 0) return 0;
		add(bank, player.getUniqueId(), (long) removed * currency.getValue());
		remember(player.getUniqueId(), player.getName());
		return removed;
	}

	/** Retira monedas de un tipo. @return cuantas monedas se entregaron. */
	public int withdraw(Player player, String bank, Currency currency, int requested) {
		if (requested <= 0) return 0;
		long balance = getBalance(bank, player.getUniqueId());
		long maxByBalance = balance / Math.max(1, currency.getValue());
		int amount = (int) Math.min(requested, maxByBalance);
		// Solo se retira lo que cabe: asi retirar 'todo' con un saldo enorme no genera millones de pilas.
		amount = Math.min(amount, capacity(player.getInventory(), currency));
		if (amount <= 0) return 0;
		add(bank, player.getUniqueId(), -(long) amount * currency.getValue());
		int notFit = giveCurrency(player.getInventory(), currency, amount);
		if (notFit > 0) {
			// Lo que no cabe vuelve a la cuenta.
			add(bank, player.getUniqueId(), (long) notFit * currency.getValue());
		}
		return amount - notFit;
	}

	// ---------------------------------------------------------------- Formato

	public static String format(long units, CurrencySet set) {
		Currency base = set.getBase();
		Currency high = set.getHighOrNull();
		StringBuilder sb = new StringBuilder();
		sb.append(units).append(' ').append(base.getDisplayName());
		if (high != null && units > 0) {
			long hv = Math.max(1, high.getValue());
			sb.append(" (").append(units / hv).append(' ').append(high.getDisplayName()).append(" + ")
					.append(units % hv).append(' ').append(base.getDisplayName()).append(')');
		}
		return sb.toString();
	}
}
