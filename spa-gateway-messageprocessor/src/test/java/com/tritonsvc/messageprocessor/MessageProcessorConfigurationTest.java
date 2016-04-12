package com.tritonsvc.messageprocessor;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

import static org.hamcrest.MatcherAssert.assertThat;

public class MessageProcessorConfigurationTest {

    private MessageProcessorConfiguration config;

    @Rule
    public TemporaryFolder folder= new TemporaryFolder();

    @Before
    public void setUp() {
        config = new MessageProcessorConfiguration();
    }

    @Test
    public void itHandlesCARoot() throws Exception {
        Files.copy(MessageProcessorConfigurationTest.class.getResourceAsStream("/ca_root_cert.pem"), folder.newFile("ca_root_cert.pem").toPath(), StandardCopyOption.REPLACE_EXISTING);
        ReflectionTestUtils.setField(config, "caRootFilePem",new File(folder.getRoot(), "ca_root_cert.pem").getAbsolutePath());
        ReflectionTestUtils.setField(config, "clientCertFilePem","nothing");
        ReflectionTestUtils.setField(config, "clientKeyFilePkcs8","nothing");

        PkiInfo pki = config.obtainPKIArtifacts();
        assertThat("cert dn not obtained correctly", pki.getCaRoot().getSubjectDN().getName().equals("CN=test ca, O=Test CA, L=San Diego, ST=CA, C=US"));
    }

    @Test
    public void itHandlesClientCert() throws Exception {
        Files.copy(MessageProcessorConfigurationTest.class.getResourceAsStream("/ca_root_cert.pem"), folder.newFile("ca_root_cert.pem").toPath(), StandardCopyOption.REPLACE_EXISTING);
        Files.copy(MessageProcessorConfigurationTest.class.getResourceAsStream("/mprocessor_cert.pem"), folder.newFile("mprocessor_cert.pem").toPath(), StandardCopyOption.REPLACE_EXISTING);
        Files.copy(MessageProcessorConfigurationTest.class.getResourceAsStream("/mprocessor_key.pkcs8"), folder.newFile("mprocessor_key.pkcs8").toPath(), StandardCopyOption.REPLACE_EXISTING);

        ReflectionTestUtils.setField(config, "caRootFilePem",new File(folder.getRoot(), "ca_root_cert.pem").getAbsolutePath());
        ReflectionTestUtils.setField(config, "clientCertFilePem",new File(folder.getRoot(), "mprocessor_cert.pem").getAbsolutePath());
        ReflectionTestUtils.setField(config, "clientKeyFilePkcs8",new File(folder.getRoot(), "mprocessor_key.pkcs8").getAbsolutePath());

        PkiInfo pki = config.obtainPKIArtifacts();
        assertThat("cert dn not obtained correctly", pki.getCaRoot().getSubjectDN().getName().equals("CN=test ca, O=Test CA, L=San Diego, ST=CA, C=US"));
        assertThat("cert dn not obtained correctly", pki.getClientPublic().getSubjectDN().getName().equals("CN=test1, OU=unit_test, O=test, L=san diego, ST=CA, C=US"));
    }
}
