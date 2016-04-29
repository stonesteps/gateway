package com.tritonsvc.gateway;

/**
 * Created by holow on 4/28/2016.
 */
public enum JacuzziCommandCode implements Codeable {

    NONE                (0),

    UP                  (1),
    DOWN                (2),
    TEMPERATURE         (3),
    OVERRANGE_UP        (34),

    JETS1               (4),
    JETS2               (5),
    JETS3               (6),
    BLOWER              (12),
    ACTIVATE_PUMPS      (13),
    HIGH_FLOW_ON_DEMAND (14),
    UV_ON_DEMAND        (15),
    LIGHT1              (17),
    LIGHT2              (18),
    LIGHT3              (19),
    LIGHT4              (20),

    TEMP_FORMAT_C       (40),
    TEMP_FORMAT_F       (41),
    TIME_FORMAT_12_HOUR (42),
    TIME_FORMAT_24_HOUR (43),
    ACK_CONDITION       (44),
    REDISPLAY_ERRORS    (47),
    BYPASS_REGISTRATION (48),

    UL_TIMEOUTS         (45),
    UL_OVERTEMP         (46),

    BACK                (202),
    SAVE                (203),
    HOME                (204),
    IGNORE              (255);

    private final int code;
    JacuzziCommandCode(final int code) {
        this.code = code;
    }

    @Override
    public int getCode() {
        return code;
    }
}
