package me.medieval.shops.holo;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.TextDisplay;

import com.nisovin.shopkeepers.api.ShopkeepersAPI;
import com.nisovin.shopkeepers.api.shopkeeper.Shopkeeper;
import com.nisovin.shopkeepers.api.shopobjects.ShopObject;
import com.nisovin.shopkeepers.api.shopobjects.entity.EntityShopObject;

import me.medieval.shops.MedievalEconomy;

/**
 * Holograma (TextDisplay) sobre cada mercader con el mercado activo: nombre del objeto, precio
 * y cuanto cambio ("Pan 12 +20%"). Si hay muchas ofertas, rota por paginas. De noche, si el
 * mercader duerme, muestra "Zzz". Las entidades no se guardan en el mundo (se recrean solas).
 */
public final class HologramManager {

	private final MedievalEconomy economy;
	private final Map<UUID, TextDisplay> displays = new HashMap<>();
	private final Map<UUID, String> lastText = new HashMap<>();
	private int calls = 0;

	private boolean enabled;
	private int perPage;
	private int rotateSeconds;
	private double extraHeight;
	private float viewRange;

	public HologramManager(MedievalEconomy economy) {
		this.economy = economy;
		reloadConfig();
	}

	public void reloadConfig() {
		FileConfiguration c = economy.config();
		enabled = c.getBoolean("holograma.habilitado", true);
		perPage = Math.max(1, c.getInt("holograma.ofertas-por-pagina", 3));
		rotateSeconds = Math.max(2, c.getInt("holograma.rotar-segundos", 5));
		extraHeight = c.getDouble("holograma.altura-extra", 0.35D);
		viewRange = (float) Math.max(0.05D, c.getDouble("holograma.distancia-vision", 0.35D));
		if (!enabled) removeAll();
	}

	private static Entity entityOf(Shopkeeper shopkeeper) {
		ShopObject object = shopkeeper.getShopObject();
		if (object instanceof EntityShopObject entityObject) return entityObject.getEntity();
		return null;
	}

	/** Llamado cada 2 segundos. */
	public void update() {
		if (!enabled) return;
		calls++;
		int rotation = (calls * 2) / rotateSeconds;

		// Hologramas de mercaderes que ya no estan activos en el mercado
		for (UUID id : new ArrayList<>(displays.keySet())) {
			if (!economy.pricing().isMarked(id)) remove(id);
		}

		for (UUID id : new ArrayList<>(economy.pricing().markedIds())) {
			try {
				updateOne(id, rotation);
			} catch (Exception e) {
				remove(id);
			}
		}
	}

	private void updateOne(UUID id, int rotation) {
		Shopkeeper shopkeeper = ShopkeepersAPI.getShopkeeperRegistry().getShopkeeperByUniqueId(id);
		if (shopkeeper == null || !shopkeeper.getShopObject().isActive()) {
			remove(id);
			return;
		}
		Entity entity = entityOf(shopkeeper);
		Location at;
		if (entity != null && entity.isValid()) {
			at = entity.getLocation().add(0.0D, entity.getHeight() + extraHeight, 0.0D);
		} else {
			Location base = shopkeeper.getShopObject().getLocation();
			if (base == null || base.getWorld() == null) {
				remove(id);
				return;
			}
			at = base.clone().add(0.5D, 2.2D + extraHeight, 0.5D);
		}
		World world = at.getWorld();
		if (world == null) {
			remove(id);
			return;
		}

		StringBuilder text = new StringBuilder();
		String name = shopkeeper.getName();
		if (economy.routes().isClosedForSleep(id)) {
			text.append("&9&lZzz...\n&7Durmiendo hasta las &e").append(economy.routes().wakeTimeText());
		} else {
			List<String> lines = economy.pricing().hologramLines(id, perPage, rotation);
			if (lines.isEmpty()) {
				remove(id);
				return;
			}
			String cleanName = me.medieval.shops.market.ShopPricing.clean(name);
			text.append(cleanName.isEmpty() ? "&6&lMercado" : "&6&l" + cleanName);
			for (String line : lines) text.append("\n").append(line);
		}
		String colored = MedievalEconomy.color(text.toString());

		TextDisplay display = displays.get(id);
		if (display == null || !display.isValid()) {
			display = world.spawn(at, TextDisplay.class, d -> {
				d.setBillboard(Display.Billboard.CENTER);
				d.setAlignment(TextDisplay.TextAlignment.CENTER);
				d.setSeeThrough(false);
				d.setShadowed(true);
				d.setDefaultBackground(false);
				d.setBackgroundColor(Color.fromARGB(110, 0, 0, 0));
				d.setLineWidth(400);
				d.setViewRange(viewRange);
				d.setGravity(false);
				d.setPersistent(false);
			});
			displays.put(id, display);
			lastText.remove(id);
		} else if (display.getLocation().distanceSquared(at) > 0.04D) {
			display.teleport(at);
		}
		if (!colored.equals(lastText.get(id))) {
			display.setText(colored);
			lastText.put(id, colored);
		}
	}

	private void remove(UUID id) {
		TextDisplay display = displays.remove(id);
		lastText.remove(id);
		if (display != null && display.isValid()) display.remove();
	}

	public void removeAll() {
		for (UUID id : new ArrayList<>(displays.keySet())) remove(id);
	}
}
