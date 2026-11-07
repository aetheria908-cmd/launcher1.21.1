package me.medieval.shops.market;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.Locale;

import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import com.nisovin.shopkeepers.api.shopkeeper.Shopkeeper;
import me.medieval.shops.MedievalEconomy;
import me.medieval.shops.ShopProfiles;
import me.medieval.shops.Targeting;
import me.medieval.shops.gui.CategoryMenu;
import me.medieval.shops.gui.GraphMenu;
import me.medieval.shops.gui.MarketMenu;
import me.medieval.shops.gui.ShopConfigMenu;

public final class MarketCommand implements TabExecutor {

	private static final String P = "&6&l[Mercado] &r";

	private final MedievalEconomy economy;
	private final boolean admin;

	public MarketCommand(MedievalEconomy economy, boolean admin) {
		this.economy = economy;
		this.admin = admin;
	}

	private static void msg(CommandSender to, String text) {
		to.sendMessage(MedievalEconomy.color(P + text));
	}

	@Override
	public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
		MarketManager market = economy.market();
		return admin ? adminCommand(sender, market, args) : userCommand(sender, market, args);
	}

	/** Item de la mano o nombre dado. */
	private static String resolveKey(CommandSender sender, String[] args, int index) {
		if (args.length > index) {
			Material material = Material.matchMaterial(args[index]);
			return material == null ? null : material.name();
		}
		if (sender instanceof Player player) {
			ItemStack hand = player.getInventory().getItemInMainHand();
			if (hand.getType() != Material.AIR) return MarketManager.keyOf(hand);
		}
		return null;
	}

	// ---------------------------------------------------------------- Jugador

	private boolean userCommand(CommandSender sender, MarketManager market, String[] args) {
		if (!sender.hasPermission("shopkeeper.market")) {
			msg(sender, "&cNo tienes permiso.");
			return true;
		}
		if (!(sender instanceof Player player)) {
			msg(sender, "&cSolo jugadores.");
			return true;
		}
		if (args.length == 0) {
			if (economy.config().getBoolean("mercado.categorias", true)) {
				new CategoryMenu(economy, player).open();
			} else {
				new MarketMenu(economy, player, 0).open();
			}
			return true;
		}
		String sub = args[0].toLowerCase(Locale.ROOT);
		switch (sub) {
		case "precio" -> {
			String key = resolveKey(player, args, 1);
			if (key == null) {
				msg(player, "&7Uso: /mercado precio [ITEM] (o sostiene un item)");
				return true;
			}
			double m = market.getMultiplier(key);
			msg(player, "&f" + MedievalEconomy.pretty(key) + "&7: " + MarketMenu.color(m)
					+ MarketMenu.mult(m) + " &7(" + MarketMenu.percent(m) + ") demanda &f"
					+ String.format(Locale.ROOT, "%.2f", market.getDemand(key)));
		}
		case "grafica" -> {
			String key = resolveKey(player, args, 1);
			if (key == null) {
				msg(player, "&7Uso: /mercado grafica [ITEM] (o sostiene un item)");
				return true;
			}
			new GraphMenu(economy, player, key, 1).open();
		}
		case "ayuda" -> {
			for (String line : me.medieval.shops.gui.Explain.help()) msg(player, line.isEmpty() ? " " : line);
		}
		case "eventos" -> {
			List<MarketManager.MarketEvent> events = market.activeEvents();
			if (events.isEmpty()) msg(player, "&7No hay eventos activos.");
			for (MarketManager.MarketEvent e : events) {
				msg(player, e.name + " &7x" + String.format(Locale.ROOT, "%.2f", e.mult) + " - quedan "
						+ e.minutesLeft() + " min");
			}
		}
		default -> msg(player, "&7Uso: /mercado [ayuda|precio|grafica|eventos]");
		}
		return true;
	}

	// ---------------------------------------------------------------- Admin

	private boolean adminCommand(CommandSender sender, MarketManager market, String[] args) {
		if (!sender.hasPermission("shopkeeper.market.admin")) {
			msg(sender, "&cNo tienes permiso.");
			return true;
		}
		if (args.length == 0) {
			msg(sender, "&7Uso: /mercadoadmin <evento|demanda|info|reiniciar|recargar|guardar> ...");
			return true;
		}
		String sub = args[0].toLowerCase(Locale.ROOT);
		switch (sub) {
		case "evento" -> event(sender, market, args);
		case "tienda" -> shop(sender, args);
		case "moneda" -> globalCurrency(sender, args);
		case "debug" -> debug(sender, args);
		case "demanda" -> {
			if (args.length < 3) {
				msg(sender, "&7Uso: /mercadoadmin demanda <ITEM> <valor>  (0 = normal)");
				return true;
			}
			String key = resolveKey(sender, args, 1);
			if (key == null) {
				msg(sender, "&cItem invalido.");
				return true;
			}
			try {
				market.setDemand(key, Double.parseDouble(args[2]));
			} catch (NumberFormatException e) {
				msg(sender, "&cValor invalido.");
				return true;
			}
			msg(sender, "&aDemanda de &f" + key + "&a: &e" + String.format(Locale.ROOT, "%.3f", market.getDemand(key))
					+ " &7(precio " + MarketMenu.mult(market.getMultiplier(key)) + ")");
		}
		case "info" -> {
			String key = resolveKey(sender, args, 1);
			if (key == null) {
				msg(sender, "&cItem invalido.");
				return true;
			}
			msg(sender, "&f" + key + " &7demanda &e" + String.format(Locale.ROOT, "%.3f", market.getDemand(key))
					+ " &7evento &e" + MarketMenu.mult(market.getEventMultiplier(key))
					+ " &7final &e" + MarketMenu.mult(market.getMultiplier(key))
					+ " &7muestras &e" + market.history(key).size());
		}
		case "reiniciar" -> {
			if (args.length < 2 || args[1].equalsIgnoreCase("todo")) {
				market.resetAll();
				msg(sender, "&aMercado reiniciado (demanda e historial).");
			} else {
				String key = resolveKey(sender, args, 1);
				if (key == null) {
					msg(sender, "&cItem invalido.");
					return true;
				}
				market.resetItem(key);
				msg(sender, "&aReiniciado: &f" + key);
			}
		}
		case "recargar" -> {
			economy.reload();
			msg(sender, "&aConfiguracion recargada.");
		}
		case "guardar" -> {
			market.save();
			economy.bank().save();
			economy.routes().save();
			msg(sender, "&aDatos guardados.");
		}
		default -> msg(sender, "&7Uso: /mercadoadmin <evento|demanda|info|reiniciar|recargar|guardar> ...");
		}
		return true;
	}

	/** /mercadoadmin tienda [config|marcar|desmarcar|banquero|moneda|moneda-alta|lista|refrescar] (mirando al mercader) */
	private void shop(CommandSender sender, String[] args) {
		ShopPricing pricing = economy.pricing();
		ShopProfiles profiles = economy.profiles();
		String sub = args.length >= 2 ? args[1].toLowerCase(Locale.ROOT) : "config";
		if (sub.equals("lista")) {
			if (pricing.markedIds().isEmpty()) msg(sender, "&7No hay tiendas con precios dinamicos.");
			for (UUID id : pricing.markedIds()) {
				Shopkeeper s = com.nisovin.shopkeepers.api.ShopkeepersAPI.getShopkeeperRegistry()
						.getShopkeeperByUniqueId(id);
				msg(sender, s == null ? "&c(tienda borrada) " + id : "&f#" + s.getId() + " &7" + s.getPositionString());
			}
			return;
		}
		if (sub.equals("refrescar")) {
			pricing.refreshAll();
			msg(sender, "&aPrecios actualizados.");
			return;
		}
		if (sub.equals("restaurar-todo")) {
			msg(sender, "&aPrecios base restaurados en &e" + pricing.restoreAllBase() + " &amercaderes (siguen en el mercado).");
			msg(sender, "&7Util antes de quitar el addon. Se vuelven a mover en el proximo minuto si no lo desactivas.");
			return;
		}
		if (!(sender instanceof Player player)) {
			msg(sender, "&cSolo jugadores.");
			return;
		}
		Shopkeeper target = Targeting.lookingAt(player, 8.0D);
		if (target == null) {
			msg(player, "&cMira a un mercader (a menos de 8 bloques).");
			return;
		}
		UUID id = target.getUniqueId();
		switch (sub) {
		case "config" -> new ShopConfigMenu(economy, player, target).open();
		case "marcar" -> {
			int count = pricing.mark(target);
			if (count < 0) msg(player, "&cEse tipo de tienda no admite precios dinamicos (usa tiendas de admin, de venta o de compra).");
			else msg(player, "&aTienda " + pricing.describe(target) + " ahora sigue el mercado. Ofertas: &e" + count
					+ " &7(sus precios actuales son el precio BASE).");
		}
		case "desmarcar" -> msg(player, pricing.unmark(target) ? "&aTienda " + target.getId()
				+ " vuelve a sus precios base." : "&cEsa tienda no estaba marcada.");
		case "banquero" -> {
			boolean now = !profiles.isBanker(id);
			profiles.setBanker(id, now);
			msg(player, now ? "&aAhora es banquero (abre SU banco al hacerle click)." : "&eYa no es banquero.");
		}
		case "moneda" -> {
			if (args.length < 3) {
				msg(player, "&7Uso: /mercadoadmin tienda moneda <ITEM|mano|global>");
				return;
			}
			if (args[2].equalsIgnoreCase("global")) {
				profiles.clearCurrency(id);
				msg(player, "&eUsa la moneda global.");
				return;
			}
			ItemStack m = itemArg(player, args[2]);
			if (m == null) {
				msg(player, "&cItem invalido (para usar el de tu mano escribe: mano).");
				return;
			}
			profiles.setBase(id, m);
			msg(player, "&aMoneda base de este mercader: &f" + nameOf(m));
			pricing.refreshAll();
		}
		case "moneda-alta" -> {
			if (args.length < 3) {
				msg(player, "&7Uso: /mercadoadmin tienda moneda-alta <ITEM|mano|ninguna> [valor]");
				return;
			}
			if (args[2].equalsIgnoreCase("ninguna")) {
				profiles.setHigh(id, null, 0);
				msg(player, "&eMoneda alta quitada.");
				return;
			}
			ItemStack m = itemArg(player, args[2]);
			if (m == null) {
				msg(player, "&cItem invalido (para usar el de tu mano escribe: mano).");
				return;
			}
			int value = profiles.getHighValue(id);
			if (args.length >= 4) {
				try {
					value = Integer.parseInt(args[3]);
				} catch (NumberFormatException e) {
					msg(player, "&cValor invalido.");
					return;
				}
			}
			profiles.setHigh(id, m, value);
			msg(player, "&aMoneda alta de este mercader: &f" + nameOf(m) + " &7(x" + profiles.getHighValue(id) + ")");
			pricing.refreshAll();
		}
		default -> msg(player, "&7Uso: /mercadoadmin tienda <config|marcar|desmarcar|banquero|moneda|moneda-alta|lista|refrescar>");
		}
	}

	/** /mercadoadmin debug  (mirando a un mercader)  o  /mercadoadmin debug <ITEM> */
	private void debug(CommandSender sender, String[] args) {
		if (args.length >= 2) {
			Material m = Material.matchMaterial(args[1]);
			if (m == null) {
				msg(sender, "&cItem invalido.");
				return;
			}
			String key = m.name();
			MarketManager market = economy.market();
			msg(sender, "&6&lDiagnostico de &f" + MedievalEconomy.pretty(key));
			msg(sender, "&7- En el mercado: " + (market.hasKey(key) ? "&asi" : "&cno (ningun mercader activo lo vende/compra todavia)"));
			msg(sender, "&7- Demanda: " + me.medieval.shops.gui.Explain.demand(market.demandLevel(key)) + " &7(valor "
					+ String.format(Locale.ROOT, "%.3f", market.getDemand(key)) + ")");
			msg(sender, "&7- Evento: x" + String.format(Locale.ROOT, "%.2f", market.getEventMultiplier(key))
					+ "  &7Precio final: " + me.medieval.shops.gui.Explain.priceLine(market.getMultiplier(key)));
			for (String line : economy.pricing().priceLines(key)) msg(sender, line);
			return;
		}
		if (!(sender instanceof Player player)) {
			msg(sender, "&7Uso: /mercadoadmin debug <ITEM>");
			return;
		}
		Shopkeeper target = Targeting.lookingAt(player, 8.0D);
		if (target == null) {
			msg(player, "&cMira a un mercader (a menos de 8 bloques) o usa /mercadoadmin debug <ITEM>.");
			return;
		}
		for (String line : economy.pricing().debugLines(target)) msg(player, line);
	}

	/** "mano" = el item que tienes en la mano, COMPLETO (nombre, lore, modelo, NBT). */
	private static ItemStack itemArg(Player player, String arg) {
		if (arg.equalsIgnoreCase("mano")) {
			ItemStack hand = player.getInventory().getItemInMainHand();
			if (hand.getType() == Material.AIR) return null;
			ItemStack copy = hand.clone();
			copy.setAmount(1);
			return copy;
		}
		Material m = Material.matchMaterial(arg);
		return (m == null || m == Material.AIR || !m.isItem()) ? null : new ItemStack(m);
	}

	private static String nameOf(ItemStack item) {
		if (item.hasItemMeta() && item.getItemMeta().hasDisplayName()) return item.getItemMeta().getDisplayName();
		return MedievalEconomy.pretty(item.getType().name());
	}

	/** /mercadoadmin moneda <base|alta|quitar-alta|ver|restablecer> [valor]  (usa el item de tu mano) */
	private void globalCurrency(CommandSender sender, String[] args) {
		String sub = args.length >= 2 ? args[1].toLowerCase(Locale.ROOT) : "ver";
		me.medieval.shops.currency.CurrencySet now = me.medieval.shops.currency.Currencies.global();
		if (sub.equals("ver")) {
			msg(sender, "&7Moneda base: &f" + now.getBase().getDisplayName());
			msg(sender, now.getHighOrNull() == null ? "&7Moneda alta: &fninguna"
					: "&7Moneda alta: &f" + now.getHighOrNull().getDisplayName() + " &7(vale " + now.getHighOrNull().getValue() + ")");
			msg(sender, "&7Para cambiarla: toma el item en la mano y usa &f/mercadoadmin moneda base &7o &fmoneda alta [valor]");
			return;
		}
		if (sub.equals("restablecer")) {
			me.medieval.shops.currency.Currencies.clearGlobal(economy.getPlugin(), economy.config());
			economy.profiles().invalidateCaches();
			msg(sender, "&eMoneda global restablecida (se lee de la config de Shopkeepers).");
			return;
		}
		ItemStack high = now.getHighOrNull() == null ? null : now.getHighOrNull().getItemData().getTemplate();
		int value = now.getHighOrNull() == null ? 9 : now.getHighOrNull().getValue();
		ItemStack base = now.getBase().getItemData().getTemplate();
		if (sub.equals("quitar-alta")) {
			high = null;
		} else if (sub.equals("base") || sub.equals("alta")) {
			if (!(sender instanceof Player player)) {
				msg(sender, "&cSolo jugadores.");
				return;
			}
			ItemStack hand = itemArg(player, "mano");
			if (hand == null) {
				msg(player, "&cTen en la mano el item que quieres usar como moneda.");
				return;
			}
			if (sub.equals("base")) {
				base = hand;
			} else {
				high = hand;
				if (args.length >= 3) {
					try {
						value = Integer.parseInt(args[2]);
					} catch (NumberFormatException e) {
						msg(player, "&cValor invalido.");
						return;
					}
				}
			}
		} else {
			msg(sender, "&7Uso: /mercadoadmin moneda <base|alta [valor]|quitar-alta|ver|restablecer>  (item en la mano)");
			return;
		}
		me.medieval.shops.currency.Currencies.setGlobal(economy.getPlugin(), base, high, value);
		economy.profiles().invalidateCaches();
		economy.pricing().refreshAll();
		msg(sender, "&aMoneda global: &f" + nameOf(base) + (high == null ? "" : " &7/ &f" + nameOf(high) + " &7(x" + value + ")"));
	}

	private void event(CommandSender sender, MarketManager market, String[] args) {
		if (args.length < 2) {
			msg(sender, "&7Uso: /mercadoadmin evento <iniciar|parar|listar|plantillas|crear> ...");
			return;
		}
		switch (args[1].toLowerCase(Locale.ROOT)) {
		case "iniciar" -> {
			if (args.length < 3) {
				msg(sender, "&7Uso: /mercadoadmin evento iniciar <plantilla> [minutos]");
				return;
			}
			int minutes = 0;
			if (args.length >= 4) {
				try {
					minutes = Integer.parseInt(args[3]);
				} catch (NumberFormatException e) {
					msg(sender, "&cMinutos invalidos.");
					return;
				}
			}
			if (market.startTemplate(args[2], minutes) == null) {
				msg(sender, "&cPlantilla desconocida. Usa /mercadoadmin evento plantillas");
			}
		}
		case "parar" -> {
			String which = args.length >= 3 ? args[2] : "todos";
			msg(sender, market.stopEvent(which) ? "&aEvento(s) detenido(s)." : "&cNo se encontro ese evento.");
		}
		case "listar" -> {
			List<MarketManager.MarketEvent> events = market.activeEvents();
			if (events.isEmpty()) msg(sender, "&7No hay eventos activos.");
			for (MarketManager.MarketEvent e : events) {
				msg(sender, "&f" + e.id + " &7- " + e.name + " &7x" + String.format(Locale.ROOT, "%.2f", e.mult)
						+ " (" + e.minutesLeft() + " min) items: " + e.patterns);
			}
		}
		case "plantillas" -> msg(sender, "&7Plantillas: &f" + String.join(", ", market.templateIds()));
		case "crear" -> {
			// /mercadoadmin evento crear <mult> <minutos> <items,separados,por,coma> <nombre...>
			if (args.length < 6) {
				msg(sender, "&7Uso: /mercadoadmin evento crear <mult> <minutos> <ITEMS,coma|*> <nombre...>");
				return;
			}
			try {
				double mult = Double.parseDouble(args[2]);
				int minutes = Integer.parseInt(args[3]);
				List<String> items = new ArrayList<>(Arrays.asList(args[4].split(",")));
				String name = String.join(" ", Arrays.copyOfRange(args, 5, args.length));
				market.startCustom(name, mult, minutes, items);
			} catch (NumberFormatException e) {
				msg(sender, "&cNumero invalido.");
			}
		}
		default -> msg(sender, "&7Uso: /mercadoadmin evento <iniciar|parar|listar|plantillas|crear> ...");
		}
	}

	@Override
	public List<String> onTabComplete(CommandSender sender, Command cmd, String alias, String[] args) {
		List<String> options = new ArrayList<>();
		if (!admin) {
			if (args.length == 1) options.addAll(Arrays.asList("ayuda", "precio", "grafica", "eventos"));
		} else if (args.length == 1) {
			options.addAll(Arrays.asList("evento", "tienda", "moneda", "debug", "demanda", "info", "reiniciar", "recargar", "guardar"));
		} else if (args.length == 2 && args[0].equalsIgnoreCase("moneda")) {
			options.addAll(Arrays.asList("base", "alta", "quitar-alta", "ver", "restablecer"));
		} else if (args.length == 2 && args[0].equalsIgnoreCase("tienda")) {
			options.addAll(Arrays.asList("config", "marcar", "desmarcar", "banquero", "moneda", "moneda-alta", "lista", "refrescar", "restaurar-todo"));
		} else if (args.length == 2 && args[0].equalsIgnoreCase("evento")) {
			options.addAll(Arrays.asList("iniciar", "parar", "listar", "plantillas", "crear"));
		} else if (args.length == 3 && args[0].equalsIgnoreCase("evento")
				&& args[1].equalsIgnoreCase("iniciar")) {
			options.addAll(economy.market().templateIds());
		} else if (args.length == 2 && (args[0].equalsIgnoreCase("demanda") || args[0].equalsIgnoreCase("debug")
				|| args[0].equalsIgnoreCase("info") || args[0].equalsIgnoreCase("reiniciar"))) {
			options.addAll(economy.market().keys());
		}
		String prefix = args.length == 0 ? "" : args[args.length - 1].toLowerCase(Locale.ROOT);
		options.removeIf(s -> !s.toLowerCase(Locale.ROOT).startsWith(prefix));
		return options;
	}
}
