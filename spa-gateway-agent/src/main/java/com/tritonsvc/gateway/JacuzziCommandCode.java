package com.tritonsvc.gateway;

/**
 * this is from the Jacuzzi ICD, Appendix B, most of these are not used, any that are
 * will be prefixed with 'k'
 */
public enum JacuzziCommandCode implements Codeable {

    NONE                (0),
    UP                  (1),
    DOWN                (2),
    TEMPERATURE         (3),
    OVERRANGE_UP        (34),

    kJets1MetaButton               (4),
    kJets2MetaButton               (5),
    kJets3MetaButton               (6),
    kBlower1MetaButton             (12),
    ACTIVATE_PUMPS                 (13),
    HIGH_FLOW_ON_DEMAND            (14),
    UV_ON_DEMAND                   (15),
    kLight1MetaButton              (17),
    kLight2MetaButton              (18),
    kLight3MetaButton              (19),
    kLight4MetaButton              (20),

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
