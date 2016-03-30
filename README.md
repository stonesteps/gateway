#BWG Spa
This is the top level folder for bwg spa projects, with the following 
sub projects:

#spa-gateway-idl: This project builds the java binding to the gateway
IDL. The IDL describes all messages that are exchanged between the cloud 
and the gateway. Refer to src/main/proto/bwg.proto for the Google Protocol
Buffers representation of the IDL.

#spa-gateway-agent: This project represents the Agent process that runs
on the gateway board. It pulls all downlink messages from the MQTT broker in 
cloud that are addrressed for a gateway, and also publishes to MQTT all data
that is collected on the spa system and it's WSN. Refer to spa-gateway-agent\README.md
for additional details on how to deploy/run the Agent.

##Requirements
Maven 3.3 and Java 8 is required to build these projects.

##To build all:
from this directory:
mvn clean install

#Testing
Unit test classes end with 'Test', suffix. Integration Tests end with 'IT' suffix.
Integration tests use both message processor and agent at same time with embedded
mqtt broker and fongo (fake mongo).

## to perform unit testing run from this directory:
mvn test

## to run integration tests
mvn failsafe:integration-test


