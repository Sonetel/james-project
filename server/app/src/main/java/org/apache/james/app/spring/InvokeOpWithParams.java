package org.apache.james.app.spring;

import java.io.IOException;

import javax.management.MBeanServerConnection;
import javax.management.ObjectName;
import javax.management.remote.JMXConnector;
import javax.management.remote.JMXConnectorFactory;
import javax.management.remote.JMXServiceURL;

public class InvokeOpWithParams {
    public static void main(String[] args) throws IOException {
        JMXConnector jmxc = null;
        try { //  Create administration connection factory
            JMXServiceURL url =
                    new JMXServiceURL("service:jmx:rmi:///jndi/rmi://127.0.0.1:9999/jmxrmi");
            jmxc = JMXConnectorFactory.connect(url, null);

            //  Get MBean server connection
            MBeanServerConnection mbsc = jmxc.getMBeanServerConnection();

            //  Create object name
            ObjectName destMgrConfigName
                    = new ObjectName("org.apache.james:type=component,name=fetchmail");

            //  Create and populate attribute list

            for (int i = 5; i <= 8; i++) {
                mbsc.invoke(destMgrConfigName, "addFetchMailConfig", new Object[]{"localhost", "b" + i + "@localhost.com", "pass"}, new String[]{String.class.getName(), String.class.getName(), String.class.getName()});
            }
            //  Invoke operation

            //  Close JMX connector

        } catch (Exception e) {
            System.out.println("Exception occurred: " + e.toString());
            e.printStackTrace();
        } finally {
            if (jmxc != null) {
                jmxc.close();
            }
        }
    }
}