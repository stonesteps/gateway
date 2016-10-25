package com.tritonsvc.messageprocessor;

import com.tritonsvc.messageprocessor.notifications.NotnoopApnsSenderBuilder;
import com.tritonsvc.messageprocessor.notifications.PushNotificationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.security.KeyFactory;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;

@Configuration
public class MessageProcessorConfiguration {

    private static final Logger log = LoggerFactory.getLogger(MessageProcessorConfiguration.class);

    @Value("${downlinkTopicName:BWG/spa/downlink}")
    private String downlinkTopicName;
    @Value("${uplinkTopicName:BWG/spa/uplink}")
    private String uplinkTopicName;
    @Value("${caRootFilePem:donotfind}")
    private String caRootFilePem;
    @Value("${clientCertFilePem:donotfind}")
    private String clientCertFilePem;
    @Value("${clientKeyFilePkcs8:donotfind}")
    private String clientKeyFilePkcs8;
    @Value("${swUpgradeUrl:http://localhost:8080/sw_upgrade}")
    private String swUpgradeUrl;

    @Value("${apnsCertPath:/ControlMySpa_dev.p12}")
    private String apnsCertPath;
    @Value("${apnsCertPassword:SpaOwner1.0}")
    private String apnsCertPassword;
    @Value("${apnsUseProduction:false}")
    private boolean apnsUseProduction;

    public String getDownlinkTopicName() {
        return downlinkTopicName;
    }

    public String getUplinkTopicName() {
        return uplinkTopicName;
    }

    public String getDownlinkTopicName(final String serialNumber) {
        if (serialNumber == null) {
            return downlinkTopicName;
        } else {
            final StringBuilder sb = new StringBuilder(downlinkTopicName);
            sb.append("/").append(serialNumber);
            return sb.toString();
        }
    }

    /**
     * check the runtime parameters and derive crypto objects
     * if certs/keys were specified as parameters
     *
     * @return PkiInfo
     */
    public PkiInfo obtainPKIArtifacts() {
        File caRootCert = new File(caRootFilePem);
        File clientPubCert = new File(clientCertFilePem);
        File clientPrivKey = new File(clientKeyFilePkcs8);
        KeyStore ks;
        TrustManagerFactory tmf;
        CertificateFactory fact;
        KeyManagerFactory kmf = null;
        PkiInfo pki = new PkiInfo();

        if (!caRootCert.exists()) {
            return pki;
        }

        try (FileInputStream isRoot = new FileInputStream(caRootCert)) {
            ks = KeyStore.getInstance(KeyStore.getDefaultType());
            ks.load(null);
            tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            fact = CertificateFactory.getInstance("X.509");

            pki.setCaRoot((X509Certificate) fact.generateCertificate(isRoot));
            ks.setCertificateEntry("caRoot", pki.getCaRoot());
            tmf.init(ks);
            log.info("found ca_root_cert.pem, will use ssl to broker");
        } catch (Exception ex) {
            log.error("problem loading server crypto, will skip any crypto setup", ex);
            pki.setCaRoot(null);
            return pki;
        }

        if (clientPubCert.exists() && clientPrivKey.exists()) {
            try (FileInputStream isClient = new FileInputStream(clientPubCert);
                 DataInputStream is = new DataInputStream(new FileInputStream(clientPrivKey))) {

                kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
                pki.setClientPublic((X509Certificate) fact.generateCertificate(isClient));
                byte[] keyBytes = new byte[(int) clientPrivKey.length()];
                is.readFully(keyBytes);

                PKCS8EncodedKeySpec spec = new PKCS8EncodedKeySpec(keyBytes);
                KeyFactory kf = KeyFactory.getInstance("RSA");
                pki.setClientPrivate(kf.generatePrivate(spec));
                ks.setKeyEntry("client", pki.getClientPrivate(), "bwgkey".toCharArray(), new Certificate[]{pki.getClientPublic()});
                kmf.init(ks, "bwgkey".toCharArray());
                log.info("found client cert and client key files, will submit as client in ssl handshake");
            } catch (Exception ex) {
                pki.setClientPublic(null);
                pki.setClientPrivate(null);
                kmf = null;
                log.error("problem loading client crypto, will skip", ex);
            }
        }

        try {
            pki.setSslContext(SSLContext.getInstance("TLS"));
            pki.getSslContext().init(kmf != null ? kmf.getKeyManagers() : null, tmf.getTrustManagers(), new java.security.SecureRandom());
        } catch (Exception ex) {
            log.error("unable to create SSL Context", ex);
            pki.setSslContext(null);
            pki.setCaRoot(null);
            pki.setClientPublic(null);
            pki.setClientPrivate(null);
        }
        return pki;
    }

    public String getSwUpgradeUrl() {
        return swUpgradeUrl;
    }

    @Bean
    public PushNotificationService configurePushNotificationService() {
        final PushNotificationService pushNotificationService = new PushNotificationService();
        pushNotificationService.setApnsSenderBuilder(
                new NotnoopApnsSenderBuilder()
                        .setCertificateResourceLocation(apnsCertPath)
                        .setCertificatePassword(apnsCertPassword)
                        .setUseProductionApnsServer(apnsUseProduction));
        return pushNotificationService;
    }
}
