package me.medieval.shops.papi;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import me.medieval.shops.MedievalEconomy;
import me.medieval.shops.bank.BankManager;
import me.medieval.shops.gui.Explain;
import me.medieval.shops.gui.MarketMenu;
import me.medieval.shops.market.MarketManager;

/**
 * Placeholders para scoreboards, tab, hologramas, etc. (PlaceholderAPI):
 * %medieval_estado_<ITEM>%  %medieval_porcentaje_<ITEM>%  %medieval_multiplicador_<ITEM>%
 * %medieval_demanda_<ITEM>%  %medieval_tendencia_<ITEM>%  %medieval_eventos%
 * %medieval_eventos_cantidad%  %medieval_saldo%  %medieval_saldo_formato%
 */
public final class Placeholders extends PlaceholderExpansion {

	private final MedievalEconomy economy;

	public Placeholders(MedievalEconomy economy) {
		this.economy = economy;
	}

	@Override
	public String getIdentifier() {
		return "medieval";
	}

	@Override
	public String getAuthor() {
		return "ShopkeepersMedieval";
	}

	@Override
	public String getVersion() {
		return economy.getPlugin().getDescription().getVersion();
	}

	@Override
	public boolean persist() {
		return true;
	}

	private static String plain(String colored) {
		return ChatColor.stripColor(MedievalEconomy.color(colored));
	}

	@Override
	public String onRequest(OfflinePlayer player, String params) {
		if (params == null) return null;
		MarketManager market = economy.market();
		String lower = params.toLowerCase(Locale.ROOT);

		switch (lower) {
		case "saldo" -> {
			if (player == null) return "0";
			return String.valueOf(economy.bank().getBalance(BankManager.GLOBAL, player.getUniqueId()));
		}
		case "saldo_formato" -> {
			if (player == null) return "0";
			return BankManager.format(economy.bank().getBalance(BankManager.GLOBAL, player.getUniqueId()),
					economy.bank().currencies(BankManager.GLOBAL));
		}
		case "eventos_cantidad" -> {
			return String.valueOf(market.activeEvents().size());
		}
		case "eventos" -> {
			List<MarketManager.MarketEvent> events = market.activeEvents();
			if (events.isEmpty()) return "Ninguno";
			StringBuilder sb = new StringBuilder();
			for (MarketManager.MarketEvent event : events) {
				if (sb.length() > 0) sb.append(", ");
				sb.append(plain(event.name));
			}
			return sb.toString();
		}
		default -> {
			// Sigue abajo: placeholders por objeto
		}
		}

		int idx = lower.indexOf('_');
		if (idx <= 0) return null;
		String what = lower.substring(0, idx);
		Material material = Material.matchMaterial(params.substring(idx + 1));
		if (material == null) return "?";
		String key = material.name();
		double mult = market.getMultiplier(key);
		return switch (what) {
		case "estado" -> plain(Explain.priceState(mult));
		case "porcentaje" -> Explain.percent(mult);
		case "multiplicador" -> String.format(Locale.ROOT, "%.2f", mult);
		case "demanda" -> Explain.demandWord(market.demandLevel(key));
		case "tendencia" -> {
			double t = MarketMenu.trend(market, key);
			yield t > 0.01D ? "\u25B2" : t < -0.01D ? "\u25BC" : "\u25AC";
		}
		default -> null;
		};
	}
}
