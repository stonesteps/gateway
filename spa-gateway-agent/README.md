#BWG Spa Gateway Java Agent
The Java agent provides a messaging platform which runs on any
device that supports Java 8. The agent allows the device to interact with
MQTT by sending and receiving messages encoded
in a Google Protocol Buffers format that follows the Spa Gateway IDL encoding(refer to spa-gateway-idl project)


##Dev environment note, you must have a MQTT broker available and configured
Install a local MQTT broker on your machine, mosquitto is good, quick install for Mac/homebrew - http://mosquitto.org/download/


enable mosquitto as service:

ln -sfv /usr/local/opt/mosquitto/*.plist ~/Library/LaunchAgents

launchctl load ~/Library/LaunchAgents/homebrew.mxcl.mosquitto.plist

run mosquitton now:

launchctl [start|stop] homebrew.mxcl.mosquitto


##Agent Usage Example
create a directory called 'gateway_agent'
copy config/config.properties, config/logback.xml into 'gateway_agent' directory
     
     
If you plan to run the agent on target platform(armv7 processor) with a connection to a real spa rs485:
copy lib/armv7/libdio.so and config/dio.policy to 'gateway_agent' directory also. This is not required 
if you plan to run the MockProcessor.


If you want to just simulate mock data and are not connecting to a real Spa controller,
then in config.properties, make sure to specify:
command.processor.classname=com.tritonsvc.gateway.MockProcessor

If you need to connect to a mqtt broker that is on ssl and requires client certificates.
then copy ca_root_cert.pem(the public ca root cert that broker's server cert is signed with) and custom key pair files 
for this gateway instance as a client(gateway_cert.pem and gateway_key.pkcs8) to 'gateway_agent' directory also.


Optional - If wanting to run agent from IDE, define run/debug launch config for AgentLoader.java and set Program Arguments to have one argument
which should be set to the 'gateway_agent' directory specified as full path, now execute AgentLoader.


Using build: 
run mvn clean install from 'spa' directory


this will create spa/spa-gateway-agent/target/bwg-gateway-agent


To kick off the agent as configured for MockProcessor from 'spa' directory:
java -jar spa-gateway-agent/target/bwg-gateway-agent <gateway_agent directory path>


To kick off the agent as configured for BWGProcessor, which will attempt to make real rs485 connection on 
serial port configured in config.properties(requires running on linux with armv7 processor):
./bwg-gateway-agent [start|stop|status]

You should see log activity in 'gateway_agent'/logs/bwg.log. The MockProcessor will push
a gateway and controller registration message onto MQTT topic 'BWG/spa/uplink' and later some
fake pre/post water temperature readings once every 5 minutes.

If you're using raspberry pi 2 as dev board with linksprite rs485 shield, you need to take extra steps to free up the serial
port /dev/ttyAMA0 on linux, follow instructions here:

http://store.linksprite.com/rs485-gpio-shield-v3-for-raspberry-pi-b-b-and-raspberry-pi-2/

If your using linksprite rs485 shield with raspberry pi 3, you need to use /dev/ttyS0 as the linux serial port,
in agent's config.properties file, change rs485.port=ttyS0, and then then add 
core_freq=250 in /boot/config.txt - https://frillip.com/raspberry-pi-3-uart-baud-rate-workaround/

Here's how to create PKI/Certificates which the MQTT broker requires. 
 
create self signed ca root(don't redo this, use the existing test ca root in src/test/resources)
openssl genrsa -out ca_root_key.pem 2048
openssl req -x509 -new -nodes -key ca_root_key.pem -sha256 -days 18250 -out ca_root_cert.pem

mqtt broker(used for server ssl)
openssl genrsa -out broker_key.pem 2048
openssl req -new -key broker_key.pem -out broker.csr
openssl x509 -req -in broker.csr -CA ca_root_cert.pem -CAkey ca_root_key.pem -CAcreateserial -out broker_cert.pem -days 18250 -sha256

gateways and message-processor(any mqtt clients)
openssl genrsa -out gateway_key.pem 2048
openssl req -new -key gateway_key.pem -out gateway.csr
openssl x509 -req -in gateway.csr -CA ca_root_cert.pem -CAkey ca_root_key.pem -CAcreateserial -out gateway_cert.pem -days 18250 -sha256
openssl pkcs8 -topk8 -inform PEM -outform DER -in gateway_key.pem -out gateway_key.pkcs8 -nocrypt

for whatever reason, if you want to package up a gateway or broker cert(clients) and the trusted ca root into a java key store: 
Create PKCS12 keystore from private key and public certificate.
openssl pkcs12 -export -name brokercert -in broker_cert.pem -inkey broker_key.pem -out broker_keystore.p12
Convert PKCS12 keystore into a JKS keystore
keytool -importkeystore -destkeystore broker_ssl.jks -srckeystore broker_keystore.p12 -srcstoretype pkcs12 -alias brokercert
keytool -import -trustcacerts -alias root -file ca_root.cert.pem -keystore broker_ssl.jks

If you ever want to create a self-signed 'non-root' cert, here's the way:
openssl req -new -key my_key.pem -out my.csr
openssl x509 -req -days 16000 -in my.csr -signkey my_key.pem -out my_cert.pem





    

