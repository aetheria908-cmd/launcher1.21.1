package me.clip.placeholderapi.expansion;

import org.bukkit.OfflinePlayer;

/**
 * SOLO PARA COMPILAR. Reemplaza a la clase real de PlaceholderAPI para que el build no dependa de
 * descargarla. Esta clase se EXCLUYE del jar (ver build.gradle): en el servidor se usa la de
 * PlaceholderAPI. Los metodos deben coincidir con los de la clase real.
 */
public abstract class PlaceholderExpansion {

	public abstract String getIdentifier();

	public abstract String getAuthor();

	public abstract String getVersion();

	public boolean persist() {
		return false;
	}

	public String onRequest(OfflinePlayer player, String params) {
		return null;
	}

	public boolean register() {
		return false;
	}
}
