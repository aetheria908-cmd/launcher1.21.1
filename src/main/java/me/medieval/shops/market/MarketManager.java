package me.medieval.shops.market;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;

import com.nisovin.shopkeepers.api.shopkeeper.TradingRecipe;
import me.medieval.shops.currency.CurrencySet;
import me.medieval.shops.currency.Currency;
import me.medieval.shops.MedievalEconomy;
import me.medieval.shops.Sfx;

/**
 * Mercado dinamico estilo Albion: la demanda de cada objeto sube cuando se vende y baja cuando
 * los jugadores lo venden a las tiendas. El precio de las tiendas de admin sigue ese valor.
 * Los eventos de mercado aplican un multiplicador extra a ciertos objetos.
 */
public final class MarketManager {

	public static final class Sample {
		public final long minute;
		public final float mult;

		Sample(long minute, float mult) {
			this.minute = minute;
			this.mult = mult;
		}
	}

	public static final class ItemState {
		double demand = 0.0D;
		final ArrayList<Sample> history = new ArrayList<>();
	}

	public static final class MarketEvent {
		public final String id;
		public final String templateId;
		public final String name;
		public final double mult;
		public final long endMillis;
		public final List<String> patterns;
		public final String endMessage;
		private final List<Pattern> compiled = new ArrayList<>();

		MarketEvent(String id, String templateId, String name, double mult, long endMillis,
				List<String> patterns, String endMessage) {
			this.id = id;
			this.templateId = templateId;
			this.name = name;
			this.mult = mult;
			this.endMillis = endMillis;
			this.patterns = patterns;
			this.endMessage = endMessage;
			for (String p : patterns) {
				compiled.add(Pattern.compile(globToRegex(p), Pattern.CASE_INSENSITIVE));
			}
		}

		public boolean matches(String key) {
			for (Pattern p : compiled) {
				if (p.matcher(key).matches()) return true;
			}
			return false;
		}

		public long minutesLeft() {
			return Math.max(0L, (endMillis - System.currentTimeMillis()) / 60000L);
		}
	}

	private static String globToRegex(String glob) {
		StringBuilder sb = new StringBuilder();
		for (char c : glob.toCharArray()) {
			if (c == '*') sb.append(".*");
			else if (Character.isLetterOrDigit(c) || c == '_') sb.append(c);
			else sb.append(Pattern.quote(String.valueOf(c)));
		}
		return sb.toString();
	}

	private final MedievalEconomy economy;
	private final File file;
	private final Map<String, ItemState> items = new LinkedHashMap<>();
	private final Map<String, MarketEvent> events = new LinkedHashMap<>();
	private final Random random = new Random();
	private boolean dirty = false;
	private int minuteCounter = 0;
	private int eventCounter = 0;

	// Config
	private boolean enabled;
	private double demandPerUnit;
	private double sensitivity;
	private double minMult;
	private double maxMult;
	private double halfLifeMin;
	private int sampleMin;
	private int maxSamples;
	private int saveMin;
	private boolean randomEvents;
	private double eventChancePerHour;
	private int maxEvents;

	public MarketManager(MedievalEconomy economy) {
		this.economy = economy;
		this.file = new File(economy.getPlugin().getDataFolder(), "market-data.yml");
		reloadConfig();
	}

	public void reloadConfig() {
		FileConfiguration c = economy.config();
		enabled = c.getBoolean("mercado.habilitado", true);
		demandPerUnit = c.getDouble("mercado.demanda-por-unidad", 0.004D);
		sensitivity = c.getDouble("mercado.sensibilidad", 1.0D);
		minMult = c.getDouble("mercado.multiplicador-min", 0.5D);
		maxMult = c.getDouble("mercado.multiplicador-max", 2.5D);
		halfLifeMin = Math.max(1.0D, c.getDouble("mercado.recuperacion-minutos", 180.0D));
		sampleMin = Math.max(1, c.getInt("mercado.muestreo-minutos", 10));
		maxSamples = Math.max(10, c.getInt("mercado.historial-max-muestras", 1008));
		saveMin = Math.max(1, c.getInt("mercado.guardar-cada-minutos", 5));
		randomEvents = c.getBoolean("mercado.eventos.aleatorios", true);
		eventChancePerHour = c.getDouble("mercado.eventos.probabilidad-por-hora", 0.15D);
		maxEvents = Math.max(1, c.getInt("mercado.eventos.maximo-simultaneos", 2));
	}

	public boolean isEnabled() {
		return enabled;
	}

	public double getMinMult() {
		return minMult;
	}

	public double getMaxMult() {
		return maxMult;
	}

	public int getSampleMinutes() {
		return sampleMin;
	}

	// ---------------------------------------------------------------- Persistencia

	public void load() {
		items.clear();
		events.clear();
		if (!file.exists()) return;
		YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
		ConfigurationSection itemsSection = yaml.getConfigurationSection("items");
		if (itemsSection != null) {
			for (String key : itemsSection.getKeys(false)) {
				ConfigurationSection s = itemsSection.getConfigurationSection(key);
				if (s == null) continue;
				ItemState state = new ItemState();
				state.demand = s.getDouble("demand", 0.0D);
				String hist = s.getString("history", "");
				if (hist != null && !hist.isEmpty()) {
					for (String part : hist.split(",")) {
						int idx = part.indexOf(':');
						if (idx <= 0) continue;
						try {
							long minute = Long.parseLong(part.substring(0, idx));
							float mult = Float.parseFloat(part.substring(idx + 1));
							state.history.add(new Sample(minute, mult));
						} catch (NumberFormatException ignored) {
							// Muestra corrupta: se ignora.
						}
					}
				}
				items.put(key, state);
			}
		}
		ConfigurationSection eventsSection = yaml.getConfigurationSection("events");
		if (eventsSection != null) {
			for (String id : eventsSection.getKeys(false)) {
				ConfigurationSection s = eventsSection.getConfigurationSection(id);
				if (s == null) continue;
				long end = s.getLong("end", 0L);
				if (end <= System.currentTimeMillis()) continue;
				events.put(id, new MarketEvent(id, s.getString("template", "custom"),
						s.getString("name", id), s.getDouble("mult", 1.0D), end,
						s.getStringList("items"), s.getString("end-message", "")));
			}
		}
	}

	public void save() {
		YamlConfiguration yaml = new YamlConfiguration();
		for (Map.Entry<String, ItemState> entry : items.entrySet()) {
			String base = "items." + entry.getKey();
			yaml.set(base + ".demand", entry.getValue().demand);
			StringBuilder sb = new StringBuilder();
			for (Sample sample : entry.getValue().history) {
				if (sb.length() > 0) sb.append(',');
				sb.append(sample.minute).append(':')
						.append(String.format(Locale.ROOT, "%.4f", sample.mult));
			}
			yaml.set(base + ".history", sb.toString());
		}
		for (MarketEvent event : events.values()) {
			String base = "events." + event.id;
			yaml.set(base + ".template", event.templateId);
			yaml.set(base + ".name", event.name);
			yaml.set(base + ".mult", event.mult);
			yaml.set(base + ".end", event.endMillis);
			yaml.set(base + ".items", event.patterns);
			yaml.set(base + ".end-message", event.endMessage);
		}
		try {
			economy.getPlugin().getDataFolder().mkdirs();
			yaml.save(file);
			dirty = false;
		} catch (IOException e) {
			economy.getPlugin().getLogger()
					.warning("No se pudo guardar market-data.yml: " + e.getMessage());
		}
	}

	// ---------------------------------------------------------------- Precios

	public static String keyOf(ItemStack item) {
		return item.getType().name();
	}

	private ItemState state(String key) {
		ItemState state = items.get(key);
		if (state == null) {
			state = new ItemState();
			items.put(key, state);
			dirty = true;
		}
		return state;
	}

	private double demandMin() {
		return (minMult - 1.0D) / Math.max(0.0001D, sensitivity);
	}

	private double demandMax() {
		return (maxMult - 1.0D) / Math.max(0.0001D, sensitivity);
	}

	public double getDemand(String key) {
		ItemState state = items.get(key);
		return state == null ? 0.0D : state.demand;
	}

	public void setDemand(String key, double demand) {
		state(key).demand = Math.max(demandMin(), Math.min(demandMax(), demand));
		dirty = true;
	}

	public void addDemand(String key, double delta) {
		ItemState state = state(key);
		state.demand = Math.max(demandMin(), Math.min(demandMax(), state.demand + delta));
		dirty = true;
	}

	public double getEventMultiplier(String key) {
		double mult = 1.0D;
		for (MarketEvent event : events.values()) {
			if (event.matches(key)) mult *= event.mult;
		}
		return mult;
	}

	/** Multiplicador final (demanda y eventos). 1.0 = precio base. */
	public double getMultiplier(String key) {
		if (!enabled) return 1.0D;
		double demandMult = Math.max(minMult,
				Math.min(maxMult, 1.0D + getDemand(key) * sensitivity));
		double finalMult = demandMult * getEventMultiplier(key);
		return Math.max(minMult / 2.0D, Math.min(maxMult * 2.0D, finalMult));
	}

	public Set<String> keys() {
		return new TreeSet<>(items.keySet());
	}

	public boolean hasKey(String key) {
		return items.containsKey(key);
	}

	public List<Sample> history(String key) {
		ItemState state = items.get(key);
		if (state == null) return Collections.emptyList();
		return state.history;
	}

	public void resetItem(String key) {
		items.remove(key);
		dirty = true;
	}

	public void resetAll() {
		items.clear();
		dirty = true;
	}

	/** Registra un objeto en el mercado para que aparezca en los menus. */
	public void register(String key) {
		state(key);
	}

	/** Nivel de demanda de -2 (muy baja) a +2 (muy alta). */
	public int demandLevel(String key) {
		double d = getDemand(key);
		double limit = d >= 0 ? Math.max(0.0001D, demandMax()) : Math.max(0.0001D, -demandMin());
		double ratio = Math.abs(d) / limit;
		int level = ratio >= 0.6D ? 2 : ratio >= 0.2D ? 1 : 0;
		return d >= 0 ? level : -level;
	}

	/** Clasifica una compra: {objeto del mercado, true si la tienda COMPRA al jugador, unidades}. */
	public Object[] classify(TradingRecipe recipe, CurrencySet set) {
		ItemStack result = recipe.getResultItem().copy();
		ItemStack item1 = recipe.getItem1().copy();
		boolean pay = set.match(result) != null && set.match(item1) == null;
		return new Object[] { pay ? keyOf(item1) : keyOf(result), pay, pay ? item1.getAmount() : result.getAmount() };
	}

	/**
	 * Registra una compra/venta realizada en una tienda marcada. Toda oferta cuenta:
	 * si la tienda te vende algo, ese objeto sube; si la tienda te compra algo, baja.
	 * Lo que un mismo jugador puede mover el precio esta limitado (anti-abuso).
	 * 
	 * @return {tipo de objeto, multiplicador antes, multiplicador despues, 1 si fue compra del
	 *         jugador} o null
	 */
	public Object[] recordTrade(TradingRecipe recipe, CurrencySet set, java.util.UUID player) {
		if (!enabled) return null;
		Object[] info = classify(recipe, set);
		String key = (String) info[0];
		boolean pay = (Boolean) info[1];
		int amount = (Integer) info[2];
		double delta = (pay ? -1.0D : 1.0D) * demandPerUnit * amount;
		TradeLimiter limiter = economy.limiter();
		if (limiter != null && player != null) delta = limiter.allowImpact(player, key, delta);
		double before = getMultiplier(key);
		addDemand(key, delta);
		return new Object[] { key, before, getMultiplier(key), pay ? 0 : 1 };
	}

	// ---------------------------------------------------------------- Eventos

	public List<MarketEvent> activeEvents() {
		return new ArrayList<>(events.values());
	}

	public List<String> templateIds() {
		ConfigurationSection s = economy.config()
				.getConfigurationSection("mercado.eventos.plantillas");
		if (s == null) return Collections.emptyList();
		return new ArrayList<>(s.getKeys(false));
	}

	public MarketEvent startTemplate(String templateId, int minutesOverride) {
		ConfigurationSection s = economy.config()
				.getConfigurationSection("mercado.eventos.plantillas." + templateId);
		if (s == null) return null;
		int minutes = minutesOverride > 0 ? minutesOverride : s.getInt("duracion-minutos", 30);
		MarketEvent event = createEvent(templateId,
				MedievalEconomy.color(s.getString("nombre", templateId)),
				s.getDouble("multiplicador", 1.0D), minutes, s.getStringList("items"),
				MedievalEconomy.color(s.getString("mensaje-fin", "")));
		String start = s.getString("mensaje-inicio", "");
		if (start != null && !start.isEmpty()) {
			Bukkit.broadcastMessage(MedievalEconomy.color(start));
		}
		Sfx.playAll(Sfx.Type.EVENT_START);
		return event;
	}

	public MarketEvent startCustom(String name, double mult, int minutes, List<String> patterns) {
		MarketEvent event = createEvent("custom", MedievalEconomy.color(name), mult, minutes,
				patterns, "");
		Bukkit.broadcastMessage(MedievalEconomy.color("&6&l[Mercado] &eEvento: &f" + name
				+ " &7(x" + String.format(Locale.ROOT, "%.2f", mult) + ", " + minutes + " min)"));
		Sfx.playAll(Sfx.Type.EVENT_START);
		return event;
	}

	private MarketEvent createEvent(String templateId, String name, double mult, int minutes,
			List<String> patterns, String endMessage) {
		String id = templateId + "-" + (++eventCounter) + "-" + (System.currentTimeMillis() % 100000L);
		MarketEvent event = new MarketEvent(id, templateId, name, mult,
				System.currentTimeMillis() + minutes * 60000L,
				patterns.isEmpty() ? new ArrayList<>(List.of("*")) : new ArrayList<>(patterns),
				endMessage);
		events.put(id, event);
		dirty = true;
		return event;
	}

	public boolean stopEvent(String idOrTemplate) {
		boolean any = false;
		for (MarketEvent event : new ArrayList<>(events.values())) {
			if (idOrTemplate.equalsIgnoreCase("todos") || event.id.equalsIgnoreCase(idOrTemplate)
					|| event.templateId.equalsIgnoreCase(idOrTemplate)) {
				events.remove(event.id);
				if (event.endMessage != null && !event.endMessage.isEmpty()) {
					Bukkit.broadcastMessage(event.endMessage);
				}
				Sfx.playAll(Sfx.Type.EVENT_END);
				any = true;
			}
		}
		if (any) dirty = true;
		return any;
	}

	// ---------------------------------------------------------------- Tick

	public void tickMinute() {
		minuteCounter++;

		// Recuperacion: la demanda decae hacia 0.
		double factor = Math.pow(0.5D, 1.0D / halfLifeMin);
		for (ItemState state : items.values()) {
			if (Math.abs(state.demand) > 0.00001D) {
				state.demand *= factor;
				if (Math.abs(state.demand) < 0.00001D) state.demand = 0.0D;
				dirty = true;
			}
		}

		// Eventos expirados.
		long now = System.currentTimeMillis();
		for (MarketEvent event : new ArrayList<>(events.values())) {
			if (event.endMillis <= now) {
				events.remove(event.id);
				dirty = true;
				if (event.endMessage != null && !event.endMessage.isEmpty()) {
					Bukkit.broadcastMessage(event.endMessage);
				}
				Sfx.playAll(Sfx.Type.EVENT_END);
			}
		}

		// Historial.
		if (minuteCounter % sampleMin == 0) {
			long minute = now / 60000L;
			for (Map.Entry<String, ItemState> entry : items.entrySet()) {
				entry.getValue().history.add(
						new Sample(minute, (float) getMultiplier(entry.getKey())));
				while (entry.getValue().history.size() > maxSamples) {
					entry.getValue().history.remove(0);
				}
			}
			dirty = true;
		}

		// Eventos aleatorios.
		if (randomEvents && minuteCounter % 60 == 0 && events.size() < maxEvents
				&& random.nextDouble() < eventChancePerHour) {
			List<String> templates = templateIds();
			if (!templates.isEmpty()) {
				startTemplate(templates.get(random.nextInt(templates.size())), 0);
			}
		}

		if (dirty && minuteCounter % saveMin == 0) save();
	}
}
