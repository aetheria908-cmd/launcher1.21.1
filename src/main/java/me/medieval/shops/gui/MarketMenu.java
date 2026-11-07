package me.medieval.shops.gui;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.ItemStack;

import me.medieval.shops.MedievalEconomy;
import me.medieval.shops.Sfx;
import me.medieval.shops.market.MarketCategory;
import me.medieval.shops.market.MarketManager;

/** Lista de todos los objetos del mercado con su precio actual y tendencia. */
public final class MarketMenu extends Menu {

	private static final int PAGE_SIZE = 45;

	private final MarketManager market;
	private int page;
	private final MarketCategory category; // null = todos
	private List<String> keys = new ArrayList<>();

	public MarketMenu(MedievalEconomy economy, Player player, int page) {
		this(economy, player, page, null);
	}

	public MarketMenu(MedievalEconomy economy, Player player, int page, MarketCategory category) {
		super(economy, player);
		this.market = economy.market();
		this.page = page;
		this.category = category;
		String title = category == null
				? economy.config().getString("gui.titulos.mercado", "&0Mercado del Reino")
				: economy.config().getString("gui.titulos.categoria", "&0{categoria}")
						.replace("{categoria}", org.bukkit.ChatColor.stripColor(
								MedievalEconomy.color(category.getDisplayName())));
		create(54, Gui.title(economy, title,
				category == null ? Gui.BG_MARKET : category.getBackground()));
	}

	public static String percent(double mult) {
		double pct = (mult - 1.0D) * 100.0D;
		return String.format(Locale.ROOT, "%s%.0f%%", pct >= 0 ? "+" : "", pct);
	}

	public static String mult(double mult) {
		return String.format(Locale.ROOT, "x%.2f", mult);
	}

	public static String color(double mult) {
		if (mult >= 1.02D) return "&c"; // Mas caro
		if (mult <= 0.98D) return "&a"; // Mas barato
		return "&e";
	}

	/** Tendencia de la ultima hora. */
	public static double trend(MarketManager market, String key) {
		List<MarketManager.Sample> history = market.history(key);
		double now = market.getMultiplier(key);
		if (history.isEmpty()) return 0.0D;
		long limit = System.currentTimeMillis() / 60000L - 60L;
		double old = history.get(0).mult;
		for (MarketManager.Sample sample : history) {
			if (sample.minute <= limit) old = sample.mult;
			else break;
		}
		return now - old;
	}

	@Override
	protected void render() {
		keys = new ArrayList<>();
		for (String key : market.keys()) {
			if (category == null || MarketCategory.of(key) == category) keys.add(key);
		}
		int pages = Math.max(1, (keys.size() + PAGE_SIZE - 1) / PAGE_SIZE);
		page = Math.max(0, Math.min(page, pages - 1));

		int start = page * PAGE_SIZE;
		for (int i = 0; i < PAGE_SIZE && start + i < keys.size(); i++) {
			String key = keys.get(start + i);
			Material material = Material.matchMaterial(key);
			if (material == null || !material.isItem()) continue;
			double mult = market.getMultiplier(key);
			double trend = trend(market, key);
			List<String> lore = new ArrayList<>();
			lore.add(Explain.priceLine(mult));
			lore.add(Explain.demandLine(market.demandLevel(key)));
			lore.add("&7Ultima hora: " + Explain.trend(trend));
			double eventMult = market.getEventMultiplier(key);
			if (Math.abs(eventMult - 1.0D) > 0.001D) {
				lore.add("&7Evento activo: " + color(eventMult) + percent(eventMult));
			}
			List<String> shops = economy.pricing().priceLines(key);
			if (!shops.isEmpty()) {
				lore.add("");
				lore.add("&8Donde:");
				for (int n = 0; n < Math.min(4, shops.size()); n++) lore.add(shops.get(n));
				if (shops.size() > 4) lore.add("&8...y " + (shops.size() - 4) + " mas");
			}
			lore.add("");
			lore.add("&eClick: &fver grafica de precios");
			inventory.setItem(i, Gui.item(material, "&f" + MedievalEconomy.pretty(key), lore));
		}

		boolean pack = Gui.usesBackgrounds(economy);
		if (!pack) {
			for (int i = 45; i < 54; i++) inventory.setItem(i, Gui.filler());
		}
		if (page > 0) {
			inventory.setItem(45, Gui.button(economy, Gui.BTN_PREV, Material.ARROW,
					"&ePagina anterior", "&7Pagina " + page + "/" + pages));
		}
		if (page < pages - 1) {
			inventory.setItem(53, Gui.button(economy, Gui.BTN_NEXT, Material.ARROW,
					"&ePagina siguiente", "&7Pagina " + (page + 2) + "/" + pages));
		}
		List<String> eventLore = new ArrayList<>();
		List<MarketManager.MarketEvent> events = market.activeEvents();
		if (events.isEmpty()) {
			eventLore.add("&7No hay eventos activos.");
		}
		for (MarketManager.MarketEvent event : events) {
			eventLore.add(event.name + " &7(" + mult(event.mult) + ", quedan " + event.minutesLeft() + " min)");
		}
		inventory.setItem(47, Gui.button(economy, Gui.BTN_BANNER, Material.WHITE_BANNER,
				"&6Eventos del mercado", eventLore));
		inventory.setItem(46, Gui.button(economy, Gui.BTN_BACK, Material.ARROW,
				"&eCategorias", "&7Volver al mercado"));
		inventory.setItem(48, Gui.item(Material.BOOK, "&6\u00BFComo funciona?", Explain.help()));
		inventory.setItem(49, Gui.button(economy, Gui.BTN_CLOSE, Material.BARRIER, "&cCerrar"));
		inventory.setItem(51, Gui.button(economy, Gui.BTN_BANK, Material.GOLD_BLOCK,
				"&6Banco del Reino", "&7Click para abrir tu cuenta"));
		if (keys.isEmpty()) {
			inventory.setItem(22, Gui.item(Material.PAPER, "&7Aun no hay objetos en el mercado",
					"&8Un admin debe activar los precios dinamicos de un mercader:",
					"&8mira al mercader y usa &f/mercadoadmin tienda"));
		}
	}

	@Override
	protected void onClick(int slot, ClickType click) {
		if (slot < 0 || slot >= 54) return;
		if (slot < PAGE_SIZE) {
			int index = page * PAGE_SIZE + slot;
			if (index < keys.size()) {
				Sfx.play(player, Sfx.Type.COIN);
				new GraphMenu(economy, player, keys.get(index), 1, category).open();
			}
			return;
		}
		switch (slot) {
		case 45 -> {
			if (page > 0) {
				page--;
				Sfx.play(player, Sfx.Type.PAGE);
				refresh();
			}
		}
		case 53 -> {
			page++;
			Sfx.play(player, Sfx.Type.PAGE);
			refresh();
		}
		case 46 -> {
			Sfx.play(player, Sfx.Type.PAGE);
			new CategoryMenu(economy, player).open();
		}
		case 51 -> {
			Sfx.play(player, Sfx.Type.CLICK);
			new BankMenu(economy, player).open();
		}
		case 49 -> player.closeInventory();
		default -> {
			// Sin accion.
		}
		}
	}
}
