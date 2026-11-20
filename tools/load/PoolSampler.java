import com.sun.tools.attach.VirtualMachine;
import java.util.Set;
import javax.management.Attribute;
import javax.management.AttributeList;
import javax.management.ObjectName;
import javax.management.remote.JMXConnector;
import javax.management.remote.JMXConnectorFactory;
import javax.management.remote.JMXServiceURL;

/** Local diagnostic only. Never prints connector addresses, properties, or exception messages. */
public final class PoolSampler {
    private static final String[] ATTRIBUTES = {
        "ActiveConnections", "IdleConnections", "TotalConnections", "ThreadsAwaitingConnection"
    };

    public static void main(String[] args) {
        try {
            if (args.length != 1 || !args[0].matches("[1-9][0-9]*")) {
                throw new IllegalArgumentException();
            }
            String address;
            VirtualMachine vm = VirtualMachine.attach(args[0]);
            try {
                address = vm.startLocalManagementAgent();
            } finally {
                vm.detach();
            }
            JMXServiceURL url = new JMXServiceURL(address);
            if (!"rmi".equals(url.getProtocol()) ||
                !Set.of("127.0.0.1", "localhost", "::1", "[::1]").contains(url.getHost()) ||
                !url.getURLPath().startsWith("/stub/")) {
                throw new SecurityException();
            }
            ObjectName pool = new ObjectName("com.zaxxer.hikari:type=Pool (roy-db)");
            try (JMXConnector connector = JMXConnectorFactory.connect(url)) {
                while (!Thread.currentThread().isInterrupted()) {
                    AttributeList values = connector.getMBeanServerConnection().getAttributes(pool, ATTRIBUTES);
                    if (values.size() != ATTRIBUTES.length) throw new IllegalStateException();
                    long[] gauges = new long[ATTRIBUTES.length];
                    for (int i = 0; i < ATTRIBUTES.length; i++) {
                        Attribute attribute = (Attribute) values.get(i);
                        if (!ATTRIBUTES[i].equals(attribute.getName()) || !(attribute.getValue() instanceof Number)) {
                            throw new IllegalStateException();
                        }
                        gauges[i] = ((Number) attribute.getValue()).longValue();
                        if (gauges[i] < 0) throw new IllegalStateException();
                    }
                    System.out.printf("{\"active\":%d,\"idle\":%d,\"total\":%d,\"pending\":%d}%n",
                        gauges[0], gauges[1], gauges[2], gauges[3]);
                    System.out.flush();
                    Thread.sleep(1000);
                }
            }
        } catch (Throwable failure) {
            System.out.println("{\"errorClass\":\"" + failure.getClass().getSimpleName() + "\"}");
            System.out.flush();
            System.exit(1);
        }
    }
}
