package me.medieval.shops.market;

import java.util.regex.Pattern;

import org.bukkit.Material;

/** Categorias del mercado. Cada una tiene su propio fondo en el resourcepack. */
public enum MarketCategory {

	FORJA("&c&lLa Forja", Material.ANVIL, '\uE003',
			"&7Armas, armaduras, herramientas", "&7y lingotes."),
	GRANJA("&a&lGranja y Taberna", Material.WHEAT, '\uE004',
			"&7Comida, cultivos, carne", "&7y cueros."),
	ALQUIMIA("&d&lAlquimia y Libros", Material.BREWING_STAND, '\uE005',
			"&7Pociones, ingredientes,", "&7libros y encantamientos."),
	MINERALES("&b&lMinas y Gemas", Material.DIAMOND, '\uE006',
			"&7Menas, gemas y bloques", "&7de metal."),
	CONSTRUCCION("&6&lConstruccion", Material.BRICKS, '\uE007',
			"&7Bloques para construir", "&7el reino."),
	VARIOS("&e&lBazar", Material.CHEST, '\uE008',
			"&7Todo lo demas.");

	private final String displayName;
	private final Material icon;
	private final char background;
	private final String[] description;

	MarketCategory(String displayName, Material icon, char background, String... description) {
		this.displayName = displayName;
		this.icon = icon;
		this.background = background;
		this.description = description;
	}

	public String getDisplayName() {
		return displayName;
	}

	public Material getIcon() {
		return icon;
	}

	public char getBackground() {
		return background;
	}

	public String[] getDescription() {
		return description;
	}

	private static Pattern[] compile(String... globs) {
		Pattern[] patterns = new Pattern[globs.length];
		for (int i = 0; i < globs.length; i++) {
			StringBuilder sb = new StringBuilder();
			for (char c : globs[i].toCharArray()) {
				if (c == '*') sb.append(".*");
				else sb.append(c);
			}
			patterns[i] = Pattern.compile(sb.toString());
		}
		return patterns;
	}

	private static final Pattern[] FORJA_P = compile("*_SWORD", "*_AXE", "*_PICKAXE", "*_SHOVEL",
			"*_HOE", "BOW", "CROSSBOW", "ARROW", "TIPPED_ARROW", "SPECTRAL_ARROW", "TRIDENT", "MACE",
			"SHIELD", "*_HELMET", "*_CHESTPLATE", "*_LEGGINGS", "*_BOOTS", "ANVIL", "IRON_INGOT",
			"IRON_NUGGET", "GOLD_INGOT", "GOLD_NUGGET", "COPPER_INGOT", "NETHERITE_INGOT",
			"NETHERITE_SCRAP", "SHEARS", "FLINT_AND_STEEL", "FISHING_ROD", "*_HORSE_ARMOR", "SADDLE",
			"SMITHING_TABLE", "GRINDSTONE", "BLAST_FURNACE", "FURNACE");
	private static final Pattern[] ALQUIMIA_P = compile("*POTION*", "BREWING_STAND", "CAULDRON",
			"BLAZE_*", "NETHER_WART", "GHAST_TEAR", "SPIDER_EYE", "FERMENTED_SPIDER_EYE", "MAGMA_CREAM",
			"GLISTERING_MELON_SLICE", "REDSTONE", "GLOWSTONE_DUST", "GUNPOWDER", "ENDER_*",
			"EXPERIENCE_BOTTLE", "ENCHANTED_*", "BOOK", "WRITABLE_BOOK", "WRITTEN_BOOK", "BOOKSHELF",
			"LAPIS_LAZULI", "AMETHYST_SHARD", "PHANTOM_MEMBRANE", "RABBIT_FOOT", "DRAGON_BREATH",
			"TOTEM_OF_UNDYING", "ENCHANTING_TABLE", "GLASS_BOTTLE", "NAME_TAG", "PAPER", "MAP",
			"FILLED_MAP", "COMPASS", "CLOCK", "LECTERN");
	private static final Pattern[] GRANJA_P = compile("WHEAT*", "BREAD", "CARROT*", "POTATO*",
			"BAKED_POTATO", "POISONOUS_POTATO", "BEETROOT*", "MELON*", "PUMPKIN*", "APPLE",
			"GOLDEN_APPLE", "ENCHANTED_GOLDEN_APPLE", "*_SEEDS", "BEEF", "PORKCHOP", "CHICKEN", "MUTTON",
			"RABBIT", "COOKED_*", "COD", "SALMON", "TROPICAL_FISH", "PUFFERFISH", "EGG", "MILK_BUCKET",
			"SUGAR*", "HAY_BLOCK", "COOKIE", "CAKE", "PUMPKIN_PIE", "*_STEW", "HONEY*", "HONEYCOMB",
			"SWEET_BERRIES", "GLOW_BERRIES", "COCOA_BEANS", "DRIED_KELP*", "LEATHER", "RABBIT_HIDE",
			"FEATHER", "BONE", "BONE_MEAL", "COMPOSTER", "ROTTEN_FLESH", "STRING", "INK_SAC", "SLIME_BALL");
	private static final Pattern[] MINERALES_P = compile("*_ORE", "DIAMOND*", "EMERALD*", "COAL*",
			"CHARCOAL", "RAW_*", "QUARTZ*", "NETHERITE_BLOCK", "GOLD_*", "IRON_*", "COPPER_*",
			"OBSIDIAN", "CRYING_OBSIDIAN", "AMETHYST_BLOCK", "LAPIS_*", "ANCIENT_DEBRIS", "FLINT",
			"CLAY_BALL", "BRICK", "NETHER_BRICK");

	private static boolean any(Pattern[] patterns, String key) {
		for (Pattern p : patterns) {
			if (p.matcher(key).matches()) return true;
		}
		return false;
	}

	public static MarketCategory of(String key) {
		if (any(FORJA_P, key)) return FORJA;
		if (any(ALQUIMIA_P, key)) return ALQUIMIA;
		if (any(GRANJA_P, key)) return GRANJA;
		if (any(MINERALES_P, key)) return MINERALES;
		Material material = Material.matchMaterial(key);
		if (material != null && material.isBlock()) return CONSTRUCCION;
		return VARIOS;
	}
}
