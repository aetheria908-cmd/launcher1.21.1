package me.medieval.shops.gui;

import java.util.ArrayList;
import java.util.List;

import org.bukkit.Material;
import org.bukkit.entity.Player;

import me.medieval.shops.MedievalEconomy;
import me.medieval.shops.Sfx;
import me.medieval.shops.market.MarketCategory;
import me.medieval.shops.market.MarketManager;

/** Menu principal del mercado: una puerta por categoria (botones anchos con fondo propio). */
public final class CategoryMenu extends ButtonMenu {

	private final MarketManager market;

	public CategoryMenu(MedievalEconomy economy, Player player) {
		super(economy, player, Layouts.HUB, economy.config().getString("gui.titulos.mercado", "&0Mercado del Reino"));
		this.market = economy.market();
	}

	private static String idOf(MarketCategory category) {
		return switch (category) {
		case FORJA -> "forja";
		case GRANJA -> "granja";
		case ALQUIMIA -> "alquimia";
		case MINERALES -> "minas";
		case CONSTRUCCION -> "construccion";
		case VARIOS -> "bazar";
		};
	}

	@Override
	protected void buildButtons() {
		List<MarketManager.MarketEvent> events = market.activeEvents();
		inventory.setItem(4, Gui.item(Material.GOLD_INGOT, "&6&lMercado del Reino",
				"&7Los precios cambian segun lo que hace la gente.", "&7Eventos activos: &f" + events.size(),
				"", "&eElige una categoria."));

		for (MarketCategory category : MarketCategory.values()) {
			int count = 0;
			String hottest = null;
			double hottestDev = 0.0D;
			for (String key : market.keys()) {
				if (MarketCategory.of(key) != category) continue;
				count++;
				double dev = Math.abs(market.getMultiplier(key) - 1.0D);
				if (dev > hottestDev) {
					hottestDev = dev;
					hottest = key;
				}
			}
			List<String> lore = new ArrayList<>();
			for (String line : category.getDescription()) lore.add(line);
			lore.add("");
			lore.add("&7Objetos con precio: &f" + count);
			if (hottest != null && hottestDev > 0.02D) {
				double m = market.getMultiplier(hottest);
				lore.add("&7Lo que mas cambio: &f" + MedievalEconomy.pretty(hottest) + " " + Explain.priceState(m)
						+ " &7(" + Explain.percent(m) + ")");
			}
			lore.add("");
			lore.add("&eClick: &fentrar");
			button(idOf(category), category.getIcon(), category.getDisplayName(), lore, click -> {
				Sfx.play(player, Sfx.Type.PAGE);
				new MarketMenu(economy, player, 0, category).open();
			});
		}

		button("todos", Material.MAP, "&6&lTodos los objetos",
				List.of("&7Lista completa de precios.", "", "&eClick: &fver todo"), click -> {
					Sfx.play(player, Sfx.Type.PAGE);
					new MarketMenu(economy, player, 0, null).open();
				});

		List<String> eventLore = new ArrayList<>();
		if (events.isEmpty()) eventLore.add("&7No hay eventos activos.");
		for (MarketManager.MarketEvent event : events) {
			eventLore.add(event.name + " &7(" + MarketMenu.mult(event.mult) + ", quedan " + event.minutesLeft() + " min)");
		}
		button("eventos", Material.WHITE_BANNER, "&6Eventos del mercado", eventLore, click -> Sfx.play(player, Sfx.Type.CLICK));

		button("banco", Material.GOLD_BLOCK, "&6Banco del Reino", List.of("&7Click para abrir tu cuenta"), click -> {
			Sfx.play(player, Sfx.Type.CLICK);
			new BankMenu(economy, player).open();
		});

		button("ayuda", Material.BOOK, "&6\u00BFComo funciona?", List.of(Explain.help()), click -> {
			Sfx.play(player, Sfx.Type.CLICK);
			player.closeInventory();
			for (String line : Explain.help()) {
				player.sendMessage(MedievalEconomy.color(line.isEmpty() ? " " : "&6&l[Mercado] &r" + line));
			}
		});

		button("cerrar", Material.BARRIER, "&cCerrar", List.of(), click -> player.closeInventory());
	}
}
