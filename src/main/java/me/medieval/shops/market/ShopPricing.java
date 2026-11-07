package me.medieval.shops.market;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;

import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;

import com.nisovin.shopkeepers.api.ShopkeepersAPI;
import com.nisovin.shopkeepers.api.shopkeeper.Shopkeeper;
import com.nisovin.shopkeepers.api.shopkeeper.admin.regular.RegularAdminShopkeeper;
import com.nisovin.shopkeepers.api.shopkeeper.offers.PriceOffer;
import com.nisovin.shopkeepers.api.shopkeeper.offers.TradeOffer;
import com.nisovin.shopkeepers.api.shopkeeper.player.buy.BuyingPlayerShopkeeper;
import com.nisovin.shopkeepers.api.shopkeeper.player.sell.SellingPlayerShopkeeper;

import me.medieval.shops.MedievalEconomy;
import me.medieval.shops.currency.Currencies;
import me.medieval.shops.currency.Currency;
import me.medieval.shops.currency.CurrencySet;
import me.medieval.shops.gui.Explain;

/**
 * Precios dinamicos SIN tocar el codigo de Shopkeepers: las tiendas "marcadas" por un admin
 * guardan sus precios base y este addon reescribe sus ofertas con la API cada minuto (y despues
 * de cada compra) segun la demanda y los eventos del mercado.
 */
public final class ShopPricing {

	private static final class Entry {
		// Tienda de admin
		ItemStack result;
		ItemStack item1;
		ItemStack item2;
		// Tienda de jugador (venta o compra)
		ItemStack item;
		int price;
		boolean buying; // tienda de jugador que COMPRA objetos
		UUID shop;             // mercader al que pertenece (tiendas de admin)
		double stock = -1.0D;  // existencias de la tienda (-1 = sin iniciar)
		double applied = 1.0D; // multiplicador con el que se aplico por ultima vez esta oferta
	}

	private final MedievalEconomy economy;
	private final MarketManager market;
	private final File file;
	private final Map<UUID, List<Entry>> marked = new LinkedHashMap<>();
	private final java.util.Set<UUID> excluded = new java.util.HashSet<>();
	private boolean applying = false;
	private boolean refreshQueued = false;

	public ShopPricing(MedievalEconomy economy, MarketManager market) {
		this.economy = economy;
		this.market = market;
		this.file = new File(economy.getPlugin().getDataFolder(), "marked-shops.yml");
	}

	// ---------------------------------------------------------------- Persistencia

	public void load() {
		marked.clear();
		excluded.clear();
		if (!file.exists()) return;
		YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
		for (String s : yaml.getStringList("excluidas")) {
			try {
				excluded.add(UUID.fromString(s));
			} catch (IllegalArgumentException ignored) {
				// Clave invalida.
			}
		}
		ConfigurationSection shops = yaml.getConfigurationSection("tiendas");
		if (shops == null) return;
		for (String key : shops.getKeys(false)) {
			try {
				UUID id = UUID.fromString(key);
				ConfigurationSection section = shops.getConfigurationSection(key);
				if (section == null) continue;
				List<Entry> list = new ArrayList<>();
				for (String index : section.getKeys(false)) {
					ConfigurationSection e = section.getConfigurationSection(index);
					if (e == null) continue;
					Entry entry = new Entry();
					entry.shop = id;
					entry.result = e.getItemStack("result");
					entry.item1 = e.getItemStack("item1");
					entry.item2 = e.getItemStack("item2");
					entry.item = e.getItemStack("item");
					entry.price = e.getInt("price", 0);
					entry.buying = e.getBoolean("buying", false);
					entry.applied = e.getDouble("applied", 1.0D);
					entry.stock = e.getDouble("stock", -1.0D);
					list.add(entry);
				}
				marked.put(id, list);
			} catch (IllegalArgumentException ignored) {
				// Clave invalida.
			}
		}
	}

	public void save() {
		YamlConfiguration yaml = new YamlConfiguration();
		List<String> excludedList = new ArrayList<>();
		for (UUID id : excluded) excludedList.add(id.toString());
		yaml.set("excluidas", excludedList);
		for (Map.Entry<UUID, List<Entry>> shop : marked.entrySet()) {
			int i = 0;
			for (Entry e : shop.getValue()) {
				String base = "tiendas." + shop.getKey() + "." + (i++);
				if (e.result != null) yaml.set(base + ".result", e.result);
				if (e.item1 != null) yaml.set(base + ".item1", e.item1);
				if (e.item2 != null) yaml.set(base + ".item2", e.item2);
				if (e.item != null) yaml.set(base + ".item", e.item);
				yaml.set(base + ".price", e.price);
				yaml.set(base + ".buying", e.buying);
				yaml.set(base + ".applied", e.applied);
				yaml.set(base + ".stock", e.stock);
			}
		}
		try {
			economy.getPlugin().getDataFolder().mkdirs();
			yaml.save(file);
		} catch (IOException ex) {
			economy.getPlugin().getLogger().warning("No se pudo guardar marked-shops.yml: " + ex.getMessage());
		}
	}

	public Set<UUID> markedIds() {
		return marked.keySet();
	}

	public boolean isMarked(UUID id) {
		return marked.containsKey(id);
	}

	// ---------------------------------------------------------------- Captura y precios

	private static boolean supported(Shopkeeper shopkeeper) {
		return shopkeeper instanceof RegularAdminShopkeeper || shopkeeper instanceof SellingPlayerShopkeeper
				|| shopkeeper instanceof BuyingPlayerShopkeeper;
	}

	/** Lee las ofertas actuales de la tienda y las convierte en precios base (dividiendo por unbake). */
	private List<Entry> capture(Shopkeeper shopkeeper, boolean unbake) {
		CurrencySet set = Currencies.of(shopkeeper.getUniqueId());
		List<Entry> list = new ArrayList<>();
		if (shopkeeper instanceof RegularAdminShopkeeper admin) {
			for (TradeOffer offer : admin.getOffers()) {
				Entry e = new Entry();
				e.shop = shopkeeper.getUniqueId();
				e.result = offer.getResultItem().copy();
				e.item1 = offer.getItem1().copy();
				e.item2 = offer.getItem2() == null ? null : offer.getItem2().copy();
				if (unbake) {
					String key = keyOf(e, set);
					if (key != null) {
						double mult = market.getMultiplier(key);
						if (mult > 0.0001D) {
							ItemStack[] base = scaleAdmin(e, 1.0D / mult, set);
							e.result = base[0];
							e.item1 = base[1];
							e.item2 = base[2];
						}
					}
				}
				list.add(e);
			}
		} else if (shopkeeper instanceof SellingPlayerShopkeeper selling) {
			for (PriceOffer offer : selling.getOffers()) {
				list.add(playerEntry(offer, unbake, false));
			}
		} else if (shopkeeper instanceof BuyingPlayerShopkeeper buying) {
			for (PriceOffer offer : buying.getOffers()) {
				list.add(playerEntry(offer, unbake, true));
			}
		}
		return list;
	}

	private Entry playerEntry(PriceOffer offer, boolean unbake, boolean buying) {
		Entry e = new Entry();
		e.buying = buying;
		e.item = offer.getItem().copy();
		e.price = offer.getPrice();
		if (unbake) {
			double mult = market.getMultiplier(MarketManager.keyOf(e.item));
			if (mult > 0.0001D) e.price = Math.max(1, (int) Math.round(e.price / mult));
		}
		return e;
	}

	private enum Mode { SELL, PAY }

	/** SELL = la tienda te vende el objeto. PAY = la tienda te paga monedas por tu objeto. */
	private static Mode modeOf(Entry e, CurrencySet set) {
		Currency cr = set.match(e.result);
		Currency c1 = set.match(e.item1);
		return (cr != null && c1 == null) ? Mode.PAY : Mode.SELL;
	}

	/** Objeto del mercado al que corresponde esta oferta. Toda oferta cuenta (incluso trueques). */
	private static String keyOf(Entry e, CurrencySet set) {
		if (e.item != null) return MarketManager.keyOf(e.item);
		if (e.result == null || e.item1 == null) return null;
		return modeOf(e, set) == Mode.PAY ? MarketManager.keyOf(e.item1) : MarketManager.keyOf(e.result);
	}

	private ItemStack[] scaleAdmin(Entry e, double mult, CurrencySet set) {
		ItemStack result = e.result.clone();
		ItemStack item1 = e.item1.clone();
		ItemStack item2 = e.item2 == null ? null : e.item2.clone();
		if (Math.abs(mult - 1.0D) < 0.0005D) return new ItemStack[] { result, item1, item2 };

		Currency c1 = set.match(item1);
		Currency c2 = item2 == null ? null : set.match(item2);
		Currency cr = set.match(result);

		if (modeOf(e, set) == Mode.PAY) {
			// La tienda paga monedas por tu objeto: pagan mas o menos.
			int units = (int) Math.max(1L, Math.round(result.getAmount() * mult));
			units = Math.min(units, cr.getMaxStackSize());
			units = capByArbitrage(e, units, cr, set);
			result.setAmount(units);
			return new ItemStack[] { result, item1, item2 };
		}
		if (c1 != null && (item2 == null || c2 != null)) {
			// Precio en monedas: se recalcula el valor total (con moneda alta si hay).
			long total = (long) item1.getAmount() * c1.getValue();
			if (item2 != null) total += (long) item2.getAmount() * c2.getValue();
			long scaled = Math.max(1L, Math.round(total * mult));
			ItemStack[] cost = splitCurrency(scaled, set);
			return new ItemStack[] { result, cost[0], cost[1] };
		}
		// El precio son objetos que no son la moneda: se escala la cantidad del primero.
		int units = (int) Math.max(1L, Math.round(item1.getAmount() * mult));
		item1.setAmount(Math.min(units, item1.getMaxStackSize()));
		return new ItemStack[] { result, item1, item2 };
	}

	/**
	 * Anti-arbitraje: lo que el mercader PAGA por un objeto nunca supera un porcentaje de lo que
	 * COBRA por ese mismo objeto en la misma tienda (por defecto 90%). Sin esto, los factores de
	 * stock o los eventos podrian dejar la compra mas cara que la venta y habria dinero infinito.
	 */
	private int capByArbitrage(Entry e, int units, Currency cr, CurrencySet set) {
		FileConfiguration c = economy.config();
		double ratio = c.getBoolean("antiabuso.habilitado", true) ? c.getDouble("antiabuso.ratio-maximo-compra-venta", 0.9D) : 0.0D;
		if (ratio <= 0.0D || e.shop == null) return units;
		List<Entry> siblings = marked.get(e.shop);
		if (siblings == null) return units;
		double bestSellUnit = Double.MAX_VALUE;
		for (Entry s : siblings) {
			if (s == e || s.item != null || s.result == null || modeOf(s, set) != Mode.SELL) continue;
			if (!s.result.isSimilar(e.item1)) continue;
			ItemStack[] arr = scaleAdmin(s, entryMultiplier(s, e.shop, set), set);
			Currency a1 = set.match(arr[1]);
			Currency a2 = arr[2] == null ? null : set.match(arr[2]);
			if (a1 == null || (arr[2] != null && a2 == null)) continue; // su precio no es en monedas
			long value = (long) arr[1].getAmount() * a1.getValue();
			if (arr[2] != null) value += (long) arr[2].getAmount() * a2.getValue();
			bestSellUnit = Math.min(bestSellUnit, (double) value / Math.max(1, arr[0].getAmount()));
		}
		if (bestSellUnit == Double.MAX_VALUE) return units;
		double payUnit = (double) units * cr.getValue() / Math.max(1, e.item1.getAmount());
		double maxUnit = bestSellUnit * ratio;
		if (payUnit <= maxUnit) return units;
		return Math.max(1, (int) Math.floor(maxUnit * e.item1.getAmount() / cr.getValue()));
	}

	/** Devuelve {item1, item2 (puede ser null)} que suman el valor dado en monedas base. */
	private static ItemStack[] splitCurrency(long value, CurrencySet set) {
		Currency base = set.getBase();
		Currency high = set.getHighOrNull();
		if (high == null) {
			int amount = (int) Math.min(value, base.getMaxStackSize());
			return new ItemStack[] { base.getItemData().createItemStack(Math.max(1, amount)), null };
		}
		long highValue = Math.max(1, high.getValue());
		long highCount = value / highValue;
		long baseCount = value % highValue;
		if (highCount > high.getMaxStackSize()) {
			highCount = high.getMaxStackSize();
			baseCount = Math.min(baseCount, base.getMaxStackSize());
		}
		if (highCount > 0 && baseCount > 0) {
			return new ItemStack[] { high.getItemData().createItemStack((int) highCount),
					base.getItemData().createItemStack((int) baseCount) };
		} else if (highCount > 0) {
			return new ItemStack[] { high.getItemData().createItemStack((int) highCount), null };
		}
		return new ItemStack[] { base.getItemData().createItemStack((int) Math.max(1, baseCount)), null };
	}

	private double maxTotalMultiplier() {
		return Math.max(1.0D, economy.config().getDouble("antiabuso.multiplicador-maximo-total", 4.0D));
	}

	private double multiplierFor(String key) {
		if (key == null) return 1.0D;
		market.register(key);
		return Math.min(maxTotalMultiplier(), market.getMultiplier(key));
	}

	// ---------------------------------------------------------------- Stock (tiendas de admin)

	private boolean stockOn(UUID shopId) {
		Shopkeeper shopkeeper = ShopkeepersAPI.getShopkeeperRegistry().getShopkeeperByUniqueId(shopId);
		if (!(shopkeeper instanceof RegularAdminShopkeeper)) return false;
		boolean def = economy.config().getBoolean("stock.habilitado", true);
		return economy.profiles().stockEnabled(shopId, def);
	}

	public int stockMax(UUID shopId) {
		return Math.max(1, economy.profiles().stockMax(shopId, economy.config().getInt("stock.maximo-por-oferta", 64)));
	}

	private void initStock(Entry e, UUID shopId, CurrencySet set) {
		if (e.stock >= 0) return;
		int max = stockMax(shopId);
		e.stock = modeOf(e, set) == Mode.SELL ? max : max / 2.0D;
	}

	/** Factor por escasez: 1.0 con la tienda llena; sube cuanto menos stock quede. */
	private double stockFactor(Entry e, UUID shopId, CurrencySet set) {
		if (e.item != null || e.result == null || !stockOn(shopId)) return 1.0D;
		initStock(e, shopId, set);
		double ratio = Math.max(0.0D, Math.min(1.0D, e.stock / stockMax(shopId)));
		double k = economy.config().getDouble("stock.escasez-maxima", 1.0D);
		if (modeOf(e, set) == Mode.PAY) {
			// La tienda que compra paga mas cuanto menos tiene y menos cuanto mas tiene (1.0 a la mitad).
			return Math.max(0.1D, 1.0D + 0.5D * k * (0.5D - ratio) * 2.0D);
		}
		return 1.0D + k * (1.0D - ratio) * (1.0D - ratio);
	}

	/** Multiplicador total de una oferta: mercado (demanda + eventos) x escasez de stock. */
	private double entryMultiplier(Entry e, UUID shopId, CurrencySet set) {
		String key = e.item != null ? MarketManager.keyOf(e.item) : keyOf(e, set);
		double total = multiplierFor(key) * stockFactor(e, shopId, set);
		return Math.max(0.25D, Math.min(maxTotalMultiplier(), total));
	}

	/** Cada minuto: la tienda repone (o consume) sus existencias. @return true si algo cambio */
	public boolean tickMinute() {
		boolean changed = false;
		double perMinute = economy.config().getDouble("stock.reposicion-por-hora", 16.0D) / 60.0D;
		for (Map.Entry<UUID, List<Entry>> shop : marked.entrySet()) {
			if (!stockOn(shop.getKey())) continue;
			CurrencySet set = Currencies.of(shop.getKey());
			int max = stockMax(shop.getKey());
			for (Entry e : shop.getValue()) {
				if (e.item != null || e.result == null) continue;
				initStock(e, shop.getKey(), set);
				double before = e.stock;
				if (modeOf(e, set) == Mode.SELL) {
					e.stock = Math.min(max, e.stock + perMinute);
				} else {
					// La tienda que compra: sus existencias vuelven poco a poco a la mitad.
					double half = max / 2.0D;
					if (e.stock > half) e.stock = Math.max(half, e.stock - perMinute);
					else e.stock = Math.min(half, e.stock + perMinute);
				}
				if (e.stock > max) e.stock = max;
				if (Math.abs(e.stock - before) > 0.0001D) changed = true;
			}
		}
		return changed;
	}

	private Entry findEntry(UUID shopId, com.nisovin.shopkeepers.api.shopkeeper.TradingRecipe recipe) {
		List<Entry> list = marked.get(shopId);
		if (list == null) return null;
		CurrencySet set = Currencies.of(shopId);
		ItemStack result = recipe.getResultItem().copy();
		ItemStack item1 = recipe.getItem1().copy();
		for (Entry e : list) {
			if (e.item != null || e.result == null) continue;
			if (modeOf(e, set) == Mode.PAY) {
				if (e.item1.isSimilar(item1)) return e;
			} else if (e.result.isSimilar(result)) {
				return e;
			}
		}
		return null;
	}

	/**
	 * Antes de una compra: comprueba el stock. @return un mensaje si la compra debe cancelarse
	 * (agotado / la tienda ya tiene de sobra), o null si puede seguir.
	 */
	public String checkStock(UUID shopId, com.nisovin.shopkeepers.api.shopkeeper.TradingRecipe recipe) {
		if (!marked.containsKey(shopId) || !stockOn(shopId)) return null;
		Entry e = findEntry(shopId, recipe);
		if (e == null) return null;
		CurrencySet set = Currencies.of(shopId);
		initStock(e, shopId, set);
		if (modeOf(e, set) == Mode.SELL) {
			int need = recipe.getResultItem().getAmount();
			if (Math.floor(e.stock) < need) {
				return "&cAgotado: &f" + nameOf(e.result) + " &7(quedan " + (int) Math.floor(e.stock)
						+ "). Se repone solo con el tiempo.";
			}
		} else {
			int give = recipe.getItem1().getAmount();
			if (e.stock + give > stockMax(shopId)) {
				return "&cLa tienda ya tiene suficiente &f" + nameOf(e.item1) + "&c. Vuelve mas tarde.";
			}
		}
		return null;
	}

	/**
	 * Anti "ventana vieja": si el jugador abrio la tienda antes de que subiera el precio (o antes de
	 * un evento) y compra con el precio viejo, se cancela. @return mensaje o null
	 */
	public String checkFreshPrice(UUID shopId, com.nisovin.shopkeepers.api.shopkeeper.TradingRecipe recipe) {
		if (!marked.containsKey(shopId)) return null;
		FileConfiguration c = economy.config();
		if (!c.getBoolean("antiabuso.habilitado", true)) return null;
		double tol = c.getDouble("antiabuso.tolerancia-precio-obsoleto", 0.10D);
		if (tol < 0.0D) return null;
		Entry e = findEntry(shopId, recipe);
		if (e == null) return null;
		CurrencySet set = Currencies.of(shopId);
		ItemStack[] expected = scaleAdmin(e, entryMultiplier(e, shopId, set), set);
		String stale = "&cEl precio cambio mientras tenias la tienda abierta. Cierrala y vuelve a abrirla.";
		if (modeOf(e, set) == Mode.PAY) {
			if (recipe.getResultItem().getAmount() > Math.ceil(expected[0].getAmount() * (1.0D + tol))) return stale;
			return null;
		}
		ItemStack r1 = recipe.getItem1().copy();
		ItemStack r2 = recipe.getItem2() == null ? null : recipe.getItem2().copy();
		Currency c1 = set.match(r1);
		Currency c2 = r2 == null ? null : set.match(r2);
		Currency e1 = set.match(expected[1]);
		Currency e2 = expected[2] == null ? null : set.match(expected[2]);
		if (c1 != null && e1 != null && (r2 == null || c2 != null) && (expected[2] == null || e2 != null)) {
			double paid = (double) r1.getAmount() * c1.getValue() + (r2 == null ? 0 : (double) r2.getAmount() * c2.getValue());
			double want = (double) expected[1].getAmount() * e1.getValue()
					+ (expected[2] == null ? 0 : (double) expected[2].getAmount() * e2.getValue());
			if (paid < Math.floor(want * (1.0D - tol))) return stale;
		} else if (r1.getAmount() < Math.floor(expected[1].getAmount() * (1.0D - tol))) {
			return stale;
		}
		return null;
	}

	/** Devuelve los precios BASE a todos los mercaderes del mercado (sin desactivarlo). @return cuantos */
	public int restoreAllBase() {
		int count = 0;
		for (UUID id : new ArrayList<>(marked.keySet())) {
			Shopkeeper shopkeeper = ShopkeepersAPI.getShopkeeperRegistry().getShopkeeperByUniqueId(id);
			if (shopkeeper == null) continue;
			apply(shopkeeper, true);
			count++;
		}
		return count;
	}

	/** Despues de una compra completada: descuenta (o suma) existencias. */
	public void applyStockTrade(UUID shopId, com.nisovin.shopkeepers.api.shopkeeper.TradingRecipe recipe) {
		if (!marked.containsKey(shopId) || !stockOn(shopId)) return;
		Entry e = findEntry(shopId, recipe);
		if (e == null) return;
		CurrencySet set = Currencies.of(shopId);
		initStock(e, shopId, set);
		if (modeOf(e, set) == Mode.SELL) {
			e.stock = Math.max(0.0D, e.stock - recipe.getResultItem().getAmount());
		} else {
			e.stock = Math.min(stockMax(shopId), e.stock + recipe.getItem1().getAmount());
		}
	}

	/** Existencias de una oferta de admin para mostrar: "12/64", o null si no hay stock en la tienda. */
	private String stockText(Entry e, UUID shopId, CurrencySet set) {
		if (e.item != null || e.result == null || !stockOn(shopId)) return null;
		initStock(e, shopId, set);
		int now = (int) Math.floor(e.stock);
		if (modeOf(e, set) == Mode.SELL) return now <= 0 ? "&cAGOTADO" : "&8quedan " + now + "/" + stockMax(shopId);
		return "&8tiene " + now + "/" + stockMax(shopId);
	}

	// ---------------------------------------------------------------- Aplicar

	private void apply(Shopkeeper shopkeeper, boolean restoreBase) {
		List<Entry> base = marked.get(shopkeeper.getUniqueId());
		if (base == null) return;
		CurrencySet set = Currencies.of(shopkeeper.getUniqueId());
		try {
			if (shopkeeper instanceof RegularAdminShopkeeper admin) {
				List<TradeOffer> out = new ArrayList<>();
				for (Entry e : base) {
					double mult = restoreBase ? 1.0D : entryMultiplier(e, shopkeeper.getUniqueId(), set);
					e.applied = mult;
					ItemStack[] arr = scaleAdmin(e, mult, set);
					out.add(TradeOffer.create(arr[0], arr[1], arr[2]));
				}
				if (!sameAdmin(admin.getOffers(), out)) {
					applying = true;
					try {
						admin.setOffers(out);
					} finally {
						applying = false;
					}
				}
			} else if (shopkeeper instanceof SellingPlayerShopkeeper selling) {
				List<PriceOffer> out = playerOffers(base, restoreBase);
				if (!samePrices(selling.getOffers(), out)) {
					applying = true;
					try {
						selling.setOffers(out);
					} finally {
						applying = false;
					}
				}
			} else if (shopkeeper instanceof BuyingPlayerShopkeeper buying) {
				List<PriceOffer> out = playerOffers(base, restoreBase);
				if (!samePrices(buying.getOffers(), out)) {
					applying = true;
					try {
						buying.setOffers(out);
					} finally {
						applying = false;
					}
				}
			}
		} catch (Exception e) {
			economy.getPlugin().getLogger().log(Level.WARNING,
					"No se pudieron actualizar los precios de la tienda #" + shopkeeper.getId(), e);
		}
	}

	private List<PriceOffer> playerOffers(List<Entry> base, boolean restoreBase) {
		List<PriceOffer> out = new ArrayList<>();
		for (Entry e : base) {
			double mult = restoreBase ? 1.0D : multiplierFor(MarketManager.keyOf(e.item));
			e.applied = mult;
			int price = Math.max(1, (int) Math.round(e.price * mult));
			out.add(PriceOffer.create(e.item.clone(), price));
		}
		return out;
	}

	private static boolean sameAdmin(List<? extends TradeOffer> current, List<TradeOffer> out) {
		if (current.size() != out.size()) return false;
		for (int i = 0; i < out.size(); i++) {
			TradeOffer a = current.get(i);
			TradeOffer b = out.get(i);
			if (!a.getResultItem().copy().equals(b.getResultItem().copy())) return false;
			if (!a.getItem1().copy().equals(b.getItem1().copy())) return false;
			ItemStack a2 = a.getItem2() == null ? null : a.getItem2().copy();
			ItemStack b2 = b.getItem2() == null ? null : b.getItem2().copy();
			if (a2 == null ? b2 != null : !a2.equals(b2)) return false;
		}
		return true;
	}

	private static boolean samePrices(List<? extends PriceOffer> current, List<PriceOffer> out) {
		if (current.size() != out.size()) return false;
		for (int i = 0; i < out.size(); i++) {
			if (current.get(i).getPrice() != out.get(i).getPrice()) return false;
			if (!current.get(i).getItem().copy().equals(out.get(i).getItem().copy())) return false;
		}
		return true;
	}

	// ---------------------------------------------------------------- API para comandos y eventos

	/** @return cantidad de ofertas capturadas, o -1 si el tipo de tienda no esta soportado */
	public int mark(Shopkeeper shopkeeper) {
		if (!supported(shopkeeper)) return -1;
		List<Entry> list = capture(shopkeeper, false);
		marked.put(shopkeeper.getUniqueId(), list);
		excluded.remove(shopkeeper.getUniqueId());
		save();
		apply(shopkeeper, false);
		return list.size();
	}

	public boolean unmark(Shopkeeper shopkeeper) {
		if (!marked.containsKey(shopkeeper.getUniqueId())) return false;
		apply(shopkeeper, true); // Devuelve los precios base originales.
		marked.remove(shopkeeper.getUniqueId());
		excluded.add(shopkeeper.getUniqueId()); // el admin la apago: no se reactiva sola
		save();
		return true;
	}

	/**
	 * Un jugador edito la tienda. Lo que dejo en el editor son sus precios BASE: las ofertas que no
	 * cambio conservan su base, las nuevas o modificadas toman el precio escrito como base.
	 */
	public void onEdited(Shopkeeper shopkeeper) {
		if (applying || !marked.containsKey(shopkeeper.getUniqueId())) return;
		Bukkit.getScheduler().runTask(economy.getPlugin(), () -> {
			List<Entry> old = marked.get(shopkeeper.getUniqueId());
			if (old == null) return;
			marked.put(shopkeeper.getUniqueId(), reconcile(shopkeeper, old, capture(shopkeeper, false)));
			save();
			apply(shopkeeper, false);
		});
	}

	private List<Entry> reconcile(Shopkeeper shopkeeper, List<Entry> old, List<Entry> current) {
		CurrencySet set = Currencies.of(shopkeeper.getUniqueId());
		List<Entry> pool = new ArrayList<>(old);
		List<Entry> result = new ArrayList<>();
		for (Entry cur : current) {
			Entry match = null;
			for (Entry candidate : pool) {
				if (shows(candidate, cur, set, shopkeeper.getUniqueId())) {
					match = candidate;
					break;
				}
			}
			if (match != null) {
				pool.remove(match);
				result.add(match);
			} else {
				result.add(cur);
			}
		}
		return result;
	}

	/** true si la oferta actual es la oferta base ya ajustada por el mercado (no fue editada). */
	private boolean shows(Entry base, Entry cur, CurrencySet set, UUID shopId) {
		double current = entryMultiplier(base, shopId, set);
		return showsAt(base, cur, base.applied > 0 ? base.applied : current, set) || showsAt(base, cur, current, set);
	}

	private boolean showsAt(Entry base, Entry cur, double m, CurrencySet set) {
		if (base.item != null) {
			if (cur.item == null || !base.item.equals(cur.item)) return false;
			return Math.max(1, (int) Math.round(base.price * m)) == cur.price;
		}
		if (cur.item != null || base.result == null || cur.result == null) return false;
		ItemStack[] arr = scaleAdmin(base, m, set);
		if (!arr[0].equals(cur.result) || !arr[1].equals(cur.item1)) return false;
		return arr[2] == null ? cur.item2 == null : arr[2].equals(cur.item2);
	}

	/** Si alguien cambio las ofertas por otra via (comandos, otros plugins), se toman como nuevo precio base. */
	private void syncEdits(Shopkeeper shopkeeper) {
		List<Entry> old = marked.get(shopkeeper.getUniqueId());
		if (old == null) return;
		List<Entry> rec = reconcile(shopkeeper, old, capture(shopkeeper, false));
		boolean changed = rec.size() != old.size();
		for (int i = 0; !changed && i < rec.size(); i++) {
			if (rec.get(i) != old.get(i)) changed = true;
		}
		if (changed) {
			marked.put(shopkeeper.getUniqueId(), rec);
			save();
		}
	}

	// ---------------------------------------------------------------- Activacion automatica

	private boolean autoAllowed(Shopkeeper shopkeeper, boolean freshlyCreated) {
		org.bukkit.configuration.file.FileConfiguration c = economy.config();
		if (!c.getBoolean("mercado.auto-activar.habilitado", true)) return false;
		if (!freshlyCreated && !c.getBoolean("mercado.auto-activar.existentes", true)) return false;
		if (shopkeeper instanceof RegularAdminShopkeeper) return c.getBoolean("mercado.auto-activar.tiendas-admin", true);
		if (shopkeeper instanceof SellingPlayerShopkeeper || shopkeeper instanceof BuyingPlayerShopkeeper) {
			return c.getBoolean("mercado.auto-activar.tiendas-jugador", false);
		}
		return false;
	}

	/** Activa el mercado en un mercader nuevo (o cargado) si la config lo permite. @return true si lo activo */
	public boolean autoMark(Shopkeeper shopkeeper, boolean freshlyCreated, boolean saveNow) {
		UUID id = shopkeeper.getUniqueId();
		if (marked.containsKey(id) || excluded.contains(id)) return false;
		if (!autoAllowed(shopkeeper, freshlyCreated)) return false;
		marked.put(id, capture(shopkeeper, false));
		if (saveNow) save();
		return true;
	}

	/** Al arrancar: activa el mercado en todos los mercaderes que aun no lo tienen. */
	public int autoMarkAll() {
		int count = 0;
		for (Shopkeeper shopkeeper : ShopkeepersAPI.getShopkeeperRegistry().getAllShopkeepers()) {
			if (autoMark(shopkeeper, false, false)) count++;
		}
		if (count > 0) save();
		return count;
	}

	/** El mercader fue borrado: se olvidan sus datos. */
	public void forget(UUID id) {
		boolean changed = marked.remove(id) != null;
		changed |= excluded.remove(id);
		if (changed) save();
	}

	public void refreshAll() {
		if (!market.isEnabled()) return;
		for (UUID id : new ArrayList<>(marked.keySet())) {
			Shopkeeper shopkeeper = ShopkeepersAPI.getShopkeeperRegistry().getShopkeeperByUniqueId(id);
			if (shopkeeper == null) continue;
			syncEdits(shopkeeper);
			apply(shopkeeper, false);
		}
	}

	/** Recalcula los precios en el proximo tick (agrupa varias compras seguidas). */
	public void refreshSoon() {
		if (refreshQueued || marked.isEmpty()) return;
		refreshQueued = true;
		Bukkit.getScheduler().runTask(economy.getPlugin(), () -> {
			refreshQueued = false;
			refreshAll();
		});
	}

	/** Quita colores, saltos de linea y codigos de un texto puesto por jugadores; limita el largo. */
	public static String clean(String raw) {
		if (raw == null) return "";
		String t = org.bukkit.ChatColor.stripColor(raw).replace("&", "").replace('\n', ' ').replace('\r', ' ').replace('\t', ' ').trim();
		return t.length() > 40 ? t.substring(0, 40) : t;
	}

	private static String nameOf(ItemStack item) {
		if (item.hasItemMeta() && item.getItemMeta().hasDisplayName()) return clean(item.getItemMeta().getDisplayName());
		return MedievalEconomy.pretty(item.getType().name());
	}

	private static String stacksText(ItemStack a, ItemStack b) {
		String text = a.getAmount() + " " + nameOf(a);
		if (b != null) text += " + " + b.getAmount() + " " + nameOf(b);
		return text;
	}

	/**
	 * Lineas faciles de leer con el precio de un objeto en cada tienda marcada.
	 * Ej: "#5 TE VENDE 4 Pan por 10 Moneda -> AHORA 12 Moneda".
	 */
	public List<String> priceLines(String key) {
		List<String> lines = new ArrayList<>();
		double mult = market.getMultiplier(key);
		for (Map.Entry<UUID, List<Entry>> shop : marked.entrySet()) {
			Shopkeeper shopkeeper = ShopkeepersAPI.getShopkeeperRegistry().getShopkeeperByUniqueId(shop.getKey());
			if (shopkeeper == null) continue;
			CurrencySet set = Currencies.of(shop.getKey());
			for (Entry e : shop.getValue()) {
				if (!key.equals(keyOf(e, set))) continue;
				String id = "&7#" + shopkeeper.getId() + " ";
				if (e.item != null) {
					int now = Math.max(1, (int) Math.round(e.price * mult));
					lines.add(id + (e.buying ? "&bTE COMPRA" : "&aTE VENDE") + " &f" + e.item.getAmount() + " "
							+ nameOf(e.item) + " &7por &e" + e.price + " &7-> &fAHORA &e" + now);
				} else {
					ItemStack[] arr = scaleAdmin(e, entryMultiplier(e, shop.getKey(), set), set);
					if (modeOf(e, set) == Mode.PAY) {
						lines.add(id + "&bTE COMPRA &f" + e.item1.getAmount() + " " + nameOf(e.item1) + " &7y paga &e"
								+ e.result.getAmount() + " &7-> &fAHORA &e" + arr[0].getAmount() + " &7" + nameOf(e.result));
					} else {
						String st = stockText(e, shop.getKey(), set);
						lines.add(id + "&aTE VENDE &f" + e.result.getAmount() + " " + nameOf(e.result) + " &7por &e"
								+ stacksText(e.item1, e.item2) + " &7-> &fAHORA &e" + stacksText(arr[1], arr[2])
								+ (st == null ? "" : " " + st));
					}
				}
			}
		}
		return lines;
	}

	private static String shortDelta(double mult) {
		String color = mult >= 1.02D ? "&c" : mult <= 0.98D ? "&a" : "&e";
		double pct = (mult - 1.0D) * 100.0D;
		return color + String.format(java.util.Locale.ROOT, "%s%.0f%%", pct >= 0 ? "+" : "", pct);
	}

	/** Lineas cortas para el holograma de un mercader (rota por paginas si hay muchas ofertas). */
	public List<String> hologramLines(UUID shopId, int perPage, int rotation) {
		List<Entry> list = marked.get(shopId);
		if (list == null || list.isEmpty()) return new ArrayList<>();
		CurrencySet set = Currencies.of(shopId);
		List<String> all = new ArrayList<>();
		for (Entry e : list) {
			if (e.item != null) {
				double m = multiplierFor(MarketManager.keyOf(e.item));
				int now = Math.max(1, (int) Math.round(e.price * m));
				all.add((e.buying ? "&b" : "&f") + nameOf(e.item) + " &7" + (e.buying ? "compra " : "") + "&e" + now + " "
						+ shortDelta(m));
				continue;
			}
			if (e.result == null) continue;
			double m = entryMultiplier(e, shopId, set);
			ItemStack[] arr = scaleAdmin(e, m, set);
			if (modeOf(e, set) == Mode.PAY) {
				all.add("&b" + nameOf(e.item1) + " &7compra &e" + arr[0].getAmount() + " " + shortDelta(m));
			} else {
				String stock = stockText(e, shopId, set);
				boolean soldOut = stock != null && stock.contains("AGOTADO");
				all.add("&f" + nameOf(e.result) + " &e" + stacksText(arr[1], arr[2]) + " " + shortDelta(m)
						+ (soldOut ? " &cAGOTADO" : ""));
			}
		}
		if (all.isEmpty()) return all;
		int size = Math.max(1, perPage);
		int pages = (all.size() + size - 1) / size;
		int page = Math.floorMod(rotation, pages);
		List<String> out = new ArrayList<>(all.subList(page * size, Math.min(all.size(), (page + 1) * size)));
		if (pages > 1) out.add("&8(" + (page + 1) + "/" + pages + ")");
		return out;
	}

	/** Diagnostico: explica por que un mercader cambia (o no) de precio. */
	public List<String> debugLines(Shopkeeper shopkeeper) {
		List<String> out = new ArrayList<>();
		UUID id = shopkeeper.getUniqueId();
		out.add("&6&lDiagnostico del mercader #" + shopkeeper.getId() + " &7(" + shopkeeper.getClass().getSimpleName() + ")");
		if (!market.isEnabled()) out.add("&c- El mercado esta desactivado: mercado.habilitado en medieval.yml.");
		if (!supported(shopkeeper)) {
			out.add("&c- Este tipo de tienda NO admite precios dinamicos (solo admin, venta de jugador o compra de jugador).");
			return out;
		}
		if (!marked.containsKey(id)) {
			if (excluded.contains(id)) out.add("&c- Lo apagaste en su panel. Para volver a activarlo: /mercadoadmin tienda marcar");
			else if (!autoAllowed(shopkeeper, true)) {
				out.add("&c- No se activo solo: mira mercado.auto-activar en medieval.yml (las tiendas de jugador vienen apagadas).");
				out.add("&7  Para activarlo en este: /mercadoadmin tienda marcar");
			} else out.add("&c- Aun no esta activado (se activa al crearse o al reiniciar). Prueba: /mercadoadmin tienda marcar");
			return out;
		}
		CurrencySet set = Currencies.of(id);
		out.add("&a- Precios dinamicos ACTIVADOS. Moneda: &f" + set.getBase().getDisplayName()
				+ (set.getHighOrNull() == null ? "" : " / " + set.getHighOrNull().getDisplayName() + " (x" + set.getHighOrNull().getValue() + ")")
				+ (economy.profiles().hasCustomCurrency(id) ? " &7(propia)" : " &7(global)"));
		boolean stockOn = stockOn(id);
		out.add("&7- Stock: " + (stockOn ? "&asi &7(max " + stockMax(id) + ")" : "&cno"));
		List<Entry> list = marked.get(id);
		if (list.isEmpty()) {
			out.add("&e- No tiene ofertas todavia: agrega objetos en el editor y se detectaran solos.");
			return out;
		}
		int n = 0;
		for (Entry e : list) {
			n++;
			if (e.item != null) {
				String key = MarketManager.keyOf(e.item);
				double m = multiplierFor(key);
				int now = Math.max(1, (int) Math.round(e.price * m));
				out.add("&f#" + n + " &7" + (e.buying ? "COMPRA" : "VENDE") + " " + nameOf(e.item) + ": precio base &e" + e.price
						+ " &7-> ahora &e" + now + " &7(mercado " + Explain.percent(m) + ")");
				if (now == e.price) out.add(diag(m, e.price));
				continue;
			}
			if (e.result == null) continue;
			Mode mode = modeOf(e, set);
			String key = keyOf(e, set);
			double marketMult = multiplierFor(key);
			double stockMult = stockFactor(e, id, set);
			double total = marketMult * stockMult;
			Currency c1 = set.match(e.item1);
			Currency cr = set.match(e.result);
			out.add("&f#" + n + " &7" + (mode == Mode.PAY ? "COMPRA " + nameOf(e.item1) : "VENDE " + nameOf(e.result)) + " &7(objeto del mercado: &f"
					+ key + "&7)");
			out.add("&7   precio: " + (mode == Mode.PAY ? "paga " + e.result.getAmount() : stacksText(e.item1, e.item2)) + " &7-> ahora &e"
					+ (mode == Mode.PAY ? String.valueOf(scaleAdmin(e, total, set)[0].getAmount()) : stacksText(scaleAdmin(e, total, set)[1], scaleAdmin(e, total, set)[2])));
			out.add("&7   mercado " + Explain.percent(marketMult) + " &7(demanda: " + Explain.demand(market.demandLevel(key))
					+ "&7, evento x" + String.format(java.util.Locale.ROOT, "%.2f", market.getEventMultiplier(key)) + ")"
					+ (stockOn ? " &7stock " + Explain.percent(stockMult) : ""));
			if (mode == Mode.SELL && c1 == null) {
				out.add("&e   - El precio NO esta en tu moneda (" + set.getBase().getDisplayName() + "): se escala la cantidad del primer objeto."
						+ " Si la moneda es otra, usa /mercadoadmin moneda base.");
			}
			if (mode == Mode.PAY && cr == null) out.add("&e   - (raro) no se detecto la moneda de pago.");
			if (Math.abs(total - 1.0D) < 0.0005D) {
				out.add("&e   - Sin cambio: demanda normal y stock lleno. Nadie ha comprado/vendido esto todavia (o ya se recupero).");
			} else {
				ItemStack[] arr = scaleAdmin(e, total, set);
				boolean same = arr[0].equals(e.result) && arr[1].equals(e.item1) && (arr[2] == null ? e.item2 == null : arr[2].equals(e.item2));
				if (same) out.add("&e   - El precio redondeado queda igual: con un precio base tan bajo no se nota. Sube el precio base (10 o mas).");
			}
		}
		// Ofertas que alguien cambio fuera del editor
		List<com.nisovin.shopkeepers.api.shopkeeper.offers.TradeOffer> now = shopkeeper instanceof RegularAdminShopkeeper admin
				? new ArrayList<>(admin.getOffers()) : new ArrayList<>();
		if (shopkeeper instanceof RegularAdminShopkeeper && now.size() != list.size()) {
			out.add("&e- La tienda tiene " + now.size() + " ofertas y el addon recuerda " + list.size() + ": se sincroniza en el proximo minuto.");
		}
		return out;
	}

	private String diag(double m, int base) {
		if (Math.abs(m - 1.0D) < 0.0005D) return "&e   - Sin cambio: nadie ha comprado/vendido esto todavia.";
		return "&e   - El precio redondeado queda igual: con precio base " + base + " no se nota. Sube el precio base.";
	}

	/** Al apagar el servidor no se tocan los precios: quedan los ultimos aplicados. */
	public String describe(Shopkeeper shopkeeper) {
		return "#" + shopkeeper.getId() + (marked.containsKey(shopkeeper.getUniqueId()) ? " (dinamica)" : "");
	}
}
