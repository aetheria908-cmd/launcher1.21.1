package me.medieval.shops;

import org.bukkit.plugin.java.JavaPlugin;

/** Addon de Shopkeepers: mercado dinamico, banco, rutas de mercaderes, sonidos y resourcepack. */
public final class MedievalPlugin extends JavaPlugin {

	@Override
	public void onEnable() {
		MedievalEconomy.enable(this);
	}

	@Override
	public void onDisable() {
		MedievalEconomy.disable();
	}
}
