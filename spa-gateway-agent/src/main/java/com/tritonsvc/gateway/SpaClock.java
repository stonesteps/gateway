package com.tritonsvc.gateway;

/**
 * track spa local clock time
 */
public class SpaClock {
    int hour;
    int minute;

    /**
     * constructor
     * @param hour
     * @param minute
     */
    public SpaClock(int hour, int minute) {
        this.hour = hour;
        this.minute = minute;
    }

    public int getHour() {
        return hour;
    }

    public void setHour(int hour) {
        this.hour = hour;
    }

    public int getMinute() {
        return minute;
    }

    public void setMinute(int minute) {
        this.minute = minute;
    }
}
