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


Optional - If wanting to run agent from IDE, define run/debug launch config for AgentLoader.java and set Program Arguments to have one argument
which should be set to the 'gateway_agent' directory specified as full path, now execute AgentLoader.


Using standalone built jar: 
run mvn clean install from 'spa' directory


this will create spa/spa-gateway-agent/target/spa-gateway-agent-0.0.1-SNAPSHOT.jar


To kick off the agent as configured for MockProcessor from 'spa' directory:
java -jar spa-gateway-agent/target/spa-gateway-agent-0.0.1-SNAPSHOT.jar <gateway_agent directory path>


To kick off the agent as configured for BWGProcessor from 'spa' directory, which will attempt to make real rs485 connection on 
serial port configured in config.properties(requires armv7 processor):
java -Djava.library.path=<gateway_agent directory path> -Djava.security.policy=<gateway_agent directory path>/dio.policy -jar spa-gateway-agent-0.0.1-SNAPSHOT.jar


You should see log activity in 'gateway_agent'/logs/bwg.log. The MockProcessor will push
a gateway and controller registration message onto MQTT topic 'BWG/spa/uplink' and later some
fake pre/post water temperature readings once every 5 minutes.

    

