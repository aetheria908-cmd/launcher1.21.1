package me.medieval.shops.gui;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.bukkit.Material;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import me.medieval.shops.MedievalEconomy;

/** Utilidades para construir items y titulos de los menus. */
public final class Gui {

	// CustomModelData de los botones del resourcepack medieval (sobre PAPER).
	public static final int BTN_BACK = 1001;
	public static final int BTN_NEXT = 1002;
	public static final int BTN_PREV = 1003;
	public static final int BTN_CLOSE = 1004;
	public static final int BTN_CLOCK = 1005;
	public static final int BTN_REFRESH = 1006;
	public static final int BTN_DEPOSIT = 1007;
	public static final int BTN_WITHDRAW = 1008;
	public static final int BTN_COINS = 1009;
	public static final int BTN_SCROLL = 1010;
	public static final int BTN_BANNER = 1011;
	public static final int BTN_MARKET = 1012;
	public static final int BTN_BANK = 1013;
	/** Item totalmente transparente: solo muestra su tooltip (para los botones anchos del fondo). */
	public static final int HIT = 2000;

	// Caracteres de fondo del resourcepack (font default.json).
	public static final char BG_MARKET = '\uE000';
	public static final char BG_GRAPH = '\uE001';
	public static final char BG_BANK = '\uE002';
	// Fondos por categoria: ver MarketCategory (E003-E008).
	public static final char BG_CONFIG = '\uE009';

	private static boolean usePack(MedievalEconomy economy) {
		return economy.config().getBoolean("gui.usar-resourcepack", false);
	}

	public static ItemStack item(Material material, String name, String... lore) {
		return item(material, name, Arrays.asList(lore));
	}

	public static ItemStack item(Material material, String name, List<String> lore) {
		ItemStack stack = new ItemStack(material);
		ItemMeta meta = stack.getItemMeta();
		if (meta != null) {
			meta.setDisplayName(MedievalEconomy.color(name));
			List<String> colored = new ArrayList<>();
			for (String line : lore) {
				colored.add(MedievalEconomy.color(line));
			}
			meta.setLore(colored);
			meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ENCHANTS,
					ItemFlag.HIDE_ADDITIONAL_TOOLTIP);
			stack.setItemMeta(meta);
		}
		return stack;
	}

	public static ItemStack button(MedievalEconomy economy, int modelData, Material fallback,
			String name, String... lore) {
		return button(economy, modelData, fallback, name, Arrays.asList(lore));
	}

	/** Item parecido a una plantilla (conserva material, modelo, etc.) con otro nombre y lore. */
	public static ItemStack itemLike(ItemStack template, String name, String... lore) {
		ItemStack stack = template.clone();
		stack.setAmount(1);
		ItemMeta meta = stack.getItemMeta();
		if (meta != null) {
			meta.setDisplayName(MedievalEconomy.color(name));
			List<String> colored = new ArrayList<>();
			for (String line : lore) colored.add(MedievalEconomy.color(line));
			meta.setLore(colored);
			meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ENCHANTS, ItemFlag.HIDE_ADDITIONAL_TOOLTIP);
			stack.setItemMeta(meta);
		}
		return stack;
	}

	/** Boton: con resourcepack usa PAPER + CustomModelData, sino el material de reserva. */
	public static ItemStack button(MedievalEconomy economy, int modelData, Material fallback,
			String name, List<String> lore) {
		if (usePack(economy)) {
			ItemStack stack = item(Material.PAPER, name, lore);
			ItemMeta meta = stack.getItemMeta();
			if (meta != null) {
				meta.setCustomModelData(modelData);
				stack.setItemMeta(meta);
			}
			return stack;
		}
		return item(fallback, name, lore);
	}

	/** Parte invisible de un boton ancho dibujado en el fondo: solo muestra el texto al pasar el mouse. */
	public static ItemStack hit(MedievalEconomy economy, String name, List<String> lore) {
		ItemStack stack = item(Material.PAPER, name, lore);
		ItemMeta meta = stack.getItemMeta();
		if (meta != null) {
			meta.setCustomModelData(HIT);
			stack.setItemMeta(meta);
		}
		return stack;
	}

	public static ItemStack filler() {
		return item(Material.BLACK_STAINED_GLASS_PANE, "&r");
	}

	public static boolean usesBackgrounds(MedievalEconomy economy) {
		return usePack(economy) && economy.config().getBoolean("gui.usar-fondos", true);
	}

	private static String shift(int pixels) {
		// Caracteres de espacio del font: F801=-1, F802=-2, F804=-4, ... F880=-128
		StringBuilder sb = new StringBuilder();
		int[] steps = { 128, 64, 32, 16, 8, 4, 2, 1 };
		int remaining = pixels;
		for (int step : steps) {
			while (remaining >= step) {
				sb.append((char) (0xF800 + step));
				remaining -= step;
			}
		}
		return sb.toString();
	}

	/** Titulo del menu; con fondos activados dibuja la textura de fondo detras de los slots. */
	public static String title(MedievalEconomy economy, String text, char background) {
		boolean baked = usesBackgrounds(economy);
		// Sobre los fondos oscuros el texto negro no se ve: se usa dorado.
		String colored = MedievalEconomy.color(baked ? text.replace("&0", "&6&l") : text);
		if (!baked) return colored;
		// -8 para alinear con el borde, glifo de 256 px (+1 de avance), volver a x=8.
		return "\u00A7f" + shift(8) + background + shift(249) + colored;
	}

	private Gui() {
	}

	/** Titulo para menus cuyo fondo ya trae el titulo dibujado: solo el fondo (o el texto sin fondos). */
	public static String titleBaked(MedievalEconomy economy, String fallbackText, char background) {
		if (!usesBackgrounds(economy)) return MedievalEconomy.color(fallbackText);
		return "\u00A7f" + shift(8) + background + shift(249);
	}
}
