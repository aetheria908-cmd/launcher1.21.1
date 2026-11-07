package me.medieval.shops.market;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import me.medieval.shops.MedievalEconomy;

/**
 * Anti-abuso del mercado:
 * - limite diario por jugador y objeto (compras y ventas por separado),
 * - tope de cuanto puede mover UN jugador el precio de un objeto por hora (evita inflar o hundir
 *   el precio para luego aprovecharse),
 * - enfriamiento opcional entre compras.
 */
public final class TradeLimiter {

	private static final long HOUR_MS = 3_600_000L;

	private final MedievalEconomy economy;
	private final File file;
	// jugador -> ("OBJETO|B" o "OBJETO|S" -> {dia, cantidad})
	private final Map<UUID, Map<String, long[]>> daily = new HashMap<>();
	// jugador -> (OBJETO -> {inicio de la ventana en ms, demanda acumulada x1e6})
	private final Map<UUID, Map<String, long[]>> impact = new HashMap<>();
	private final Map<UUID, Long> lastTrade = new HashMap<>();
	private boolean dirty = false;
	private int minuteCounter = 0;

	private boolean enabled;
	private int dailyLimit;
	private double impactPerHour;
	private long cooldownMs;

	public TradeLimiter(MedievalEconomy economy) {
		this.economy = economy;
		this.file = new File(economy.getPlugin().getDataFolder(), "limits-data.yml");
		reloadConfig();
	}

	public void reloadConfig() {
		FileConfiguration c = economy.config();
		enabled = c.getBoolean("antiabuso.habilitado", true);
		dailyLimit = Math.max(0, c.getInt("antiabuso.limite-diario-por-objeto", 1024));
		impactPerHour = Math.max(0.0D, c.getDouble("antiabuso.impacto-max-por-jugador-hora", 0.25D));
		cooldownMs = Math.max(0L, c.getLong("antiabuso.enfriamiento-ms", 0L));
	}

	private static long today() {
		return System.currentTimeMillis() / 86_400_000L;
	}

	private static String dailyKey(String item, boolean playerBuys) {
		return item + (playerBuys ? "|B" : "|S");
	}

	/** @return un mensaje si la compra/venta debe cancelarse, o null si puede seguir */
	public String check(UUID player, String item, boolean playerBuys, int amount) {
		if (!enabled) return null;
		long now = System.currentTimeMillis();
		if (cooldownMs > 0) {
			Long last = lastTrade.get(player);
			if (last != null && now - last < cooldownMs) return "&cMas despacio, espera un momento.";
		}
		if (dailyLimit > 0) {
			long[] entry = daily.getOrDefault(player, Map.of()).get(dailyKey(item, playerBuys));
			long used = (entry == null || entry[0] != today()) ? 0 : entry[1];
			if (used + amount > dailyLimit) {
				return "&cLimite diario alcanzado para &f" + MedievalEconomy.pretty(item) + " &c(" + used + "/" + dailyLimit
						+ " unidades " + (playerBuys ? "compradas" : "vendidas") + " hoy). Vuelve manana.";
			}
		}
		return null;
	}

	public void record(UUID player, String item, boolean playerBuys, int amount) {
		if (!enabled) return;
		long day = today();
		long[] entry = daily.computeIfAbsent(player, k -> new HashMap<>())
				.computeIfAbsent(dailyKey(item, playerBuys), k -> new long[] { day, 0L });
		if (entry[0] != day) {
			entry[0] = day;
			entry[1] = 0L;
		}
		entry[1] += amount;
		lastTrade.put(player, System.currentTimeMillis());
		dirty = true;
	}

	/**
	 * Cuanta de la variacion de demanda pedida cuenta de verdad para el precio. Un jugador no puede
	 * mover un objeto mas de {@code impacto-max-por-jugador-hora} por hora.
	 */
	public double allowImpact(UUID player, String item, double delta) {
		if (!enabled || impactPerHour <= 0.0D) return delta;
		long now = System.currentTimeMillis();
		long[] window = impact.computeIfAbsent(player, k -> new HashMap<>())
				.computeIfAbsent(item, k -> new long[] { now, 0L });
		if (now - window[0] > HOUR_MS) {
			window[0] = now;
			window[1] = 0L;
		}
		double used = window[1] / 1_000_000.0D;
		double room = Math.max(0.0D, impactPerHour - used);
		double allowed = Math.min(Math.abs(delta), room);
		window[1] += Math.round(allowed * 1_000_000.0D);
		dirty = true;
		return Math.signum(delta) * allowed;
	}

	// ---------------------------------------------------------------- Persistencia

	public void load() {
		daily.clear();
		impact.clear();
		if (!file.exists()) return;
		YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
		long day = today();
		long now = System.currentTimeMillis();
		for (String s : yaml.getStringList("diario")) {
			String[] p = s.split(";");
			if (p.length < 5) continue;
			try {
				if (Long.parseLong(p[3]) != day) continue;
				daily.computeIfAbsent(UUID.fromString(p[0]), k -> new HashMap<>())
						.put(dailyKey(p[1], p[2].equals("B")), new long[] { day, Long.parseLong(p[4]) });
			} catch (IllegalArgumentException ignored) {
				// Linea invalida.
			}
		}
		for (String s : yaml.getStringList("impacto")) {
			String[] p = s.split(";");
			if (p.length < 4) continue;
			try {
				long start = Long.parseLong(p[2]);
				if (now - start > HOUR_MS) continue;
				impact.computeIfAbsent(UUID.fromString(p[0]), k -> new HashMap<>())
						.put(p[1], new long[] { start, Long.parseLong(p[3]) });
			} catch (IllegalArgumentException ignored) {
				// Linea invalida.
			}
		}
	}

	public void save() {
		YamlConfiguration yaml = new YamlConfiguration();
		long day = today();
		long now = System.currentTimeMillis();
		List<String> dailyList = new ArrayList<>();
		for (Map.Entry<UUID, Map<String, long[]>> player : daily.entrySet()) {
			for (Map.Entry<String, long[]> e : player.getValue().entrySet()) {
				if (e.getValue()[0] != day) continue;
				int sep = e.getKey().lastIndexOf('|');
				dailyList.add(player.getKey() + ";" + e.getKey().substring(0, sep) + ";" + e.getKey().substring(sep + 1)
						+ ";" + day + ";" + e.getValue()[1]);
			}
		}
		List<String> impactList = new ArrayList<>();
		for (Map.Entry<UUID, Map<String, long[]>> player : impact.entrySet()) {
			for (Map.Entry<String, long[]> e : player.getValue().entrySet()) {
				if (now - e.getValue()[0] > HOUR_MS) continue;
				impactList.add(player.getKey() + ";" + e.getKey() + ";" + e.getValue()[0] + ";" + e.getValue()[1]);
			}
		}
		yaml.set("diario", dailyList);
		yaml.set("impacto", impactList);
		try {
			economy.getPlugin().getDataFolder().mkdirs();
			yaml.save(file);
			dirty = false;
		} catch (IOException e) {
			economy.getPlugin().getLogger().warning("No se pudo guardar limits-data.yml: " + e.getMessage());
		}
	}

	/** Cada minuto: limpia lo vencido y guarda cada 5 minutos si hubo cambios. */
	public void tickMinute() {
		minuteCounter++;
		if (minuteCounter % 5 != 0) return;
		long day = today();
		long now = System.currentTimeMillis();
		daily.values().forEach(map -> map.values().removeIf(v -> v[0] != day));
		daily.values().removeIf(Map::isEmpty);
		impact.values().forEach(map -> map.values().removeIf(v -> now - v[0] > HOUR_MS));
		impact.values().removeIf(Map::isEmpty);
		lastTrade.values().removeIf(t -> now - t > HOUR_MS);
		if (dirty) save();
	}
}
