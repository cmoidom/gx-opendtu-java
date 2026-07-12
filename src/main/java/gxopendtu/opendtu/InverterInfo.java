package gxopendtu.opendtu;

/** All inverters OpenDTU currently knows about, used by the config web UI's discovery button. */
public record InverterInfo(String serial, String name, double maxPowerW) {}
