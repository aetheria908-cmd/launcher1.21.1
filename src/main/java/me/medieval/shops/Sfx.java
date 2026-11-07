package me.medieval.shops;

import org.bukkit.Bukkit;
import org.bukkit.SoundCategory;
import org.bukkit.entity.Player;

/**
 * Sonidos del modulo medieval. Con el resourcepack usa los sonidos propios (shopkeepers:...);
 * sin el, usa sonidos de vanilla parecidos.
 */
public final class Sfx {

	public enum Type {
		CLICK("ui.click", "block.wooden_button.click_on", 0.7F, 1.3F),
		PAGE("ui.page", "item.book.page_turn", 0.8F, 1.0F),
		COIN("ui.coin", "entity.experience_orb.pickup", 0.5F, 1.7F),
		DEPOSIT("ui.deposit", "block.chain.place", 0.9F, 1.1F),
		WITHDRAW("ui.withdraw", "entity.item.pickup", 0.9F, 0.8F),
		ERROR("ui.error", "entity.villager.no", 0.8F, 0.9F),
		TRADE_OK("trade.success", "block.note_block.chime", 0.8F, 1.5F),
		EVENT_START("event.horn", "event.raid.horn", 0.7F, 1.0F),
		EVENT_END("event.bell", "block.bell.use", 0.8F, 1.0F);

		private final String custom;
		private final String vanilla;
		private final float vanillaVolume;
		private final float vanillaPitch;

		Type(String custom, String vanilla, float vanillaVolume, float vanillaPitch) {
			this.custom = custom;
			this.vanilla = vanilla;
			this.vanillaVolume = vanillaVolume;
			this.vanillaPitch = vanillaPitch;
		}
	}

	private static boolean enabled(MedievalEconomy economy) {
		return economy.config().getBoolean("sonidos.habilitado", true);
	}

	public static void play(Player player, Type type) {
		MedievalEconomy economy = MedievalEconomy.get();
		if (economy == null || player == null || !enabled(economy)) return;
		boolean custom = economy.config().getBoolean("gui.usar-resourcepack", false);
		float volume = (float) economy.config().getDouble("sonidos.volumen", 1.0D);
		String name = custom ? "shopkeepers:" + type.custom : "minecraft:" + type.vanilla;
		float vol = custom ? volume : volume * type.vanillaVolume;
		float pitch = custom ? 1.0F : type.vanillaPitch;
		player.playSound(player.getLocation(), name, SoundCategory.MASTER, vol, pitch);
	}

	public static void playAll(Type type) {
		for (Player player : Bukkit.getOnlinePlayers()) {
			play(player, type);
		}
	}

	/**
	 * Sonido de compra exitosa.
	 * 
	 * @return true si reemplaza al sonido original del plugin (entonces no debe sonar el original)
	 */
	public static boolean tradeSucceeded(Player player) {
		MedievalEconomy economy = MedievalEconomy.get();
		if (economy == null || !enabled(economy)) return false;
		if (!economy.config().getBoolean("sonidos.reemplazar-sonido-compra", true)) return false;
		play(player, Type.TRADE_OK);
		return true;
	}

	private Sfx() {
	}
}
