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

/** Grafica de barras (9 columnas x 5 filas) con el historial de precio de un objeto. */
public final class GraphMenu extends Menu {

	private static final int[] BUCKET_MINUTES = { 10, 30, 120, 720 };
	private static final int COLUMNS = 9;
	private static final int ROWS = 5;

	private final MarketManager market;
	private final String key;
	private int period;
	private final MarketCategory from; // categoria desde la que se abrio (null = todos)

	public GraphMenu(MedievalEconomy economy, Player player, String key, int period) {
		this(economy, player, key, period, null);
	}

	public GraphMenu(MedievalEconomy economy, Player player, String key, int period,
			MarketCategory from) {
		super(economy, player);
		this.from = from;
		this.market = economy.market();
		this.key = key;
		this.period = Math.floorMod(period, BUCKET_MINUTES.length);
		String title = economy.config().getString("gui.titulos.grafica", "&0Grafica: {item}")
				.replace("{item}", MedievalEconomy.pretty(key));
		create(54, Gui.title(economy, title, Gui.BG_GRAPH));
	}

	private static String span(long minutes) {
		if (minutes >= 1440) {
			long d = minutes / 1440;
			long h = (minutes % 1440) / 60;
			return d + "d" + (h > 0 ? " " + h + "h" : "");
		}
		if (minutes >= 60) {
			long h = minutes / 60;
			long m = minutes % 60;
			return h + "h" + (m > 0 ? " " + m + "m" : "");
		}
		return minutes + "m";
	}

	@Override
	protected void render() {
		int bucket = BUCKET_MINUTES[period];
		long now = System.currentTimeMillis() / 60000L;
		double[] values = new double[COLUMNS];
		List<MarketManager.Sample> history = market.history(key);

		for (int i = 0; i < COLUMNS; i++) {
			long end = now - (long) (COLUMNS - 1 - i) * bucket;
			long start = end - bucket;
			double sum = 0.0D;
			int count = 0;
			for (MarketManager.Sample sample : history) {
				if (sample.minute > start && sample.minute <= end) {
					sum += sample.mult;
					count++;
				}
			}
			values[i] = count > 0 ? sum / count : Double.NaN;
		}
		double current = market.getMultiplier(key);
		if (Double.isNaN(values[COLUMNS - 1])) values[COLUMNS - 1] = current;
		for (int i = 1; i < COLUMNS; i++) {
			if (Double.isNaN(values[i]) && !Double.isNaN(values[i - 1])) values[i] = values[i - 1];
		}

		double lo = 1.0D;
		double hi = 1.0D;
		for (double v : values) {
			if (Double.isNaN(v)) continue;
			lo = Math.min(lo, v);
			hi = Math.max(hi, v);
		}
		double pad = Math.max(0.05D, (hi - lo) * 0.1D);
		lo -= pad;
		hi += pad;
		if (hi - lo < 0.2D) {
			double mid = (hi + lo) / 2.0D;
			lo = mid - 0.1D;
			hi = mid + 0.1D;
		}

		for (int c = 0; c < COLUMNS; c++) {
			double v = values[c];
			if (Double.isNaN(v)) continue;
			int height = (int) Math.max(1, Math.min(ROWS, 1 + Math.round((v - lo) / (hi - lo) * (ROWS - 1))));
			long minutesAgo = (long) (COLUMNS - 1 - c) * bucket + bucket / 2;
			String when = c == COLUMNS - 1 ? "Ahora" : "Hace " + span(minutesAgo);
			String name = "&e" + when;
			String lore1 = "&7Precio: " + MarketMenu.color(v) + MarketMenu.mult(v)
					+ " &7(" + MarketMenu.color(v) + MarketMenu.percent(v) + "&7)";
			boolean up = v >= 1.02D;
			boolean down = v <= 0.98D;
			Material pane = up ? Material.RED_STAINED_GLASS_PANE
					: down ? Material.LIME_STAINED_GLASS_PANE : Material.YELLOW_STAINED_GLASS_PANE;
			Material cap = up ? Material.RED_STAINED_GLASS
					: down ? Material.LIME_STAINED_GLASS : Material.YELLOW_STAINED_GLASS;
			for (int r = 0; r < height; r++) {
				int slot = (ROWS - 1 - r) * 9 + c;
				inventory.setItem(slot, Gui.item(r == height - 1 ? cap : pane, name, lore1));
			}
		}

		// Barra inferior
		if (!Gui.usesBackgrounds(economy)) {
			for (int i = 45; i < 54; i++) inventory.setItem(i, Gui.filler());
		}
		Material icon = Material.matchMaterial(key);
		if (icon == null || !icon.isItem()) icon = Material.PAPER;
		List<String> info = new ArrayList<>();
		info.add(Explain.priceLine(current));
		info.add(Explain.demandLine(market.demandLevel(key)));
		double eventMult = market.getEventMultiplier(key);
		if (Math.abs(eventMult - 1.0D) > 0.001D) {
			info.add("&7Evento activo: " + MarketMenu.color(eventMult) + MarketMenu.percent(eventMult));
		}
		List<String> shops = economy.pricing().priceLines(key);
		if (!shops.isEmpty()) {
			info.add("");
			info.add("&8Donde:");
			for (int n = 0; n < Math.min(6, shops.size()); n++) info.add(shops.get(n));
			if (shops.size() > 6) info.add("&8...y " + (shops.size() - 6) + " mas");
		}
		info.add("");
		info.add("&7Como leer la grafica:");
		info.add("&cBarra roja &7= estaba mas caro de lo normal");
		info.add("&aBarra verde &7= estaba mas barato de lo normal");
		info.add("&eBarra amarilla &7= precio normal");
		info.add("&7Cuanto mas alta la barra, mas caro estaba.");
		inventory.setItem(49, Gui.item(icon, "&6" + MedievalEconomy.pretty(key), info));

		inventory.setItem(46, Gui.item(Material.BOOK, "&6\u00BFComo funciona?", Explain.help()));
		inventory.setItem(45, Gui.button(economy, Gui.BTN_BACK, Material.ARROW, "&eVolver al mercado"));
		inventory.setItem(47, Gui.button(economy, Gui.BTN_CLOCK, Material.CLOCK, "&bPeriodo: &f"
				+ span((long) bucket * COLUMNS), "&7Cada columna: &f" + span(bucket),
				"&eClick: &fcambiar periodo"));
		inventory.setItem(51, Gui.button(economy, Gui.BTN_REFRESH, Material.COMPASS, "&aActualizar"));
		inventory.setItem(53, Gui.button(economy, Gui.BTN_CLOSE, Material.BARRIER, "&cCerrar"));
	}

	@Override
	protected void onClick(int slot, ClickType click) {
		switch (slot) {
		case 45 -> {
			Sfx.play(player, Sfx.Type.PAGE);
			new MarketMenu(economy, player, 0, from).open();
		}
		case 47 -> {
			period = (period + 1) % BUCKET_MINUTES.length;
			Sfx.play(player, Sfx.Type.CLICK);
			refresh();
			// El titulo muestra solo el objeto, asi que no hace falta reabrir.
		}
		case 51 -> refresh();
		case 53 -> player.closeInventory();
		default -> {
			// Sin accion.
		}
		}
	}
}
