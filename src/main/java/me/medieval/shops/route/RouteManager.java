package me.medieval.shops.route;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Villager;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import com.nisovin.shopkeepers.api.ShopkeepersAPI;
import com.nisovin.shopkeepers.api.shopkeeper.Shopkeeper;
import com.nisovin.shopkeepers.api.shopobjects.ShopObject;
import com.nisovin.shopkeepers.api.shopobjects.entity.EntityShopObject;
import me.medieval.shops.MedievalEconomy;

/**
 * Rutas diarias de mercaderes: cada parada tiene una hora (reloj de Minecraft). Cuando llega la
 * hora, el mercader camina hasta la parada y se queda ahi (invulnerable, como cualquier
 * mercader del plugin).
 */
public final class RouteManager {

	public static final class Stop {
		public final int tick;
		public final String world;
		public final int x;
		public final int y;
		public final int z;
		public final float yaw;

		public Stop(int tick, String world, int x, int y, int z, float yaw) {
			this.tick = tick;
			this.world = world;
			this.x = x;
			this.y = y;
			this.z = z;
			this.yaw = yaw;
		}

		String serialize() {
			return tick + ";" + world + ";" + x + ";" + y + ";" + z + ";" + yaw;
		}

		static Stop parse(String s) {
			String[] p = s.split(";");
			if (p.length < 6) return null;
			try {
				return new Stop(Integer.parseInt(p[0]), p[1], Integer.parseInt(p[2]),
						Integer.parseInt(p[3]), Integer.parseInt(p[4]), Float.parseFloat(p[5]));
			} catch (NumberFormatException e) {
				return null;
			}
		}
	}

	private static final class Walker {
		final List<int[]> path;
		final int targetIndex; // indice de parada, o BED / HOME
		final Stop target;
		int step = 0;
		int[] last;

		Walker(List<int[]> path, int targetIndex, Stop target, int[] start) {
			this.path = path;
			this.targetIndex = targetIndex;
			this.target = target;
			this.last = start;
		}
	}

	private final MedievalEconomy economy;
	private final File file;
	private final Map<UUID, List<Stop>> routes = new LinkedHashMap<>();
	/** Destino especial: la cama del mercader. */
	public static final int BED = -1;
	/** Destino especial: su lugar de siempre (cuando no tiene ruta). */
	public static final int HOME = -2;

	private final Map<UUID, Stop> beds = new HashMap<>();
	private final Map<UUID, Stop> homes = new HashMap<>();
	private final Set<UUID> sleeping = new HashSet<>();
	private final Map<UUID, Integer> current = new HashMap<>();
	private final Map<UUID, Walker> walkers = new HashMap<>();
	private boolean dirty = false;

	private boolean enabled;
	private double speed;
	private int maxWalkDistance;
	private int maxNodes;
	private boolean sleepEnabled;
	private int sleepFrom;
	private int sleepTo;

	public RouteManager(MedievalEconomy economy) {
		this.economy = economy;
		this.file = new File(economy.getPlugin().getDataFolder(), "routes-data.yml");
		reloadConfig();
	}

	public void reloadConfig() {
		FileConfiguration c = economy.config();
		enabled = c.getBoolean("rutas.habilitado", true);
		speed = Math.max(0.5D, Math.min(10.0D, c.getDouble("rutas.velocidad-bloques-por-segundo", 2.5D)));
		maxWalkDistance = Math.max(4, c.getInt("rutas.distancia-maxima-caminando", 80));
		maxNodes = Math.max(200, c.getInt("rutas.nodos-maximos", 5000));
		sleepEnabled = c.getBoolean("dormir.habilitado", true);
		int from = parseTime(c.getString("dormir.desde", "19:00"));
		int to = parseTime(c.getString("dormir.hasta", "06:00"));
		sleepFrom = from < 0 ? 13000 : from;
		sleepTo = to < 0 ? 0 : to;
	}

	public long getStepTicks() {
		return Math.max(1L, Math.round(20.0D / speed));
	}

	// ---------------------------------------------------------------- Tiempo

	/** "HH:mm" del reloj de Minecraft (06:00 = amanecer, tick 0) a ticks del dia. */
	public static int parseTime(String text) {
		String[] p = text.split(":");
		if (p.length != 2) return -1;
		try {
			int h = Integer.parseInt(p[0]);
			int m = Integer.parseInt(p[1]);
			if (h < 0 || h > 23 || m < 0 || m > 59) return -1;
			int minutes = ((h * 60 + m) - 360 + 1440) % 1440;
			return (int) (minutes * 1000L / 60L);
		} catch (NumberFormatException e) {
			return -1;
		}
	}

	public static String formatTime(int tick) {
		int minutes = (int) ((tick * 60L / 1000L + 360L) % 1440L);
		return String.format("%02d:%02d", minutes / 60, minutes % 60);
	}

	// ---------------------------------------------------------------- Datos

	public List<Stop> getStops(UUID id) {
		return routes.getOrDefault(id, new ArrayList<>());
	}

	public Map<UUID, List<Stop>> all() {
		return routes;
	}

	public void addStop(UUID id, Stop stop) {
		List<Stop> list = routes.computeIfAbsent(id, k -> new ArrayList<>());
		list.removeIf(s -> s.tick == stop.tick);
		list.add(stop);
		list.sort((a, b) -> Integer.compare(a.tick, b.tick));
		current.remove(id); // Reevaluar la parada actual
		dirty = true;
	}

	public boolean removeStop(UUID id, int index) {
		List<Stop> list = routes.get(id);
		if (list == null || index < 0 || index >= list.size()) return false;
		list.remove(index);
		if (list.isEmpty()) routes.remove(id);
		current.remove(id);
		walkers.remove(id);
		dirty = true;
		return true;
	}

	public void clearRoute(UUID id) {
		routes.remove(id);
		current.remove(id);
		walkers.remove(id);
		dirty = true;
	}

	public void load() {
		routes.clear();
		current.clear();
		beds.clear();
		homes.clear();
		if (!file.exists()) return;
		YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
		loadStops(yaml.getConfigurationSection("camas"), beds);
		loadStops(yaml.getConfigurationSection("casas"), homes);
		ConfigurationSection state = yaml.getConfigurationSection("estado");
		if (state != null) {
			for (String key : state.getKeys(false)) {
				try {
					current.put(UUID.fromString(key), state.getInt(key));
				} catch (IllegalArgumentException ignored) {
					// Clave invalida.
				}
			}
		}
		ConfigurationSection section = yaml.getConfigurationSection("rutas");
		if (section == null) return;
		for (String key : section.getKeys(false)) {
			try {
				UUID id = UUID.fromString(key);
				List<Stop> list = new ArrayList<>();
				for (String s : section.getStringList(key + ".paradas")) {
					Stop stop = Stop.parse(s);
					if (stop != null) list.add(stop);
				}
				if (!list.isEmpty()) {
					list.sort((a, b) -> Integer.compare(a.tick, b.tick));
					routes.put(id, list);
				}
				if (section.contains(key + ".actual")) {
					current.put(id, section.getInt(key + ".actual"));
				}
			} catch (IllegalArgumentException ignored) {
				// Clave invalida.
			}
		}
	}

	private static void loadStops(ConfigurationSection section, Map<UUID, Stop> into) {
		if (section == null) return;
		for (String key : section.getKeys(false)) {
			try {
				Stop stop = Stop.parse(section.getString(key, ""));
				if (stop != null) into.put(UUID.fromString(key), stop);
			} catch (IllegalArgumentException ignored) {
				// Clave invalida.
			}
		}
	}

	public void save() {
		YamlConfiguration yaml = new YamlConfiguration();
		for (Map.Entry<UUID, Stop> e : beds.entrySet()) yaml.set("camas." + e.getKey(), e.getValue().serialize());
		for (Map.Entry<UUID, Stop> e : homes.entrySet()) yaml.set("casas." + e.getKey(), e.getValue().serialize());
		for (UUID id : current.keySet()) {
			if (!routes.containsKey(id) && current.get(id) != null) yaml.set("estado." + id, current.get(id));
		}
		for (Map.Entry<UUID, List<Stop>> entry : routes.entrySet()) {
			List<String> list = new ArrayList<>();
			for (Stop stop : entry.getValue()) list.add(stop.serialize());
			yaml.set("rutas." + entry.getKey() + ".paradas", list);
			Integer cur = current.get(entry.getKey());
			if (cur != null) yaml.set("rutas." + entry.getKey() + ".actual", cur);
		}
		try {
			economy.getPlugin().getDataFolder().mkdirs();
			yaml.save(file);
			dirty = false;
		} catch (IOException e) {
			economy.getPlugin().getLogger()
					.warning("No se pudo guardar routes-data.yml: " + e.getMessage());
		}
	}

	// ---------------------------------------------------------------- Horario

	private static boolean active(Shopkeeper shopkeeper) {
		return shopkeeper.getShopObject().isActive();
	}

	private Shopkeeper shopkeeper(UUID id) {
		return ShopkeepersAPI.getShopkeeperRegistry().getShopkeeperByUniqueId(id);
	}

	// ---------------------------------------------------------------- Dormir

	/** true si en ese mundo es de noche segun la ventana configurada (dormir.desde - dormir.hasta). */
	public boolean isNight(World world) {
		long t = world.getTime();
		if (sleepFrom <= sleepTo) return t >= sleepFrom && t < sleepTo;
		return t >= sleepFrom || t < sleepTo;
	}

	public String wakeTimeText() {
		return formatTime(sleepTo);
	}

	public void setBed(UUID id, Stop stop) {
		beds.put(id, stop);
		current.remove(id);
		dirty = true;
	}

	public boolean clearBed(UUID id) {
		boolean had = beds.remove(id) != null;
		if (had) {
			sleeping.remove(id);
			current.remove(id);
			dirty = true;
		}
		return had;
	}

	public Stop getBed(UUID id) {
		return beds.get(id);
	}

	/** El mercader tiene cama y ahora es de noche: no atiende. */
	public boolean isClosedForSleep(UUID id) {
		if (!sleepEnabled) return false;
		Stop bed = beds.get(id);
		if (bed == null) return false;
		World world = Bukkit.getWorld(bed.world);
		return world != null && isNight(world);
	}

	public boolean isSleeping(UUID id) {
		return sleeping.contains(id);
	}

	private Entity entityOf(Shopkeeper shopkeeper) {
		ShopObject object = shopkeeper.getShopObject();
		if (object instanceof EntityShopObject entityObject) return entityObject.getEntity();
		return null;
	}

	/** Acuesta (o levanta) al aldeano. Solo se acuesta de verdad si hay una cama en el sitio. */
	private void setSleepPose(Shopkeeper shopkeeper, Stop bed, boolean sleep) {
		try {
			Entity entity = entityOf(shopkeeper);
			if (!(entity instanceof Villager villager) || !villager.isValid()) return;
			if (!sleep) {
				if (villager.isSleeping()) villager.wakeup();
				return;
			}
			World world = Bukkit.getWorld(bed.world);
			if (world == null) return;
			Block block = world.getBlockAt(bed.x, bed.y, bed.z);
			if (!Tag.BEDS.isTagged(block.getType())) return;
			if (!villager.isSleeping()) villager.sleep(block.getLocation());
		} catch (Exception | LinkageError ignored) {
			// Mejor esfuerzo: si el servidor no deja acostarlo, simplemente queda parado.
		}
	}

	/** Llamado cada segundo: decide a donde debe estar cada mercader segun la hora (parada, cama o casa). */
	public void checkSchedules() {
		if (!enabled || !ShopMover.isAvailable()) return;
		Set<UUID> ids = new LinkedHashSet<>(routes.keySet());
		ids.addAll(beds.keySet());
		for (UUID id : ids) {
			if (walkers.containsKey(id)) continue;
			Shopkeeper shopkeeper = shopkeeper(id);
			if (shopkeeper == null) continue;
			Stop bed = beds.get(id);
			List<Stop> stops = routes.get(id);
			boolean hasRoute = stops != null && !stops.isEmpty();
			String worldName = bed != null ? bed.world : hasRoute ? stops.get(0).world : null;
			World clockWorld = worldName == null ? null : Bukkit.getWorld(worldName);
			if (clockWorld == null) continue;

			int desired;
			Stop target;
			if (sleepEnabled && bed != null && isNight(clockWorld)) {
				desired = BED;
				target = bed;
			} else if (hasRoute) {
				long time = clockWorld.getTime();
				desired = stops.size() - 1; // Antes de la primera parada: sigue en la ultima de ayer
				for (int i = 0; i < stops.size(); i++) {
					if (stops.get(i).tick <= time) desired = i;
				}
				target = stops.get(desired);
			} else if (homes.containsKey(id)) {
				desired = HOME;
				target = homes.get(id);
			} else {
				continue;
			}

			Integer cur = current.get(id);
			if (cur != null && cur == desired) {
				if (desired == BED && active(shopkeeper)) {
					sleeping.add(id);
					setSleepPose(shopkeeper, bed, true); // Si el aldeano reaparecio, se vuelve a acostar.
				}
				continue;
			}
			if (desired == BED && !hasRoute && !homes.containsKey(id)) {
				// Sin ruta: recuerda donde estaba para volver por la manana.
				Location now = shopkeeper.getLocation();
				if (now != null && now.getWorld() != null) {
					homes.put(id, new Stop(0, now.getWorld().getName(), now.getBlockX(), now.getBlockY(),
							now.getBlockZ(), now.getYaw()));
					dirty = true;
				}
			}
			startMove(id, shopkeeper, target, desired);
		}
		if (dirty) save();
	}

	/** Fuerza que un mercader vaya a la parada indicada ahora mismo. */
	public boolean forceGo(UUID id, int index) {
		List<Stop> stops = routes.get(id);
		Shopkeeper shopkeeper = shopkeeper(id);
		if (stops == null || shopkeeper == null || index < 0 || index >= stops.size()) return false;
		walkers.remove(id);
		startMove(id, shopkeeper, stops.get(index), index);
		return true;
	}

	private void startMove(UUID id, Shopkeeper shopkeeper, Stop stop, int index) {
		World world = Bukkit.getWorld(stop.world);
		if (world == null) return;
		if (sleeping.remove(id)) {
			Stop bed = beds.get(id);
			setSleepPose(shopkeeper, bed != null ? bed : stop, false); // Se levanta antes de caminar.
		}
		Location now = shopkeeper.getLocation();
		boolean sameWorld = now != null && now.getWorld() != null && now.getWorld().equals(world);
		if (sameWorld && Math.abs(now.getBlockX() - stop.x) <= 0 && Math.abs(now.getBlockZ() - stop.z) <= 0
				&& Math.abs(now.getBlockY() - stop.y) <= 1) {
			finishArrival(id, shopkeeper, stop, index);
			return;
		}
		double distance = sameWorld ? Math.sqrt(Math.pow(now.getBlockX() - stop.x, 2)
				+ Math.pow(now.getBlockZ() - stop.z, 2)) : Double.MAX_VALUE;
		if (!sameWorld || !active(shopkeeper) || distance > maxWalkDistance) {
			arrive(id, shopkeeper, stop, index);
			return;
		}
		List<int[]> path = new PathFinder(world).find(now.getBlockX(), now.getBlockY(), now.getBlockZ(),
				stop.x, stop.y, stop.z, maxNodes, maxWalkDistance + 10);
		if (path == null) {
			economy.getPlugin().getLogger().info("Ruta: sin camino para el mercader #" + shopkeeper.getId()
					+ ", se teletransporta al destino.");
			arrive(id, shopkeeper, stop, index);
			return;
		}
		walkers.put(id, new Walker(path, index, stop,
				new int[] { now.getBlockX(), now.getBlockY(), now.getBlockZ() }));
	}

	private void arrive(UUID id, Shopkeeper shopkeeper, Stop stop, int index) {
		World world = Bukkit.getWorld(stop.world);
		if (world != null) {
			ShopMover.teleport(shopkeeper, new Location(world, stop.x, stop.y, stop.z, stop.yaw, 0.0F));
		}
		walkers.remove(id);
		finishArrival(id, shopkeeper, stop, index);
	}

	private void finishArrival(UUID id, Shopkeeper shopkeeper, Stop stop, int index) {
		current.put(id, index);
		dirty = true;
		if (index == BED) {
			sleeping.add(id);
			setSleepPose(shopkeeper, stop, true);
		}
	}

	/** Mueve a los caminantes un bloque. */
	public void tickWalkers() {
		if (walkers.isEmpty()) return;
		Iterator<Map.Entry<UUID, Walker>> it = walkers.entrySet().iterator();
		List<Runnable> afterwards = new ArrayList<>();
		while (it.hasNext()) {
			Map.Entry<UUID, Walker> entry = it.next();
			UUID id = entry.getKey();
			Walker walker = entry.getValue();
			Shopkeeper shopkeeper = shopkeeper(id);
			if (shopkeeper == null) {
				it.remove();
				continue;
			}
			Stop stop = walker.target;
			if (!active(shopkeeper) || walker.step >= walker.path.size()) {
				it.remove();
				afterwards.add(() -> arrive(id, shopkeeper, stop, walker.targetIndex));
				continue;
			}
			World world = Bukkit.getWorld(stop.world);
			if (world == null) {
				it.remove();
				continue;
			}
			int[] next = walker.path.get(walker.step++);
			double dx = next[0] - walker.last[0];
			double dz = next[2] - walker.last[2];
			float yaw = (dx == 0 && dz == 0) ? stop.yaw
					: (float) Math.toDegrees(Math.atan2(-dx, dz));
			walker.last = next;
			if (!ShopMover.teleport(shopkeeper, new Location(world, next[0], next[1], next[2], yaw, 0.0F))) {
				it.remove();
				continue;
			}
		}
		// Las llegadas modifican el mapa de caminantes, asi que se hacen fuera del iterador.
		afterwards.forEach(Runnable::run);
	}

	public boolean isWalking(UUID id) {
		return walkers.containsKey(id);
	}

	public Integer getCurrent(UUID id) {
		return current.get(id);
	}
}
