package com.tritonsvc.messageprocessor.util;

import com.bwg.iot.model.Spa;
import com.google.protobuf.AbstractMessageLite;
import com.tritonsvc.spa.communication.proto.Bwg;
import com.tritonsvc.spa.communication.proto.Bwg.Downlink.Model.RequestMetadata;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/**
 * Utility class helping with creating and handling Spa entities
 */
public final class SpaDataHelper {

    private static final Logger log = LoggerFactory.getLogger(SpaDataHelper.class);

    private SpaDataHelper() {
        // utility class
    }

    public static String getMetadataValue(final String name, final Iterable<Bwg.Metadata> metadata) {
        String value = null;
        if (metadata != null) {
            for (final Bwg.Metadata metadataElem : metadata) {
                if (metadataElem.hasName() && metadataElem.getName().equals(name)) {
                    value = metadataElem.getValue();
                    break;
                }
            }
        }
        return value;
    }

    public static RequestMetadata buildRequestMetadata(final String name, final String value) {
        final RequestMetadata.Builder builder = RequestMetadata.newBuilder();

        for (Bwg.Downlink.Model.SpaCommandAttribName attrib : Bwg.Downlink.Model.SpaCommandAttribName.values()) {
            if (attrib.name().equalsIgnoreCase(name)) {
                builder.setName(attrib).setValue(value);
                return builder.build();
            }
        }
        throw new IllegalArgumentException("request meta attrib name is not defined in bwg.proto: " + name);
    }

    public static Bwg.Metadata buildMetadata(final String name, final String value) {
        final Bwg.Metadata.Builder builder = Bwg.Metadata.newBuilder();
        builder.setName(name).setValue(value);
        return builder.build();
    }

    public static Bwg.Downlink.Model.Request buildRequest(final Bwg.Downlink.Model.RequestType type, final HashMap<String, String> values) {
        final Bwg.Downlink.Model.Request.Builder builder = Bwg.Downlink.Model.Request.newBuilder();
        builder.setRequestType(type);

        if (values != null && values.size() > 0) {
            for (final Map.Entry<String, String> entry: values.entrySet()) {
                builder.addMetadata(buildRequestMetadata(entry.getKey(), entry.getValue()));
            }
        }

        return builder.build();
    }

    public static Bwg.Downlink.Model.SpaRegistrationResponse buildSpaRegistrationResponse(final Bwg.Downlink.Model.RegistrationAckState state, final Spa spa) {
        final Bwg.Downlink.Model.SpaRegistrationResponse.Builder builder = Bwg.Downlink.Model.SpaRegistrationResponse.newBuilder();
        builder.setState(state);
        if (spa != null) {
            if (spa.getP2pAPSSID() != null) {
                builder.setP2PAPSSID(spa.getP2pAPSSID());
            }
            if (spa.getP2pAPPassword() != null) {
                builder.setP2PAPPassword(spa.getP2pAPPassword());
            }
        }

        return builder.build();
    }

    public static Bwg.Uplink.Model.RegisterDevice buildRegisterDevice(final String parentDeviceHardwareId,
                                                                      final String deviceTypeName,
                                                                      final Iterable<? extends Bwg.Metadata> metadata) {

        final Bwg.Uplink.Model.RegisterDevice.Builder builder = Bwg.Uplink.Model.RegisterDevice.newBuilder();
        if (parentDeviceHardwareId != null) {
            builder.setParentDeviceHardwareId(parentDeviceHardwareId);
        }
        if (metadata != null) {
            builder.addAllMetadata(metadata);
        }
        builder.setDeviceTypeName(deviceTypeName);

        return builder.build();
    }

    public static byte[] buildUplinkMessage(final String originator,
                                            final String hardwareId,
                                            final Bwg.Uplink.UplinkCommandType uplinkCommandType,
                                            final AbstractMessageLite msg) {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();

        final Bwg.Header header = buildHeader(Bwg.CommandType.UPLINK, originator);
        final Bwg.Uplink.UplinkHeader uplinkHeader = buildUplinkHeader(hardwareId, uplinkCommandType);

        try {
            header.writeDelimitedTo(out);
            uplinkHeader.writeDelimitedTo(out);
            if (msg != null) {
                msg.writeDelimitedTo(out);
            }
        } catch (IOException e) {
            log.error("error building uplink message", e);
        }
        return out.toByteArray();
    }

    private static Bwg.Header buildHeader(final Bwg.CommandType commandType, final String originator) {
        final Bwg.Header.Builder builder = Bwg.Header.newBuilder();
        builder.setCommand(commandType).setSentTimestamp(System.currentTimeMillis());
        if (originator != null) {
            builder.setOriginator(originator);
        }

        return builder.build();
    }

    private static Bwg.Uplink.UplinkHeader buildUplinkHeader(final String hardwareId, final Bwg.Uplink.UplinkCommandType uplinkCommandType) {
        final Bwg.Uplink.UplinkHeader.Builder builder = Bwg.Uplink.UplinkHeader.newBuilder();
        if (hardwareId != null) {
            builder.setHardwareId(hardwareId);
        }
        builder.setCommand(uplinkCommandType);

        return builder.build();
    }

    public static byte[] buildDownlinkMessage(final String originator,
                                              final String hardwareId,
                                              final Bwg.Downlink.DownlinkCommandType downlinkCommandType,
                                              final AbstractMessageLite msg) {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();

        final Bwg.Header header = buildHeader(Bwg.CommandType.DOWNLINK, originator);
        final Bwg.Downlink.DownlinkHeader downlinkHeader = buildDownlinkHeader(hardwareId, downlinkCommandType);

        try {
            header.writeDelimitedTo(out);
            downlinkHeader.writeDelimitedTo(out);
            if (msg != null) {
                msg.writeDelimitedTo(out);
            }
        } catch (IOException e) {
            log.error("error building downlink message", e);
        }
        return out.toByteArray();
    }

    private static Bwg.Downlink.DownlinkHeader buildDownlinkHeader(final String hardwareId, final Bwg.Downlink.DownlinkCommandType downlinkCommandType) {
        final Bwg.Downlink.DownlinkHeader.Builder builder = Bwg.Downlink.DownlinkHeader.newBuilder();
        if (hardwareId != null) {
            builder.setHardwareId(hardwareId);
        }
        builder.setCommandType(downlinkCommandType);

        return builder.build();
    }
}
