package com.dnl.appenv.pro.core;

public final class Identity {
    public final String seed;
    public final String oaid;
    public final String deviceId;
    public final String androidId;
    public final long generation;

    public Identity(String seed, String oaid, String deviceId, String androidId, long generation) {
        this.seed = seed;
        this.oaid = oaid;
        this.deviceId = deviceId;
        this.androidId = androidId;
        this.generation = generation;
    }

    @Override
    public String toString() {
        return "Identity{" +
                "generation=" + generation +
                ", seed='" + seed + '\'' +
                ", oaid='" + oaid + '\'' +
                ", deviceId='" + deviceId + '\'' +
                ", androidId='" + androidId + '\'' +
                '}';
    }
}
