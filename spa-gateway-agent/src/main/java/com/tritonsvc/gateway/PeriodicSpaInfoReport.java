package com.tritonsvc.gateway;

import com.tritonsvc.agent.GatewayEventDispatcher;
import com.tritonsvc.spa.communication.proto.Bwg.Uplink.Model.Component;
import com.tritonsvc.spa.communication.proto.Bwg.Uplink.Model.RunMode;
import com.tritonsvc.spa.communication.proto.Bwg.Uplink.Model.SpaState;
import com.tritonsvc.spa.communication.proto.Bwg.Uplink.UplinkCommandType;

import java.util.List;

import static com.google.common.collect.Lists.newArrayList;

/**
 * Utility class to send periodic spa info up to cloud
 */
public class PeriodicSpaInfoReport {
    private SpaStateInfo spaState;
    private GatewayEventDispatcher mqtt;

    public PeriodicSpaInfoReport(SpaStateInfo info, GatewayEventDispatcher mqtt) {
        this.spaState = info;
        this.mqtt = mqtt;
    }

    public void publishToCloud(String hardwareId) {
        //TODO - Marek, refer to RS485DataHarvester.processPanelUpdateMessage(), in order to build this out further

        List<Component> spaComponentStates = newArrayList();
        for (ComponentStateInfo info : spaState.getComponentStates()) {
            spaComponentStates.add( Component.newBuilder()
                    .setPort(info.getPort())
                    .setType(info.getComponentType())
                    .setValue(info.getValue()).build());
        }

        SpaState state = SpaState.newBuilder()
                .setRunMode(spaState.getRunMode() == 0 ? RunMode.READY : RunMode.REST)
                .setHour(spaState.getHour())
                .setMinute(spaState.getMinute())
                .setDesiredTemp(spaState.getDesiredTemperature())
                .setErrorCode(spaState.getErrorCode())
                .addAllComponent(spaComponentStates)
                .build();

        mqtt.sendUplink(hardwareId, null, UplinkCommandType.SPA_STATE, state);
    }
}
