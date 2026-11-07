package me.medieval.shops.route;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

import com.nisovin.shopkeepers.api.ShopkeepersAPI;
import com.nisovin.shopkeepers.api.shopkeeper.Shopkeeper;
import me.medieval.shops.MedievalEconomy;
import me.medieval.shops.Targeting;

public final class RouteCommand implements TabExecutor {

	private static final String P = "&6&l[Rutas] &r";

	private final MedievalEconomy economy;
	private final Map<UUID, UUID> selected = new HashMap<>(); // jugador -> shopkeeper

	public RouteCommand(MedievalEconomy economy) {
		this.economy = economy;
	}

	private static void msg(CommandSender to, String text) {
		to.sendMessage(MedievalEconomy.color(P + text));
	}

	private Shopkeeper resolve(Player player) {
		Shopkeeper looked = Targeting.lookingAt(player, 8.0D);
		if (looked != null) {
			selected.put(player.getUniqueId(), looked.getUniqueId());
			return looked;
		}
		UUID id = selected.get(player.getUniqueId());
		if (id == null) return null;
		return ShopkeepersAPI.getShopkeeperRegistry().getShopkeeperByUniqueId(id);
	}

	private static String describe(Shopkeeper shopkeeper) {
		String name = shopkeeper.getName();
		return "#" + shopkeeper.getId() + (name == null || name.isEmpty() ? "" : " (" + name + ")");
	}

	@Override
	public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
		if (!sender.hasPermission("shopkeeper.route")) {
			msg(sender, "&cNo tienes permiso.");
			return true;
		}
		if (!(sender instanceof Player player)) {
			msg(sender, "&cSolo jugadores.");
			return true;
		}
		RouteManager routes = economy.routes();
		if (args.length == 0) {
			help(player);
			return true;
		}
		String sub = args[0].toLowerCase(Locale.ROOT);

		if (sub.equals("estado")) {
			if (routes.all().isEmpty()) msg(player, "&7No hay rutas configuradas.");
			for (Map.Entry<UUID, List<RouteManager.Stop>> entry : routes.all().entrySet()) {
				Shopkeeper s = ShopkeepersAPI.getShopkeeperRegistry()
						.getShopkeeperByUniqueId(entry.getKey());
				msg(player, (s == null ? "&c(borrado)" : "&f" + describe(s)) + " &7- "
						+ entry.getValue().size() + " paradas" + (routes.isWalking(entry.getKey()) ? " &e(caminando)" : "")
						+ (routes.isSleeping(entry.getKey()) ? " &9(durmiendo)" : ""));
			}
			return true;
		}

		Shopkeeper target = resolve(player);
		if (target == null) {
			msg(player, "&cMira a un mercader (o seleccionalo antes mirandolo).");
			return true;
		}
		UUID id = target.getUniqueId();

		switch (sub) {
		case "seleccionar" -> msg(player, "&aSeleccionado: &f" + describe(target));
		case "agregar" -> {
			if (args.length < 2) {
				msg(player, "&7Uso: /ruta agregar <HH:mm>   (hora del reloj de Minecraft; 06:00 = amanecer)");
				return true;
			}
			int tick = RouteManager.parseTime(args[1]);
			if (tick < 0) {
				msg(player, "&cHora invalida. Formato HH:mm, ej. 09:30");
				return true;
			}
			Location loc = player.getLocation();
			routes.addStop(id, new RouteManager.Stop(tick, loc.getWorld().getName(), loc.getBlockX(),
					loc.getBlockY(), loc.getBlockZ(), loc.getYaw()));
			msg(player, "&aParada agregada para " + describe(target) + ": &e" + RouteManager.formatTime(tick)
					+ " &7en tu posicion actual.");
		}
		case "listar" -> {
			List<RouteManager.Stop> stops = routes.getStops(id);
			RouteManager.Stop bed = routes.getBed(id);
			if (bed != null) msg(player, "&9Cama: &7" + bed.world + " " + bed.x + " " + bed.y + " " + bed.z);
			if (stops.isEmpty()) msg(player, "&7" + describe(target) + " no tiene ruta.");
			for (int i = 0; i < stops.size(); i++) {
				RouteManager.Stop s = stops.get(i);
				msg(player, "&f" + (i + 1) + ". &e" + RouteManager.formatTime(s.tick) + " &7-> "
						+ s.world + " " + s.x + " " + s.y + " " + s.z);
			}
		}
		case "quitar" -> {
			int index = args.length >= 2 ? parseInt(args[1]) - 1 : -1;
			msg(player, routes.removeStop(id, index) ? "&aParada eliminada." : "&cNumero de parada invalido.");
		}
		case "limpiar" -> {
			routes.clearRoute(id);
			msg(player, "&aRuta eliminada.");
		}
		case "cama" -> {
			if (args.length >= 2 && args[1].equalsIgnoreCase("borrar")) {
				msg(player, routes.clearBed(id) ? "&aCama eliminada." : "&cEse mercader no tenia cama.");
			} else {
				Location loc = player.getLocation();
				routes.setBed(id, new RouteManager.Stop(0, loc.getWorld().getName(), loc.getBlockX(),
						loc.getBlockY(), loc.getBlockZ(), loc.getYaw()));
				msg(player, "&aCama fijada para " + describe(target) + " &7en tu posicion. De noche se ira a dormir ahi y "
						+ "no atendera hasta las &e" + routes.wakeTimeText() + "&7.");
				msg(player, "&7Tip: parate encima de una cama (bloque) y el aldeano se acostara de verdad.");
			}
		}
		case "ir" -> {
			int index = args.length >= 2 ? parseInt(args[1]) - 1 : -1;
			msg(player, routes.forceGo(id, index) ? "&aEl mercader va hacia la parada." : "&cParada invalida.");
		}
		default -> help(player);
		}
		return true;
	}

	private static int parseInt(String s) {
		try {
			return Integer.parseInt(s);
		} catch (NumberFormatException e) {
			return -1;
		}
	}

	private void help(Player player) {
		msg(player, "&7Mira a un mercader y usa:");
		msg(player, "&f/ruta agregar <HH:mm> &7- a esa hora el mercader ira a donde estas parado");
		msg(player, "&f/ruta cama &7- fija donde duerme de noche (parate en la cama);  &f/ruta cama borrar");
		msg(player, "&f/ruta listar | quitar <n> | limpiar | ir <n> | estado | seleccionar");
	}

	@Override
	public List<String> onTabComplete(CommandSender sender, Command cmd, String alias, String[] args) {
		List<String> options = new ArrayList<>();
		if (args.length == 1) {
			options.addAll(Arrays.asList("agregar", "cama", "listar", "quitar", "limpiar", "ir", "estado", "seleccionar"));
		}
		String prefix = args.length == 0 ? "" : args[args.length - 1].toLowerCase(Locale.ROOT);
		options.removeIf(s -> !s.toLowerCase(Locale.ROOT).startsWith(prefix));
		return options;
	}
}
