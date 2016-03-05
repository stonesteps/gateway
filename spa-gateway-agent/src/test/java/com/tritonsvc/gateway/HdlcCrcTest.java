package com.tritonsvc.gateway;

import org.junit.Test;

import static org.junit.Assert.assertTrue;

public class HdlcCrcTest {
    @Test
    public void itGeneratesRoundTripFCS() throws Exception {
        byte fcs = HdlcCrc.generateFCS(new byte[]{126, 5,16,-65,7,0,0});
        boolean valid = HdlcCrc.isValidFCS(new byte[]{5,16,-65,7,fcs});
        assertTrue(valid);
    }
}
