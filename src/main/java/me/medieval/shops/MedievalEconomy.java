package me.medieval.shops;

import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.logging.Level;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.PluginCommand;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import me.medieval.shops.bank.BankCommand;
import me.medieval.shops.bank.BankManager;
import me.medieval.shops.gui.MenuListener;
import me.medieval.shops.market.MarketCommand;
import me.medieval.shops.currency.Currencies;
import me.medieval.shops.holo.HologramManager;
import me.medieval.shops.market.MarketManager;
import me.medieval.shops.market.ShopPricing;
import me.medieval.shops.route.RouteCommand;
import me.medieval.shops.route.RouteManager;

/**
 * Punto de entrada de las funciones medievales: mercado (oferta y demanda), banco y rutas.
 */
public final class MedievalEconomy {

	private static MedievalEconomy instance = null;

	public static MedievalEconomy get() {
		return instance;
	}

	public static boolean isEnabled() {
		return instance != null;
	}

	public static void enable(JavaPlugin plugin) {
		if (instance != null) return;
		MedievalEconomy economy = new MedievalEconomy(plugin);
		instance = economy;
		try {
			economy.start();
		} catch (Exception e) {
			plugin.getLogger().log(Level.SEVERE, "No se pudo iniciar Shopkeepers Medieval", e);
		}
	}

	public static void disable() {
		MedievalEconomy economy = instance;
		if (economy == null) return;
		instance = null;
		try {
			economy.stop();
		} catch (Exception e) {
			economy.plugin.getLogger().log(Level.SEVERE, "Error al detener Shopkeepers Medieval", e);
		}
	}

	public static String color(String text) {
		if (text == null) return "";
		return ChatColor.translateAlternateColorCodes('&', text);
	}

	private final JavaPlugin plugin;
	private FileConfiguration config;
	private MarketManager market;
	private ShopPricing pricing;
	private me.medieval.shops.market.TradeLimiter limiter;
	private ShopProfiles profiles;
	private HologramManager holograms;
	private BankManager bank;
	private RouteManager routes;
	private final java.util.List<BukkitTask> tasks = new java.util.ArrayList<>();

	private MedievalEconomy(JavaPlugin plugin) {
		this.plugin = plugin;
	}

	public JavaPlugin getPlugin() {
		return plugin;
	}

	public FileConfiguration config() {
		return config;
	}

	public MarketManager market() {
		return market;
	}

	public HologramManager holograms() {
		return holograms;
	}

	public me.medieval.shops.market.TradeLimiter limiter() {
		return limiter;
	}

	public ShopProfiles profiles() {
		return profiles;
	}

	public ShopPricing pricing() {
		return pricing;
	}

	public BankManager bank() {
		return bank;
	}

	public RouteManager routes() {
		return routes;
	}

	private void loadConfig() {
		File file = new File(plugin.getDataFolder(), "medieval.yml");
		if (!file.exists()) {
			plugin.saveResource("medieval.yml", false);
		}
		YamlConfiguration loaded = YamlConfiguration.loadConfiguration(file);
		java.io.InputStream defaults = plugin.getResource("medieval.yml");
		if (defaults != null) {
			loaded.setDefaults(YamlConfiguration.loadConfiguration(
					new InputStreamReader(defaults, StandardCharsets.UTF_8)));
		}
		this.config = loaded;
	}

	public void reload() {
		loadConfig();
		Currencies.load(plugin, config);
		profiles.invalidateCaches();
		holograms.reloadConfig();
		limiter.reloadConfig();
		market.reloadConfig();
		bank.reloadConfig();
		routes.reloadConfig();
	}

	private void start() {
		loadConfig();
		Currencies.load(plugin, config);
		market = new MarketManager(this);
		limiter = new me.medieval.shops.market.TradeLimiter(this);
		limiter.load();
		profiles = new ShopProfiles(this);
		profiles.load();
		pricing = new ShopPricing(this, market);
		bank = new BankManager(this);
		routes = new RouteManager(this);
		holograms = new HologramManager(this);
		market.load();
		pricing.load();
		bank.load();
		routes.load();

		Bukkit.getPluginManager().registerEvents(new MenuListener(), plugin);
		Bukkit.getPluginManager().registerEvents(new EconomyListener(this), plugin);

		register("mercado", new MarketCommand(this, false));
		register("mercadoadmin", new MarketCommand(this, true));
		register("banco", new BankCommand(this, false));
		register("bancoadmin", new BankCommand(this, true));
		register("ruta", new RouteCommand(this));

		// Tick de 1 minuto: demanda, eventos, historial, interes y guardado.
		tasks.add(Bukkit.getScheduler().runTaskTimer(plugin, () -> {
			market.tickMinute();
			limiter.tickMinute();
			pricing.tickMinute();
			pricing.refreshAll();
		}, 1200L, 1200L));
		tasks.add(Bukkit.getScheduler().runTaskTimer(plugin, bank::tickMinute, 1200L, 1200L));
		// Rutas: comprobar horario cada segundo y mover caminantes cada pocos ticks.
		tasks.add(Bukkit.getScheduler().runTaskTimer(plugin, routes::checkSchedules, 40L, 20L));
		// Hologramas de precios sobre los mercaderes (cada 2 segundos).
		tasks.add(Bukkit.getScheduler().runTaskTimer(plugin, holograms::update, 100L, 40L));
		tasks.add(Bukkit.getScheduler().runTaskTimer(plugin, routes::tickWalkers, 40L, routes.getStepTicks()));

		// PlaceholderAPI (opcional)
		if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null) {
			try {
				new me.medieval.shops.papi.Placeholders(this).register();
				plugin.getLogger().info("PlaceholderAPI detectado: placeholders %medieval_...% registrados.");
			} catch (Throwable t) {
				plugin.getLogger().warning("No se pudo registrar PlaceholderAPI: " + t);
			}
		}
		plugin.getLogger().info("Shopkeepers Medieval activo (mercado, banco, rutas).");
		// Primer ajuste de precios de las tiendas marcadas (cuando Shopkeepers ya cargo las tiendas).
		Bukkit.getScheduler().runTaskLater(plugin, () -> {
			int added = pricing.autoMarkAll();
			if (added > 0) plugin.getLogger().info("Mercado activado automaticamente en " + added + " mercaderes.");
			pricing.refreshAll();
		}, 100L);
	}

	private void stop() {
		for (BukkitTask task : tasks) {
			task.cancel();
		}
		tasks.clear();
		if (holograms != null) holograms.removeAll();
		if (market != null) market.save();
		if (pricing != null && config != null && config.getBoolean("mercado.restaurar-al-apagar", false)) pricing.restoreAllBase();
		if (pricing != null) pricing.save();
		if (limiter != null) limiter.save();
		if (profiles != null) profiles.save();
		if (bank != null) bank.save();
		if (routes != null) routes.save();
	}

	private void register(String name, org.bukkit.command.TabExecutor executor) {
		PluginCommand command = plugin.getCommand(name);
		if (command == null) {
			plugin.getLogger().warning("Comando no declarado en plugin.yml: " + name);
			return;
		}
		command.setExecutor(executor);
		command.setTabCompleter(executor);
	}

	public static String pretty(String materialName) {
		String[] parts = materialName.toLowerCase(Locale.ROOT).split("_");
		StringBuilder sb = new StringBuilder();
		for (String part : parts) {
			if (part.isEmpty()) continue;
			if (sb.length() > 0) sb.append(' ');
			sb.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
		}
		return sb.toString();
	}
}
