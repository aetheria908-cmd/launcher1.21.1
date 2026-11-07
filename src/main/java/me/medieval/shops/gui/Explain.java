package me.medieval.shops.gui;

import java.util.Locale;

/** Textos claros y cortos para explicar precios y demanda. */
public final class Explain {

	/** "MUY BARATO / BARATO / NORMAL / CARO / MUY CARO" segun el multiplicador. */
	public static String priceState(double mult) {
		if (mult >= 1.25D) return "&c&lMUY CARO";
		if (mult >= 1.05D) return "&cCARO";
		if (mult <= 0.75D) return "&a&lMUY BARATO";
		if (mult <= 0.95D) return "&aBARATO";
		return "&eNORMAL";
	}

	/** "+20%" / "-15%" respecto al precio normal. */
	public static String percent(double mult) {
		double pct = (mult - 1.0D) * 100.0D;
		return String.format(Locale.ROOT, "%s%.0f%%", pct >= 0 ? "+" : "", pct);
	}

	public static String priceLine(double mult) {
		return "&7Precio ahora: " + priceState(mult) + " &7(" + percent(mult) + " vs. el precio normal)";
	}

	/** Barra de demanda con explicacion. level de -2 a 2. */
	public static String demand(int level) {
		return switch (level) {
		case 2 -> "&c\u25AE\u25AE\u25AE\u25AE\u25AE &cMUY ALTA &7(todos lo compran)";
		case 1 -> "&6\u25AE\u25AE\u25AE\u25AE&8\u25AF &6ALTA &7(se compra bastante)";
		case -1 -> "&b\u25AE\u25AE&8\u25AF\u25AF\u25AF &bBAJA &7(se compra poco)";
		case -2 -> "&9\u25AE&8\u25AF\u25AF\u25AF\u25AF &9MUY BAJA &7(casi nadie lo compra)";
		default -> "&e\u25AE\u25AE\u25AE&8\u25AF\u25AF &eNORMAL";
		};
	}

	/** Palabra corta para scoreboards y placeholders. */
	public static String demandWord(int level) {
		return switch (level) {
		case 2 -> "MUY ALTA";
		case 1 -> "ALTA";
		case -1 -> "BAJA";
		case -2 -> "MUY BAJA";
		default -> "NORMAL";
		};
	}

	public static String demandLine(int level) {
		return "&7Demanda: " + demand(level);
	}

	/** Tendencia de la ultima hora. */
	public static String trend(double delta) {
		if (delta > 0.01D) return "&c\u25B2 subiendo";
		if (delta < -0.01D) return "&a\u25BC bajando";
		return "&e\u25AC estable";
	}

	/** Explicacion del sistema, en palabras simples. */
	public static String[] help() {
		return new String[] {
				"&fLos precios cambian solos segun lo que hace la gente:",
				"",
				"&c\u25B2 Si COMPRAN mucho un objeto &7-> su precio SUBE.",
				"&a\u25BC Si VENDEN mucho un objeto a una tienda &7-> su precio BAJA.",
				"&e\u25AC Con el tiempo vuelve solo al precio normal.",
				"",
				"&aVerde &7= mas barato de lo normal (buen momento para comprar).",
				"&cRojo &7= mas caro de lo normal (buen momento para vender).",
				"",
				"&6Eventos &7(feria, guerra, hambruna...) cambian el precio",
				"&7de ciertos objetos durante un rato.",
				"&7Solo cambian los mercaderes marcados por un admin."
		};
	}

	private Explain() {
	}
}
