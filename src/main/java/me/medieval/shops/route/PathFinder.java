package me.medieval.shops.route;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;

/** Buscador de caminos A* sencillo sobre bloques (sin teletransporte, evita paredes y huecos). */
final class PathFinder {

	private static final class Node implements Comparable<Node> {
		final int x;
		final int y;
		final int z;
		double g;
		double f;
		Node parent;

		Node(int x, int y, int z) {
			this.x = x;
			this.y = y;
			this.z = z;
		}

		@Override
		public int compareTo(Node other) {
			return Double.compare(f, other.f);
		}
	}

	private static final int[][] DIRS = {
			{ 1, 0 }, { -1, 0 }, { 0, 1 }, { 0, -1 }, { 1, 1 }, { 1, -1 }, { -1, 1 }, { -1, -1 }
	};
	private static final int[] DY = { 0, 1, -1, -2, -3 };

	private final World world;

	PathFinder(World world) {
		this.world = world;
	}

	private static long key(int x, int y, int z) {
		return ((x & 0x3FFFFFFL) << 38) | ((z & 0x3FFFFFFL) << 12) | (y & 0xFFFL);
	}

	private boolean loaded(int x, int z) {
		return world.isChunkLoaded(x >> 4, z >> 4);
	}

	private boolean passable(int x, int y, int z) {
		if (y < world.getMinHeight() || y >= world.getMaxHeight()) return false;
		if (!loaded(x, z)) return false;
		Block block = world.getBlockAt(x, y, z);
		Material type = block.getType();
		if (block.isLiquid()) return false;
		if (type == Material.FIRE || type == Material.SOUL_FIRE || type == Material.CACTUS
				|| type == Material.SWEET_BERRY_BUSH || type == Material.WITHER_ROSE
				|| type == Material.POWDER_SNOW || type == Material.COBWEB
				|| type == Material.MAGMA_BLOCK) {
			return false;
		}
		return block.isPassable() || Tag.DOORS.isTagged(type) || Tag.FENCE_GATES.isTagged(type);
	}

	private boolean solidFloor(int x, int y, int z) {
		if (!loaded(x, z)) return false;
		Block block = world.getBlockAt(x, y, z);
		Material type = block.getType();
		if (block.isLiquid()) return false;
		if (type == Material.CACTUS || type == Material.MAGMA_BLOCK || type == Material.CAMPFIRE
				|| type == Material.SOUL_CAMPFIRE) {
			return false;
		}
		return !block.isPassable();
	}

	private boolean standable(int x, int y, int z) {
		return solidFloor(x, y - 1, z) && passable(x, y, z) && passable(x, y + 1, z);
	}

	/**
	 * @return lista de bloques (x, y, z) desde el siguiente al inicio hasta el final, o null si no
	 *         hay camino.
	 */
	List<int[]> find(int sx, int sy, int sz, int gx, int gy, int gz, int maxNodes, int maxRadius) {
		PriorityQueue<Node> open = new PriorityQueue<>();
		Map<Long, Node> best = new HashMap<>();
		Node start = new Node(sx, sy, sz);
		start.g = 0;
		start.f = heuristic(start, gx, gy, gz);
		open.add(start);
		best.put(key(sx, sy, sz), start);

		int expanded = 0;
		Node goal = null;
		while (!open.isEmpty() && expanded < maxNodes) {
			Node current = open.poll();
			expanded++;
			int dxg = Math.abs(current.x - gx);
			int dzg = Math.abs(current.z - gz);
			if (dxg <= 1 && dzg <= 1 && Math.abs(current.y - gy) <= 2) {
				goal = current;
				break;
			}
			for (int[] dir : DIRS) {
				int nx = current.x + dir[0];
				int nz = current.z + dir[1];
				if (Math.abs(nx - sx) > maxRadius || Math.abs(nz - sz) > maxRadius) continue;
				boolean diagonal = dir[0] != 0 && dir[1] != 0;
				for (int dy : DY) {
					int ny = current.y + dy;
					if (!standable(nx, ny, nz)) continue;
					int level = Math.max(current.y, ny);
					// Espacio libre para caminar hacia el bloque (pies y cabeza al nivel mas alto).
					if (!passable(nx, level, nz) || !passable(nx, level + 1, nz)) continue;
					if (dy > 0 && !passable(current.x, current.y + 2, current.z)) continue;
					if (dy < 0) {
						boolean clear = true;
						for (int yy = ny; yy <= current.y + 1; yy++) {
							if (!passable(nx, yy, nz)) {
								clear = false;
								break;
							}
						}
						if (!clear) continue;
					}
					if (diagonal) {
						if (!passable(nx, level, current.z) || !passable(nx, level + 1, current.z)
								|| !passable(current.x, level, nz) || !passable(current.x, level + 1, nz)) {
							continue;
						}
					}
					double cost = (diagonal ? 1.414D : 1.0D) + (dy != 0 ? 0.6D : 0.0D);
					double g = current.g + cost;
					long k = key(nx, ny, nz);
					Node known = best.get(k);
					if (known != null && known.g <= g) break;
					Node next = new Node(nx, ny, nz);
					next.g = g;
					next.f = g + heuristic(next, gx, gy, gz);
					next.parent = current;
					best.put(k, next);
					open.add(next);
					break; // Una sola altura por columna vecina.
				}
			}
		}
		if (goal == null) return null;
		List<int[]> path = new ArrayList<>();
		for (Node n = goal; n != null && n.parent != null; n = n.parent) {
			path.add(new int[] { n.x, n.y, n.z });
		}
		Collections.reverse(path);
		return path;
	}

	private static double heuristic(Node n, int gx, int gy, int gz) {
		double dx = Math.abs(n.x - gx);
		double dz = Math.abs(n.z - gz);
		double diag = Math.min(dx, dz);
		return (dx + dz) + (1.414D - 2.0D) * diag + Math.abs(n.y - gy) * 0.5D;
	}
}
