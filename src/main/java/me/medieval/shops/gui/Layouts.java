package me.medieval.shops.gui;

/** GENERADO por resourcepack_src/gui_dark.py: posiciones de los botones dibujados en los fondos. */
public final class Layouts {

	public static final class Spec {
		public final String id;
		public final int row;
		public final int col;
		public final int width;

		Spec(String id, int row, int col, int width) {
			this.id = id;
			this.row = row;
			this.col = col;
			this.width = width;
		}
	}

	public static final class Layout {
		public final char bg;
		public final Spec[] buttons;

		Layout(char bg, Spec... buttons) {
			this.bg = bg;
			this.buttons = buttons;
		}

		public Spec find(String id) {
			for (Spec spec : buttons) {
				if (spec.id.equals(id)) return spec;
			}
			return null;
		}
	}

	public static final Layout HUB = new Layout('\uE00A',
				new Spec("forja", 1, 0, 3),
				new Spec("granja", 1, 3, 3),
				new Spec("alquimia", 1, 6, 3),
				new Spec("minas", 2, 0, 3),
				new Spec("construccion", 2, 3, 3),
				new Spec("bazar", 2, 6, 3),
				new Spec("todos", 3, 0, 3),
				new Spec("eventos", 3, 3, 3),
				new Spec("banco", 3, 6, 3),
				new Spec("ayuda", 4, 0, 4),
				new Spec("cerrar", 4, 5, 4));

	public static final Layout BANK = new Layout('\uE00B',
				new Spec("dep1", 1, 0, 3),
				new Spec("dep10", 1, 3, 3),
				new Spec("depall", 1, 6, 3),
				new Spec("ret1", 2, 0, 3),
				new Spec("ret10", 2, 3, 3),
				new Spec("retall", 2, 6, 3),
				new Spec("mercado", 5, 0, 4),
				new Spec("cerrar", 5, 5, 4));

	public static final Layout BANK_HIGH = new Layout('\uE00C',
				new Spec("dep1", 1, 0, 3),
				new Spec("dep10", 1, 3, 3),
				new Spec("depall", 1, 6, 3),
				new Spec("ret1", 2, 0, 3),
				new Spec("ret10", 2, 3, 3),
				new Spec("retall", 2, 6, 3),
				new Spec("hdep1", 3, 0, 3),
				new Spec("hdep10", 3, 3, 3),
				new Spec("hdepall", 3, 6, 3),
				new Spec("hret1", 4, 0, 3),
				new Spec("hret10", 4, 3, 3),
				new Spec("hretall", 4, 6, 3),
				new Spec("mercado", 5, 0, 4),
				new Spec("cerrar", 5, 5, 4));

	public static final Layout CONFIG = new Layout('\uE00D',
				new Spec("dinamicos", 1, 0, 3),
				new Spec("stock", 1, 3, 3),
				new Spec("banquero", 1, 6, 3),
				new Spec("moneda", 2, 0, 3),
				new Spec("alta", 2, 3, 3),
				new Spec("valor", 2, 6, 3),
				new Spec("reset", 3, 0, 3),
				new Spec("actualizar", 3, 3, 3),
				new Spec("abrirbanco", 3, 6, 3),
				new Spec("cerrar", 5, 3, 3));

	private Layouts() {
	}
}
