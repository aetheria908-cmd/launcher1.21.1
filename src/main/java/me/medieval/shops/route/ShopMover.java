package me.medieval.shops.route;

import java.lang.reflect.Method;
import java.util.logging.Level;

import org.bukkit.Location;
import org.bukkit.block.BlockFace;

import com.nisovin.shopkeepers.api.shopkeeper.Shopkeeper;

import me.medieval.shops.MedievalEconomy;

/**
 * Mueve un mercader. La API publica de Shopkeepers no tiene "teletransportar", asi que se usa
 * el metodo teleport(Location, BlockFace) del propio plugin mediante reflexion. Si cambia en una
 * version futura, las rutas se desactivan solas (el resto del addon sigue funcionando).
 */
final class ShopMover {

	private static boolean failed = false;
	private static Method method = null;
	private static Class<?> methodClass = null;

	static boolean isAvailable() {
		return !failed;
	}

	static boolean teleport(Shopkeeper shopkeeper, Location location) {
		if (failed) return false;
		try {
			if (method == null || methodClass != shopkeeper.getClass()) {
				method = shopkeeper.getClass().getMethod("teleport", Location.class, BlockFace.class);
				method.setAccessible(true);
				methodClass = shopkeeper.getClass();
			}
			method.invoke(shopkeeper, location, null);
			return true;
		} catch (NoSuchMethodException e) {
			failed = true;
			log("Esta version de Shopkeepers no tiene teleport(Location, BlockFace): rutas desactivadas.", null);
		} catch (Exception e) {
			failed = true;
			log("No se pudo mover un mercader; rutas desactivadas.", e);
		}
		return false;
	}

	private static void log(String message, Exception e) {
		MedievalEconomy economy = MedievalEconomy.get();
		if (economy == null) return;
		if (e == null) economy.getPlugin().getLogger().warning(message);
		else economy.getPlugin().getLogger().log(Level.WARNING, message, e);
	}

	private ShopMover() {
	}
}
