package ch.bissbert.swissworld.gen.building;

/** Horizontal direction in block space (+z = south), kept free of Minecraft types so plans are unit-testable. */
public enum Dir {
    N(0, -1), E(1, 0), S(0, 1), W(-1, 0);

    public final int dx, dz;

    Dir(int dx, int dz) {
        this.dx = dx;
        this.dz = dz;
    }

    public Dir opposite() {
        return values()[(ordinal() + 2) & 3];
    }

    public Dir cw() {
        return values()[(ordinal() + 1) & 3];
    }

    public boolean alongX() {
        return dx != 0;
    }

    /** BTBL entranceSide: 1 N, 2 E, 3 S, 4 W; anything else is null. */
    public static Dir ofEntrance(int side) {
        return side >= 1 && side <= 4 ? values()[side - 1] : null;
    }
}
