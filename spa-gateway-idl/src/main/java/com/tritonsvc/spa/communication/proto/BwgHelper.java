package com.tritonsvc.spa.communication.proto;

import com.google.protobuf.AbstractMessageLite;
import com.tritonsvc.spa.communication.proto.Bwg.Downlink.Model.RequestMetadata;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Map;

/**
 * Utility class helping with creating and handling Spa entities
 */
public final class BwgHelper {

    private BwgHelper() {
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

    public static String getRequestMetadataValue(final String name, final Iterable<Bwg.Downlink.Model.RequestMetadata> metadata) {
        String value = null;
        if (metadata != null) {
            for (final Bwg.Downlink.Model.RequestMetadata metadataElem : metadata) {
                if (metadataElem.hasName() && metadataElem.getName().name().equals(name)) {
                    value = metadataElem.getValue();
                    break;
                }
            }
        }
        return value;
    }

    public static Bwg.Downlink.Model.RequestType getRequestTypeByCode(Integer code) {
        if (code == null) return null;
        return Bwg.Downlink.Model.RequestType.valueOf(code.intValue());
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

    public static Bwg.Downlink.Model.Request buildRequest(final Bwg.Downlink.Model.RequestType type, final Map<String, String> values) {
        final Bwg.Downlink.Model.Request.Builder builder = Bwg.Downlink.Model.Request.newBuilder();
        builder.setRequestType(type);

        if (values != null && values.size() > 0) {
            for (final Map.Entry<String, String> entry : values.entrySet()) {
                builder.addMetadata(buildRequestMetadata(entry.getKey(), entry.getValue()));
            }
        }

        return builder.build();
    }

    public static Bwg.Downlink.Model.SpaRegistrationResponse buildSpaRegistrationResponse(
            final Bwg.Downlink.Model.RegistrationAckState state,
            final String p2pAPSSID, final String p2pAPPassword, final String regKey, final String regUserId) {
        final Bwg.Downlink.Model.SpaRegistrationResponse.Builder builder = Bwg.Downlink.Model.SpaRegistrationResponse.newBuilder();
        builder.setState(state);
        if (p2pAPSSID != null) {
            builder.setP2PAPSSID(p2pAPSSID);
        }
        if (p2pAPPassword != null) {
            builder.setP2PAPPassword(p2pAPPassword);
        }
        if (regKey != null) {
            builder.setRegKey(regKey);
        }
        if (regUserId != null) {
            builder.setRegUserId(regUserId);
        }

        return builder.build();
    }

    public static Bwg.Downlink.Model.RegistrationResponse buildComponentRegistrationResponse(final Bwg.Downlink.Model.RegistrationAckState state) {
        final Bwg.Downlink.Model.RegistrationResponse.Builder builder = Bwg.Downlink.Model.RegistrationResponse.newBuilder();
        builder.setState(state);
        return builder.build();
    }

    public static Bwg.Uplink.Model.RegisterDevice buildRegisterDevice(final String parentDeviceHardwareId,
                                                                      final String deviceTypeName,
                                                                      final String gwSerialNumber,
                                                                      final Iterable<? extends Bwg.Metadata> metadata) {

        final Bwg.Uplink.Model.RegisterDevice.Builder builder = Bwg.Uplink.Model.RegisterDevice.newBuilder();
        if (parentDeviceHardwareId != null) {
            builder.setParentDeviceHardwareId(parentDeviceHardwareId);
        }
        if (metadata != null) {
            builder.addAllMetadata(metadata);
        }
        builder.setDeviceTypeName(deviceTypeName);
        builder.setGatewaySerialNumber(gwSerialNumber);

        return builder.build();
    }

    public static Bwg.Uplink.Model.DownlinkAcknowledge buildDownlinkAcknowledge(final Bwg.AckResponseCode responseCode,
                                                                                final String description) {

        final Bwg.Uplink.Model.DownlinkAcknowledge.Builder builder = Bwg.Uplink.Model.DownlinkAcknowledge.newBuilder();
        builder.setCode(responseCode);

        if (description != null) {
            builder.setDescription(description);
        }

        return builder.build();
    }

    public static byte[] buildUplinkMessage(final String originator,
                                            final String hardwareId,
                                            final Bwg.Uplink.UplinkCommandType uplinkCommandType,
                                            final AbstractMessageLite msg) throws IOException {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();

        final Bwg.Header header = buildHeader(Bwg.CommandType.UPLINK, originator);
        final Bwg.Uplink.UplinkHeader uplinkHeader = buildUplinkHeader(hardwareId, uplinkCommandType);

        header.writeDelimitedTo(out);
        uplinkHeader.writeDelimitedTo(out);
        if (msg != null) {
            msg.writeDelimitedTo(out);
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
                                              final AbstractMessageLite msg) throws IOException {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();

        final Bwg.Header header = buildHeader(Bwg.CommandType.DOWNLINK, originator);
        final Bwg.Downlink.DownlinkHeader downlinkHeader = buildDownlinkHeader(hardwareId, downlinkCommandType);

        header.writeDelimitedTo(out);
        downlinkHeader.writeDelimitedTo(out);
        if (msg != null) {
            msg.writeDelimitedTo(out);
        }

        return out.toByteArray();
    }

    private static Bwg.Downlink.DownlinkHeader buildDownlinkHeader(final String hardwareId, final Bwg.Downlink.DownlinkCommandType downlinkCommandType) {
        final Bwg.Downlink.DownlinkHeader.Builder builder = Bwg.Downlink.DownlinkHeader.newBuilder();
        builder.setHardwareId(hardwareId);
        builder.setCommandType(downlinkCommandType);

        return builder.build();
    }
}
