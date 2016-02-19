#Spa Gateway Message Processor
This module is responsible for pulling pending SpaCommands from MongoDB and transforming them into 
gateway IDL messages useing spa-gateway-idl and publishing them onto MQTT. Conversely, this module also subscribes to the uplink topic
on MQTT, and transforms the gateway IDL messages on uplink into MongoDB documents using spa-model.




