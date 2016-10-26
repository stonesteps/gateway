package com.tritonsvc.messageprocessor.state;

import com.bwg.iot.model.SpaCommand;
import com.tritonsvc.spa.communication.proto.Bwg;

/**
 * Created by holow on 25.10.2016.
 */
public final class ExpectedStateBuilder {

    private ExpectedStateBuilder() {
        // utility class
    }

    public static final ExpectedState buildExpectedStateFromCommand(final SpaCommand spaCommand) {

        if (spaCommand.getRequestTypeId() != null && spaCommand.getRequestTypeId().equals(Integer.valueOf(SpaCommand.RequestType.HEATER.getCode()))) {
            final int desiredTemperature = getInteger(spaCommand.getValues().get(Bwg.Downlink.Model.SpaCommandAttribName.DESIREDTEMP.name()));
            return new DesiredTemperatureState(desiredTemperature);
        }


        return null;
    }

    private static int getInteger(String string) {
        try {
            return Integer.parseInt(string);
        } catch (final NumberFormatException e) {
            // ignore
            return 0;
        }
    }
}
