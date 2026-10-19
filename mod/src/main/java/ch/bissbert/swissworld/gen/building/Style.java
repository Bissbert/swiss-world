package ch.bissbert.swissworld.gen.building;

import ch.bissbert.swissworld.cell.SwdCell;

/** Building archetype (PLAN §8), chosen from GWR class/period, OBJEKTART and altitude, deterministic per gid. */
public enum Style {
    CHALET, TOWN_HOUSE, OLD_TOWN, MODERN, BARN, CHURCH, INDUSTRIAL, HOTEL, COMMERCIAL, TANK, GREENHOUSE, WALL, BRIDGE;

    /** One open hall (or none) per building instead of storeys and rooms. */
    public boolean hall() {
        return this == BARN || this == CHURCH || this == INDUSTRIAL || this == GREENHOUSE || empty();
    }

    /** Structures without door, interior or loot: tank (hollow), large wall (solid), covered bridge (walk-through). */
    public boolean empty() {
        return this == TANK || this == WALL || this == BRIDGE;
    }

    public boolean residential() {
        return this == CHALET || this == TOWN_HOUSE || this == OLD_TOWN || this == MODERN;
    }

    /**
     * GKLAS (GWR building class): 11xx residential, 1211/1212 hotel, 1220/1230/1231/126x commercial and public,
     * 1241/1242/1251/1252 industrial and storage, 1271 agricultural, 1272 religious.
     * OBJEKTART (docs/swd-format.md §6): 3 sacred building, 7 tank, 8 greenhouse, 9 large wall, 10 covered bridge.
     */
    public static Style pick(SwdCell.Building b, double baseH, int footprint) {
        int k = b.gwrClass(), period = b.gwrPeriod();
        long gid = b.gid();
        if (b.objektart() == 3 || k == 1272) return CHURCH;
        switch (b.objektart()) {
            case 7: return TANK;
            case 8: return GREENHOUSE;
            case 9: return WALL;
            case 10: return BRIDGE;
            default: break;
        }
        if (k == 1271) return BARN;
        if (k == 1241 || k == 1242 || k == 1251 || k == 1252) return INDUSTRIAL;
        if (k == 1211 || k == 1212) return HOTEL;
        if (k == 1220 || k == 1230 || k == 1231 || (k >= 1261 && k <= 1265)) return COMMERCIAL;
        boolean residential = k / 100 == 11 || (b.gwrCategory() >= 1020 && b.gwrCategory() <= 1040);
        if (!residential && footprint < 40) return BARN;
        // chalets get likelier with altitude and age
        double chalet = Math.clamp((baseH - 600) / 700, 0.1, 0.9);
        if (period == 8011) chalet += 0.15;
        if (period >= 8016) chalet *= 0.5;
        if (!residential && k == 0) chalet = Math.max(chalet, 0.3);
        if (Hash.unit(gid, 1) < chalet) return CHALET;
        if (period == 8011) return OLD_TOWN;
        if (period >= 8018 && Hash.unit(gid, 2) < 0.6) return MODERN;
        return TOWN_HOUSE;
    }
}
