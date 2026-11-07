package me.medieval.shops.bank;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

import com.nisovin.shopkeepers.api.shopkeeper.Shopkeeper;

import me.medieval.shops.MedievalEconomy;
import me.medieval.shops.Targeting;
import me.medieval.shops.currency.Currency;
import me.medieval.shops.currency.CurrencySet;
import me.medieval.shops.gui.BankMenu;

public final class BankCommand implements TabExecutor {

	private static final String P = "&6&l[Banco] &r";

	private final MedievalEconomy economy;
	private final boolean admin;

	public BankCommand(MedievalEconomy economy, boolean admin) {
		this.economy = economy;
		this.admin = admin;
	}

	private static void msg(CommandSender to, String text) {
		to.sendMessage(MedievalEconomy.color(P + text));
	}

	@Override
	public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
		BankManager bank = economy.bank();
		if (!bank.isEnabled()) {
			msg(sender, "&cEl banco esta desactivado.");
			return true;
		}
		return admin ? adminCommand(sender, bank, args) : userCommand(sender, bank, args);
	}

	// ---------------------------------------------------------------- Jugador (banco global)

	private boolean userCommand(CommandSender sender, BankManager bank, String[] args) {
		if (!sender.hasPermission("shopkeeper.bank")) {
			msg(sender, "&cNo tienes permiso.");
			return true;
		}
		if (!(sender instanceof Player player)) {
			msg(sender, "&cSolo jugadores.");
			return true;
		}
		if (!bank.isGlobalAllowed()) {
			msg(player, "&cEl banco solo se usa hablando con un banquero.");
			return true;
		}
		String key = BankManager.GLOBAL;
		CurrencySet set = bank.currencies(key);
		if (args.length == 0) {
			new BankMenu(economy, player).open();
			return true;
		}
		String sub = args[0].toLowerCase(Locale.ROOT);
		switch (sub) {
		case "saldo" -> msg(player, "Saldo: &e" + BankManager.format(bank.getBalance(key, player.getUniqueId()), set));
		case "depositar" -> {
			if (args.length < 2) {
				msg(player, "&7Uso: /banco depositar <monto|todo>");
				return true;
			}
			long deposited = depositValue(player, bank, key, set, args[1]);
			if (deposited <= 0) msg(player, "&cNo se pudo depositar nada (monedas insuficientes o limite).");
			else msg(player, "&aDepositaste &e" + deposited + " &amonedas. Saldo: &e"
					+ BankManager.format(bank.getBalance(key, player.getUniqueId()), set));
		}
		case "retirar" -> {
			if (args.length < 2) {
				msg(player, "&7Uso: /banco retirar <monto|todo>");
				return true;
			}
			long withdrawn = withdrawValue(player, bank, key, set, args[1]);
			if (withdrawn <= 0) msg(player, "&cNo se pudo retirar nada (saldo insuficiente o inventario lleno).");
			else msg(player, "&aRetiraste &e" + withdrawn + " &amonedas. Saldo: &e"
					+ BankManager.format(bank.getBalance(key, player.getUniqueId()), set));
		}
		case "pagar" -> pay(player, bank, key, args);
		default -> msg(player, "&7Uso: /banco [saldo|depositar|retirar|pagar]");
		}
		return true;
	}

	private static long parseAmount(String s) {
		try {
			long value = Long.parseLong(s);
			return value > 1_000_000_000_000L ? -1L : value; // tope de seguridad
		} catch (NumberFormatException e) {
			return -1L;
		}
	}

	private long depositValue(Player player, BankManager bank, String key, CurrencySet set, String arg) {
		Currency base = set.getBase();
		Currency high = set.getHighOrNull();
		long wanted;
		if (arg.equalsIgnoreCase("todo")) {
			wanted = Long.MAX_VALUE;
		} else {
			wanted = parseAmount(arg);
			if (wanted <= 0) return 0;
		}
		long total = 0;
		if (high != null) {
			long hv = Math.max(1, high.getValue());
			long highWanted = wanted == Long.MAX_VALUE ? Integer.MAX_VALUE : wanted / hv;
			int done = bank.deposit(player, key, high, (int) Math.min(highWanted, Integer.MAX_VALUE));
			total += done * hv;
		}
		long remaining = wanted == Long.MAX_VALUE ? Integer.MAX_VALUE : wanted - total;
		if (remaining > 0) {
			total += bank.deposit(player, key, base, (int) Math.min(remaining, Integer.MAX_VALUE))
					* (long) base.getValue();
		}
		return total;
	}

	private long withdrawValue(Player player, BankManager bank, String key, CurrencySet set, String arg) {
		Currency base = set.getBase();
		Currency high = set.getHighOrNull();
		long balance = bank.getBalance(key, player.getUniqueId());
		long wanted = arg.equalsIgnoreCase("todo") ? balance : parseAmount(arg);
		if (wanted <= 0) return 0;
		wanted = Math.min(wanted, balance);
		long total = 0;
		if (high != null) {
			long hv = Math.max(1, high.getValue());
			int done = bank.withdraw(player, key, high, (int) Math.min(wanted / hv, Integer.MAX_VALUE));
			total += done * hv;
		}
		long remaining = wanted - total;
		if (remaining > 0) {
			total += bank.withdraw(player, key, base, (int) Math.min(remaining, Integer.MAX_VALUE))
					* (long) base.getValue();
		}
		return total;
	}

	private void pay(Player player, BankManager bank, String key, String[] args) {
		if (args.length < 3) {
			msg(player, "&7Uso: /banco pagar <jugador> <monto>");
			return;
		}
		UUID target = bank.findByName(args[1]);
		if (target == null) {
			msg(player, "&cJugador desconocido (debe haber usado el banco o estar conectado).");
			return;
		}
		if (target.equals(player.getUniqueId())) {
			msg(player, "&cNo puedes pagarte a ti mismo.");
			return;
		}
		long amount = parseAmount(args[2]);
		if (amount <= 0) {
			msg(player, "&cMonto invalido.");
			return;
		}
		long tax = (long) Math.floor(amount * bank.getTransferTaxPercent() / 100.0D);
		long received = amount - tax;
		if (bank.getBalance(key, player.getUniqueId()) < amount) {
			msg(player, "&cSaldo insuficiente.");
			return;
		}
		if (!bank.add(key, target, received)) {
			msg(player, "&cEl destinatario no puede recibir ese monto (limite de saldo).");
			return;
		}
		bank.add(key, player.getUniqueId(), -amount);
		bank.remember(player.getUniqueId(), player.getName());
		msg(player, "&aPagaste &e" + amount + " &aa &f" + bank.nameOf(target)
				+ (tax > 0 ? " &7(impuesto: " + tax + ")" : ""));
		Player online = Bukkit.getPlayer(target);
		if (online != null) {
			msg(online, "&a" + player.getName() + " te envio &e" + received + " &amonedas.");
		}
	}

	// ---------------------------------------------------------------- Admin

	/** Banco al que se refiere el admin: el del banquero al que mira, o el global. */
	private String targetBank(CommandSender sender) {
		if (sender instanceof Player player) {
			Shopkeeper looked = Targeting.lookingAt(player, 8.0D);
			if (looked != null && economy.profiles().isBanker(looked.getUniqueId())) {
				return BankManager.keyOf(looked.getUniqueId());
			}
		}
		return BankManager.GLOBAL;
	}

	private boolean adminCommand(CommandSender sender, BankManager bank, String[] args) {
		if (!sender.hasPermission("shopkeeper.bank.admin")) {
			msg(sender, "&cNo tienes permiso.");
			return true;
		}
		if (args.length == 0) {
			msg(sender, "&7Uso: /bancoadmin <ver|dar|quitar|fijar|top|npc> ...  (mirando a un banquero = su banco)");
			return true;
		}
		String sub = args[0].toLowerCase(Locale.ROOT);
		String key = targetBank(sender);
		String where = BankManager.GLOBAL.equals(key) ? "banco global" : "banco del mercader";
		switch (sub) {
		case "npc" -> {
			if (!(sender instanceof Player player)) {
				msg(sender, "&cSolo jugadores.");
				return true;
			}
			Shopkeeper target = Targeting.lookingAt(player, 8.0D);
			if (target == null) {
				msg(player, "&cMira a un mercader (tienda de admin con al menos 1 trade).");
				return true;
			}
			boolean now = !economy.profiles().isBanker(target.getUniqueId());
			economy.profiles().setBanker(target.getUniqueId(), now);
			msg(player, now ? "&aEse mercader ahora es &eBanquero&a: al hacerle click abre SU banco."
					: "&eEse mercader ya no es banquero (sus cuentas se conservan).");
		}
		case "top" -> {
			List<Map.Entry<UUID, Long>> list = new ArrayList<>(bank.all(key).entrySet());
			list.sort((a, b) -> Long.compare(b.getValue(), a.getValue()));
			msg(sender, "&eTop de saldos (" + where + "):");
			for (int i = 0; i < Math.min(10, list.size()); i++) {
				msg(sender, "&7" + (i + 1) + ". &f" + bank.nameOf(list.get(i).getKey()) + " &e"
						+ list.get(i).getValue());
			}
		}
		case "ver", "dar", "quitar", "fijar" -> {
			if (args.length < 2 || (!sub.equals("ver") && args.length < 3)) {
				msg(sender, "&7Uso: /bancoadmin " + sub + " <jugador>" + (sub.equals("ver") ? "" : " <monto>"));
				return true;
			}
			UUID target = bank.findByName(args[1]);
			if (target == null) {
				msg(sender, "&cJugador desconocido.");
				return true;
			}
			CurrencySet set = bank.currencies(key);
			if (sub.equals("ver")) {
				msg(sender, "&f" + bank.nameOf(target) + " &7(" + where + "): &e"
						+ BankManager.format(bank.getBalance(key, target), set));
				return true;
			}
			long amount = parseAmount(args[2]);
			if (amount < 0) {
				msg(sender, "&cMonto invalido.");
				return true;
			}
			long current = bank.getBalance(key, target);
			switch (sub) {
			case "dar" -> bank.setBalance(key, target, current + amount);
			case "quitar" -> bank.setBalance(key, target, Math.max(0, current - amount));
			default -> bank.setBalance(key, target, amount);
			}
			msg(sender, "&aSaldo de &f" + bank.nameOf(target) + " &7(" + where + ")&a: &e"
					+ bank.getBalance(key, target));
		}
		default -> msg(sender, "&7Uso: /bancoadmin <ver|dar|quitar|fijar|top|npc> ...");
		}
		return true;
	}

	@Override
	public List<String> onTabComplete(CommandSender sender, Command cmd, String alias, String[] args) {
		List<String> options = new ArrayList<>();
		if (args.length == 1) {
			options.addAll(admin ? Arrays.asList("ver", "dar", "quitar", "fijar", "top", "npc")
					: Arrays.asList("saldo", "depositar", "retirar", "pagar"));
		} else if (args.length == 2) {
			if (admin || args[0].equalsIgnoreCase("pagar")) {
				for (Player p : Bukkit.getOnlinePlayers()) options.add(p.getName());
			} else if (args[0].equalsIgnoreCase("depositar") || args[0].equalsIgnoreCase("retirar")) {
				options.add("todo");
			}
		}
		String prefix = args.length == 0 ? "" : args[args.length - 1].toLowerCase(Locale.ROOT);
		options.removeIf(s -> !s.toLowerCase(Locale.ROOT).startsWith(prefix));
		return options;
	}
}
