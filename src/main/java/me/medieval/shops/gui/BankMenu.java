package me.medieval.shops.gui;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.bukkit.Material;
import org.bukkit.entity.Player;

import me.medieval.shops.MedievalEconomy;
import me.medieval.shops.Sfx;
import me.medieval.shops.bank.BankManager;
import me.medieval.shops.currency.Currency;
import me.medieval.shops.currency.CurrencySet;

/** Menu del banco (global o de un mercader banquero): depositar y retirar monedas. */
public final class BankMenu extends ButtonMenu {

	private static final int[] AMOUNTS = { 1, 10, -1 }; // -1 = todo

	private final BankManager bank;
	private final String bankKey;
	private final String bankName;

	public BankMenu(MedievalEconomy economy, Player player) {
		this(economy, player, BankManager.GLOBAL, null);
	}

	public BankMenu(MedievalEconomy economy, Player player, String bankKey, String bankName) {
		super(economy, player, layoutFor(economy, bankKey), titleFor(economy, bankName));
		this.bank = economy.bank();
		this.bankKey = bankKey;
		this.bankName = bankName;
	}

	private static Layouts.Layout layoutFor(MedievalEconomy economy, String bankKey) {
		return economy.bank().currencies(bankKey).getHighOrNull() != null ? Layouts.BANK_HIGH : Layouts.BANK;
	}

	private static String titleFor(MedievalEconomy economy, String bankName) {
		String title = economy.config().getString("gui.titulos.banco", "&0Banco del Reino");
		if (bankName != null && !bankName.isEmpty()) {
			title = title + " &8- &0" + org.bukkit.ChatColor.stripColor(MedievalEconomy.color(bankName));
		}
		return title;
	}

	private static String label(int amount) {
		return amount < 0 ? "todo" : String.valueOf(amount);
	}

	@Override
	protected void buildButtons() {
		bank.remember(player.getUniqueId(), player.getName());
		CurrencySet set = bank.currencies(bankKey);
		long balance = bank.getBalance(bankKey, player.getUniqueId());

		List<String> balanceLore = new ArrayList<>();
		if (bankName != null && !bankName.isEmpty()) balanceLore.add("&7Banco: &f" + org.bukkit.ChatColor.stripColor(MedievalEconomy.color(bankName)));
		balanceLore.add("&7" + BankManager.format(balance, set));
		if (bank.getLimit() > 0) balanceLore.add("&7Limite de saldo: &f" + bank.getLimit());
		if (bank.getInterestPercent() > 0.0D) {
			balanceLore.add("&7Interes: &a" + String.format(Locale.ROOT, "%.2f", bank.getInterestPercent()) + "% &7por hora");
		}
		if (BankManager.GLOBAL.equals(bankKey)) balanceLore.add("&7Transferir: &f/banco pagar <jugador> <monto>");
		inventory.setItem(4, Gui.button(economy, Gui.BTN_COINS, Material.GOLD_BLOCK, "&6Tu saldo: &e" + balance, balanceLore));

		rows(set.getBase(), new String[] { "dep1", "dep10", "depall" }, new String[] { "ret1", "ret10", "retall" });
		if (set.getHighOrNull() != null) {
			rows(set.getHighOrNull(), new String[] { "hdep1", "hdep10", "hdepall" }, new String[] { "hret1", "hret10", "hretall" });
		}

		button("mercado", Material.MAP, "&6Mercado del Reino", List.of("&7Ver precios y graficas"), click -> {
			Sfx.play(player, Sfx.Type.PAGE);
			new CategoryMenu(economy, player).open();
		});
		button("cerrar", Material.BARRIER, "&cCerrar", List.of(), click -> player.closeInventory());
	}

	private void rows(Currency currency, String[] depositIds, String[] withdrawIds) {
		int have = BankManager.countCurrency(player.getInventory(), currency);
		long balance = bank.getBalance(bankKey, player.getUniqueId());
		long inBank = balance / Math.max(1, currency.getValue());
		for (int i = 0; i < AMOUNTS.length; i++) {
			int amount = AMOUNTS[i];
			button(depositIds[i], currency.getItemData().getTemplate(),
					"&aDepositar " + label(amount) + " &7" + currency.getDisplayName(),
					List.of("&7Tienes: &f" + have, "", "&eClick: &fdepositar"), click -> transfer(currency, amount, true));
			button(withdrawIds[i], currency.getItemData().getTemplate(),
					"&cRetirar " + label(amount) + " &7" + currency.getDisplayName(),
					List.of("&7En el banco: &f" + inBank, "", "&eClick: &fretirar"), click -> transfer(currency, amount, false));
		}
	}

	private void transfer(Currency currency, int amount, boolean deposit) {
		int done;
		if (deposit) {
			done = bank.deposit(player, bankKey, currency, amount < 0 ? Integer.MAX_VALUE : amount);
		} else {
			long balance = bank.getBalance(bankKey, player.getUniqueId());
			int max = (int) Math.min(Integer.MAX_VALUE, balance / Math.max(1, currency.getValue()));
			done = bank.withdraw(player, bankKey, currency, amount < 0 ? max : amount);
		}
		Sfx.play(player, done <= 0 ? Sfx.Type.ERROR : deposit ? Sfx.Type.DEPOSIT : Sfx.Type.WITHDRAW);
		if (done <= 0) {
			player.sendMessage(MedievalEconomy.color("&6&l[Banco] &cNo se pudo "
					+ (deposit ? "depositar (no tienes monedas o llegaste al limite)."
							: "retirar (saldo insuficiente o inventario lleno).")));
		} else {
			player.sendMessage(MedievalEconomy.color("&6&l[Banco] &a" + (deposit ? "Depositaste " : "Retiraste ")
					+ "&e" + done + " &a" + currency.getDisplayName() + "&a."));
		}
		refresh();
	}
}
