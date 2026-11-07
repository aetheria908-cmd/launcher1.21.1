package me.medieval.shops;

import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

import com.nisovin.shopkeepers.api.events.ShopkeeperAddedEvent;
import com.nisovin.shopkeepers.api.events.ShopkeeperEditedEvent;
import com.nisovin.shopkeepers.api.events.ShopkeeperRemoveEvent;
import com.nisovin.shopkeepers.api.shopkeeper.Shopkeeper;
import com.nisovin.shopkeepers.api.shopkeeper.TradingRecipe;
import com.nisovin.shopkeepers.api.events.ShopkeeperOpenUIEvent;
import com.nisovin.shopkeepers.api.events.ShopkeeperTradeCompletedEvent;
import com.nisovin.shopkeepers.api.events.ShopkeeperTradeEvent;
import com.nisovin.shopkeepers.api.ui.DefaultUITypes;
import me.medieval.shops.bank.BankManager;
import me.medieval.shops.currency.Currencies;
import me.medieval.shops.gui.Explain;
import me.medieval.shops.gui.BankMenu;

final class EconomyListener implements Listener {

	private final MedievalEconomy economy;

	EconomyListener(MedievalEconomy economy) {
		this.economy = economy;
	}

	// Un mercader marcado como banquero abre el banco en vez del comercio.
	@EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
	void onOpenUI(ShopkeeperOpenUIEvent event) {
		if (!DefaultUITypes.TRADING().equals(event.getUIType())) return;
		Shopkeeper shopkeeper = event.getShopkeeper();
		// De noche, los mercaderes que tienen cama duermen y no atienden.
		if (economy.routes().isClosedForSleep(shopkeeper.getUniqueId())
				&& !event.getPlayer().hasPermission("shopkeeper.sleep.bypass")) {
			event.setCancelled(true);
			event.getPlayer().sendMessage(MedievalEconomy.color("&9&l[Mercader] &7Zzz... esta durmiendo. Vuelve a las &e"
					+ economy.routes().wakeTimeText() + "&7."));
			Sfx.play(event.getPlayer(), Sfx.Type.ERROR);
			return;
		}
		if (!economy.config().getBoolean("banco.habilitado", true)) return;
		if (!economy.profiles().isBanker(shopkeeper.getUniqueId())) return;
		event.setCancelled(true);
		Player player = event.getPlayer();
		String key = BankManager.keyOf(shopkeeper.getUniqueId());
		String name = shopkeeper.getName();
		Bukkit.getScheduler().runTask(economy.getPlugin(), () -> {
			if (player.isOnline()) new BankMenu(economy, player, key, name).open();
		});
	}

	// Antes de comprar: si la tienda de admin esta agotada (o ya tiene de sobra), se cancela.
	@EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
	void onTradeAttempt(ShopkeeperTradeEvent event) {
		try {
			UUID shopId = event.getShopkeeper().getUniqueId();
			if (!economy.pricing().isMarked(shopId)) return;
			TradingRecipe recipe = event.getTradingRecipe();
			Player buyer = event.getPlayer();
			// 1) precio obsoleto (ventana abierta antes de un cambio)  2) stock  3) limite diario / enfriamiento
			String message = economy.pricing().checkFreshPrice(shopId, recipe);
			if (message == null) message = economy.pricing().checkStock(shopId, recipe);
			if (message == null) {
				Object[] info = economy.market().classify(recipe, Currencies.of(shopId));
				message = economy.limiter().check(buyer.getUniqueId(), (String) info[0], !((Boolean) info[1]), (Integer) info[2]);
			}
			if (message != null) {
				event.setCancelled(true);
				event.getPlayer().sendMessage(MedievalEconomy.color("&6&l[Mercado] " + message));
				Sfx.play(event.getPlayer(), Sfx.Type.ERROR);
			}
		} catch (Exception e) {
			economy.getPlugin().getLogger().warning("Error comprobando el stock: " + e);
		}
	}

	// Cada compra completada sube/baja la demanda, suena y recalcula precios.
	@EventHandler(priority = EventPriority.MONITOR)
	void onTradeCompleted(ShopkeeperTradeCompletedEvent event) {
		try {
			ShopkeeperTradeEvent trade = event.getCompletedTrade();
			UUID shopId = event.getShopkeeper().getUniqueId();
			// Solo los mercaderes que tu marcas cuentan para la demanda y el mercado.
			if (!economy.pricing().isMarked(shopId)) return;
			TradingRecipe recipe = trade.getTradingRecipe();
			me.medieval.shops.currency.CurrencySet set = Currencies.of(shopId);
			economy.pricing().applyStockTrade(shopId, recipe);
			Object[] info = economy.market().classify(recipe, set);
			economy.limiter().record(trade.getPlayer().getUniqueId(), (String) info[0], !((Boolean) info[1]), (Integer) info[2]);
			Object[] changed = economy.market().recordTrade(recipe, set, trade.getPlayer().getUniqueId());
			Sfx.play(trade.getPlayer(), Sfx.Type.TRADE_OK);
			if (changed != null && economy.config().getBoolean("mercado.avisar-cambio-precio", true)) {
				String key = (String) changed[0];
				double before = (Double) changed[1];
				double after = (Double) changed[2];
				boolean bought = ((Integer) changed[3]) == 1;
				String arrow = after > before + 0.0004D ? "&c\u25B2 sube" : after < before - 0.0004D ? "&a\u25BC baja" : "&e\u25AC igual";
				trade.getPlayer().sendMessage(MedievalEconomy.color("&6&l[Mercado] &f" + MedievalEconomy.pretty(key)
						+ " &7(" + (bought ? "lo compraron" : "lo vendieron") + ") " + arrow + " &7-> ahora "
						+ Explain.priceState(after) + " &7(" + Explain.percent(after) + ")"));
			}
			economy.pricing().refreshSoon();
		} catch (Exception e) {
			economy.getPlugin().getLogger().warning("Error procesando una compra: " + e);
		}
	}

	// Si un admin edita una tienda dinamica, sus precios pasan a ser el nuevo precio base.
	@EventHandler(priority = EventPriority.MONITOR)
	void onEdited(ShopkeeperEditedEvent event) {
		economy.pricing().onEdited(event.getShopkeeper());
	}

	// Mercader nuevo: el mercado se activa solo (ver mercado.auto-activar en medieval.yml).
	@EventHandler(priority = EventPriority.MONITOR)
	void onAdded(ShopkeeperAddedEvent event) {
		try {
			boolean created = event.getCause() == ShopkeeperAddedEvent.Cause.CREATED;
			economy.pricing().autoMark(event.getShopkeeper(), created, true);
		} catch (Exception e) {
			economy.getPlugin().getLogger().warning("Error activando el mercado en un mercader: " + e);
		}
	}

	@EventHandler(priority = EventPriority.MONITOR)
	void onRemoved(ShopkeeperRemoveEvent event) {
		economy.pricing().forget(event.getShopkeeper().getUniqueId());
	}

	@EventHandler
	void onJoin(PlayerJoinEvent event) {
		if (!economy.config().getBoolean("gui.resourcepack.enviar-al-entrar", false)) return;
		String url = economy.config().getString("gui.resourcepack.url", "");
		if (url == null || url.isEmpty()) return;
		String sha1 = economy.config().getString("gui.resourcepack.sha1", "");
		Player player = event.getPlayer();
		Bukkit.getScheduler().runTaskLater(economy.getPlugin(), () -> {
			if (!player.isOnline()) return;
			if (sha1 != null && sha1.length() == 40) {
				player.setResourcePack(url, hex(sha1));
			} else {
				player.setResourcePack(url);
			}
		}, 40L);
	}

	private static byte[] hex(String s) {
		byte[] out = new byte[s.length() / 2];
		for (int i = 0; i < out.length; i++) {
			out[i] = (byte) Integer.parseInt(s.substring(i * 2, i * 2 + 2), 16);
		}
		return out;
	}
}
