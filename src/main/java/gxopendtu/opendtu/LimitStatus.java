package gxopendtu.opendtu;

public record LimitStatus(double limitRelative, double maxPower, String limitSetStatus) {

    /** limitSetStatus goes Pending -> Ok after RF acknowledgement (a few seconds, normal for sub-GHz). */
    public boolean acknowledged() {
        return "Ok".equals(limitSetStatus);
    }
}
