#Spa Gateway Message Processor
This module is responsible for pulling pending SpaCommands from MongoDB and transforming them into 
gateway IDL messages useing spa-gateway-idl and publishing them onto MQTT. Conversely, this module also subscribes to the uplink topic
on MQTT, and transforms the gateway IDL messages on uplink into MongoDB documents using spa-model.

invoking from commandline with client side ssl cert:
java -DmqttHostname=localhost -DcaRootFilePem=/Users/shawn/openssl/ca_root_cert.pem \
-DclientCertFilePem=/Users/shawn/openssl/gateway_cert.pem \
-DclientKeyFilePkcs8=/Users/shawn/openssl/gateway_key.pkcs8 \
-jar spa-gateway-messageprocessor-0.0.1-SNAPSHOT.jar




