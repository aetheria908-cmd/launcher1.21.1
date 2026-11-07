package me.medieval.shops.gui;

import java.util.List;
import java.util.UUID;

import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.ItemStack;

import com.nisovin.shopkeepers.api.shopkeeper.Shopkeeper;
import com.nisovin.shopkeepers.api.shopkeeper.admin.regular.RegularAdminShopkeeper;

import me.medieval.shops.MedievalEconomy;
import me.medieval.shops.ShopProfiles;
import me.medieval.shops.Sfx;
import me.medieval.shops.bank.BankManager;
import me.medieval.shops.currency.Currencies;
import me.medieval.shops.currency.Currency;
import me.medieval.shops.currency.CurrencySet;
import me.medieval.shops.market.ShopPricing;

/**
 * Panel de configuracion de UN mercader: precios dinamicos, stock, banquero y moneda propia.
 * La moneda se elige tomando un item con el mouse y haciendo click en su boton.
 */
public final class ShopConfigMenu extends ButtonMenu {

	private final Shopkeeper shop;

	public ShopConfigMenu(MedievalEconomy economy, Player player, Shopkeeper shop) {
		super(economy, player, Layouts.CONFIG, "&0Mercader #" + shop.getId());
		this.shop = shop;
	}

	@Override
	boolean allowsInventoryPickup() {
		return true;
	}

	private static String nameOf(ItemStack item) {
		if (item.hasItemMeta() && item.getItemMeta().hasDisplayName()) return item.getItemMeta().getDisplayName();
		return MedievalEconomy.pretty(item.getType().name());
	}

	private void say(String text) {
		player.sendMessage(MedievalEconomy.color("&6&l[Mercader] &r" + text));
	}

	private void afterCurrencyChange() {
		if (economy.pricing().isMarked(shop.getUniqueId())) {
			say("&7Si tus ofertas usan otra moneda, editalas con la nueva y vuelve a activar los precios dinamicos.");
			economy.pricing().refreshAll();
		}
		Sfx.play(player, Sfx.Type.COIN);
		refresh();
	}

	@Override
	protected void buildButtons() {
		UUID id = shop.getUniqueId();
		ShopPricing pricing = economy.pricing();
		ShopProfiles profiles = economy.profiles();
		boolean dynamic = pricing.isMarked(id);
		boolean banker = profiles.isBanker(id);
		boolean custom = profiles.hasCustomCurrency(id);
		boolean adminShop = shop instanceof RegularAdminShopkeeper;
		CurrencySet set = Currencies.of(id);
		Currency base = set.getBase();
		Currency high = set.getHighOrNull();

		String name = shop.getName();
		inventory.setItem(4, Gui.item(Material.NAME_TAG, "&6Mercader #" + shop.getId(),
				"&7Nombre: &f" + (name == null || name.isEmpty() ? "(sin nombre)" : name),
				"&7Posicion: &f" + shop.getPositionString(),
				"&7Precios dinamicos: " + (dynamic ? "&asi" : "&cno"),
				"&7Banquero: " + (banker ? "&asi" : "&cno"),
				"&7Moneda: &f" + base.getDisplayName() + (high == null ? "" : " / " + high.getDisplayName())
						+ (custom ? " &7(propia)" : " &7(global)")));

		// Fila 1: precios dinamicos, stock, banquero
		button("dinamicos", dynamic ? Material.LIME_DYE : Material.GRAY_DYE,
				"&ePrecios dinamicos: " + (dynamic ? "&aACTIVADOS" : "&cdesactivados"),
				List.of("&7Los precios de este mercader siguen la oferta y la demanda.",
						"&7Es AUTOMATICO en mercaderes nuevos; aqui puedes apagarlo.",
						"&7Los precios que escribas en el editor son los precios BASE.", "", "&eClick: &fcambiar"),
				click -> {
					if (pricing.isMarked(id)) {
						pricing.unmark(shop);
						say("&ePrecios dinamicos desactivados: vuelven los precios base.");
					} else {
						int count = pricing.mark(shop);
						if (count < 0) {
							Sfx.play(player, Sfx.Type.ERROR);
							say("&cEse tipo de tienda no admite precios dinamicos (usa admin, venta o compra).");
							return;
						}
						say("&aPrecios dinamicos activados. Ofertas detectadas: &e" + count);
					}
					Sfx.play(player, Sfx.Type.CLICK);
					refresh();
				});

		if (adminShop) {
			boolean stockOn = profiles.stockEnabled(id, economy.config().getBoolean("stock.habilitado", true));
			button("stock", stockOn ? Material.BARREL : Material.CHEST, "&eStock limitado: " + (stockOn ? "&aSI" : "&cno"),
					List.of("&7Cada oferta tiene &f" + pricing.stockMax(id) + " &7unidades y se repone con el tiempo.",
							"&7Cuanto menos quede, mas sube el precio. Si se agota, no se puede comprar.", "",
							"&eClick izq: &factivar/desactivar", "&eShift+click: &f+8 / -8 de stock maximo",
							"&eClick der: &fvalores por defecto"),
					click -> {
						boolean current = profiles.stockEnabled(id, economy.config().getBoolean("stock.habilitado", true));
						int max = pricing.stockMax(id);
						switch (click) {
						case LEFT -> profiles.setStockEnabled(id, !current);
						case RIGHT -> {
							profiles.setStockEnabled(id, null);
							profiles.setStockMax(id, 0);
						}
						case SHIFT_LEFT -> profiles.setStockMax(id, max + 8);
						case SHIFT_RIGHT -> profiles.setStockMax(id, Math.max(8, max - 8));
						default -> {
							return;
						}
						}
						Sfx.play(player, Sfx.Type.CLICK);
						refresh();
					});
		} else {
			button("stock", Material.BARRIER, "&7Stock limitado", List.of("&7Solo en mercaderes de admin."), null);
		}

		button("banquero", banker ? Material.GOLD_BLOCK : Material.IRON_BLOCK, "&eBanquero: " + (banker ? "&aSI" : "&cno"),
				List.of("&7Al hacerle click se abre SU banco (cuentas y moneda propias).",
						"&7Necesita al menos 1 trade para poder abrirse.", "", "&eClick: &fcambiar"),
				click -> {
					boolean now = !profiles.isBanker(id);
					profiles.setBanker(id, now);
					say(now ? "&aAhora es banquero: al hacerle click se abre su banco." : "&eYa no es banquero.");
					Sfx.play(player, Sfx.Type.CLICK);
					refresh();
				});

		// Fila 2: moneda base, moneda alta, valor
		button("moneda", base.getItemData().getTemplate(), "&eMoneda base: &f" + base.getDisplayName(),
				List.of("&7" + (custom ? "Moneda de ESTE mercader." : "Moneda global del servidor."),
						"&7Se usa el item COMPLETO (nombre, lore, modelo...).", "",
						"&eToma un item con el mouse", "&ey haz click aqui para usarlo como moneda."),
				click -> {
					if (cursor == null || !cursor.getType().isItem()) {
						say("&7Toma un item con el mouse (de tu inventario) y haz click en este boton.");
						return;
					}
					profiles.setBase(id, cursor);
					say("&aMoneda base: &f" + nameOf(cursor));
					afterCurrencyChange();
				});

		if (high != null) {
			button("alta", high.getItemData().getTemplate(), "&eMoneda alta: &f" + high.getDisplayName(),
					List.of("&7Vale &f" + high.getValue() + " &7monedas base.", "",
							"&eToma un item y haz click: cambiarla", "&eClick derecho sin item: quitarla"),
					this::highClick);
		} else {
			button("alta", Material.BARRIER, "&eMoneda alta: &7ninguna",
					List.of("", "&eToma un item y haz click aqui", "&epara ponerla."), this::highClick);
		}

		button("valor", Material.GOLD_NUGGET,
				"&eValor de la moneda alta: &f" + (high == null ? profiles.getHighValue(id) : high.getValue()),
				List.of("&7Cuantas monedas base vale una alta.", "", "&eClick izq: &f+1   &eClick der: &f-1",
						"&eShift: &f+5 / -5"),
				click -> {
					int delta = switch (click) {
					case LEFT -> 1;
					case RIGHT -> -1;
					case SHIFT_LEFT -> 5;
					case SHIFT_RIGHT -> -5;
					default -> 0;
					};
					if (delta != 0) {
						Currency current = Currencies.of(id).getHighOrNull();
						int value = current == null ? profiles.getHighValue(id) : current.getValue();
						profiles.setHighValue(id, value + delta);
						Sfx.play(player, Sfx.Type.CLICK);
						refresh();
					}
				});

		// Fila 3: moneda global, actualizar, banco
		button("reset", Material.RED_DYE, "&cUsar la moneda global",
				List.of("&7Quita la moneda propia de este mercader."), click -> {
					profiles.clearCurrency(id);
					say("&eAhora usa la moneda global.");
					afterCurrencyChange();
				});

		button("actualizar", Material.COMPASS, "&aActualizar precios ahora", List.of(), click -> {
			pricing.refreshAll();
			say("&aPrecios actualizados.");
			Sfx.play(player, Sfx.Type.CLICK);
		});

		if (banker) {
			button("abrirbanco", Material.GOLD_INGOT, "&6Abrir este banco", List.of("&7Mira las cuentas como cliente."), click -> {
				Sfx.play(player, Sfx.Type.PAGE);
				new BankMenu(economy, player, BankManager.keyOf(id), shop.getName()).open();
			});
		} else {
			button("abrirbanco", Material.IRON_INGOT, "&7Banco", List.of("&7Este mercader no es banquero."), null);
		}

		button("cerrar", Material.BARRIER, "&cCerrar", List.of(), click -> player.closeInventory());
	}

	private void highClick(ClickType click) {
		UUID id = shop.getUniqueId();
		ShopProfiles profiles = economy.profiles();
		if (cursor != null && cursor.getType().isItem()) {
			profiles.setHigh(id, cursor, profiles.getHighValue(id));
			say("&aMoneda alta: &f" + nameOf(cursor));
			afterCurrencyChange();
		} else if (click == ClickType.RIGHT) {
			profiles.setHigh(id, null, 0);
			say("&eMoneda alta quitada.");
			afterCurrencyChange();
		} else {
			say("&7Toma un item con el mouse y haz click aqui para ponerlo como moneda alta.");
		}
	}
}
