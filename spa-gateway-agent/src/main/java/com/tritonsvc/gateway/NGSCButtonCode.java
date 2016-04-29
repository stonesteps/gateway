package com.tritonsvc.gateway;

public enum NGSCButtonCode implements Codeable {
    kNoMetaButton     (0),
    kUpMetaButton     (1),
    kDownMetaButton   (2),
    kTempMetaButton   (3),

    kJets1MetaButton  (4),
    kJets2MetaButton  (5),
    kJets3MetaButton  (6),
    kJets4MetaButton  (7),
    kJets5MetaButton  (8),
    kJets6MetaButton  (9),
    kJets7MetaButton  (10),
    kJets8MetaButton  (11),

    kBlower1MetaButton (12),
    kBlower2MetaButton (13),

    kMister1MetaButton (14),
    kMister2MetaButton (15),
    kMister3MetaButton (16),

    kLight1MetaButton  (17),
    kLight2MetaButton  (18),
    kLight3MetaButton  (19),
    kLight4MetaButton  (20),

    kFiberMetaButton   (21),

    kOption1MetaButton (22),
    kOption2MetaButton (23),
    kOption3MetaButton (24),
    kOption4MetaButton (25),

    kEitherLightMetaButton (26),
    kInvertMetaButton      (27),

    kTimerMetaButton (28),
    kSoakMetaButton  (29), // Marquis-specific

    kMicroSilkQuietMetaButton (30), // for panel user interface

    kMenuMetaButton (31), // for dedicated Menu button
    kStirMetaButton (32),
    kEcoModeToggleMetaButton (33),
    kOverrangeUpMetaButton (34),

    kSwimSpaModeMetaButton (39),
    // For LED mapping only (+ embedded TE?)
    kHeater1MetaButton  (40),
    kHeater2MetaButton  (41),
    kOzoneMetaButton    (42),
    kSpaModeMetaButton  (43), // SwimSpa
    kSwimModeMetaButton (44), // SwimSpa
    kAnyHeaterMetaButton (45),
    kAnyJetsMetaButton   (46),

    // ADCM/Embedded TE only
    kStandbyModeMetaButton         (60), // CP500 also
    kPump0MetaButton               (61),
    kPump1OnlyMetaButton           (62),
    kFiberLightOnlyMetaButton      (63),
    kSpaLightOnlyMetaButton        (64),
    kOzoneWithoutTimeoutMetaButton (65),
    kDemoModeMetaButton            (66), // CP500 also
    kMicroSilkAddMetaButton        (67), // for testing amperage totals via ADCM

    // Menu panel
    kTempRangeMetaButton     (80),  // Toggle temp range
    kHeatModeMetaButton      (81),  // Ready <-> Rest

    // Group buttons
    kGroup1MetaButton (90),
    kGroup2MetaButton (91),
    kGroup3MetaButton (92),
    kGroup4MetaButton (93),

    kNavChooseMetaButton (128);

    private int code;
    NGSCButtonCode(int code) {
       this.code = code;
    }

    @Override
    public int getCode() {
        return code;
    }
}

