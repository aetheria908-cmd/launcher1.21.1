package me.medieval.shops.gui;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.ItemStack;

import me.medieval.shops.MedievalEconomy;

/**
 * Menu de botones anchos dibujados en el fondo (resourcepack). Cada boton ocupa varios slots:
 * el primero lleva un item real (el icono) y el resto es invisible; los tres muestran el mismo
 * texto al pasar el mouse. Sin resourcepack, el icono queda en el primer slot y el resto se rellena.
 * Las posiciones vienen de {@link Layouts} (generado por el mismo script que dibuja el fondo).
 */
public abstract class ButtonMenu extends Menu {

	private static final class Btn {
		final Consumer<ClickType> action;

		Btn(Consumer<ClickType> action) {
			this.action = action;
		}
	}

	private final Layouts.Layout layout;
	private final Map<Integer, Btn> bySlot = new HashMap<>();

	protected ButtonMenu(MedievalEconomy economy, Player player, Layouts.Layout layout, String fallbackTitle) {
		super(economy, player);
		this.layout = layout;
		create(54, Gui.titleBaked(economy, fallbackTitle, layout.bg));
	}

	/** Coloca los botones con {@link #button}. */
	protected abstract void buildButtons();

	@Override
	protected final void render() {
		bySlot.clear();
		if (!Gui.usesBackgrounds(economy)) {
			for (int i = 0; i < 54; i++) inventory.setItem(i, Gui.filler());
		}
		buildButtons();
	}

	protected final void button(String id, Material icon, String name, List<String> lore, Consumer<ClickType> action) {
		button(id, new ItemStack(icon), name, lore, action);
	}

	protected final void button(String id, ItemStack icon, String name, List<String> lore, Consumer<ClickType> action) {
		Layouts.Spec spec = layout.find(id);
		if (spec == null) return;
		Btn btn = new Btn(action);
		boolean baked = Gui.usesBackgrounds(economy);
		for (int i = 0; i < spec.width; i++) {
			int slot = spec.row * 9 + spec.col + i;
			bySlot.put(slot, btn);
			ItemStack item;
			if (i == 0) item = Gui.itemLike(icon, name, lore.toArray(new String[0]));
			else item = baked ? Gui.hit(economy, name, lore) : Gui.filler();
			inventory.setItem(slot, item);
		}
	}

	@Override
	protected void onClick(int slot, ClickType click) {
		Btn btn = bySlot.get(slot);
		if (btn != null && btn.action != null) btn.action.accept(click);
	}
}
