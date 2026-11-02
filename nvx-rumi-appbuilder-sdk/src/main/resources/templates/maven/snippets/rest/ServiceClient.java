package {{WebPackageName}}.clients;

/**
 * The generated client this web service uses to call the
 * {@code {{TargetServiceName}}} service. App Builder created it with the first
 * REST endpoint that calls that service, and removes it with the last.
 *
 * <p>One client is shared by every endpoint that calls the service. It is opened
 * on first use and closed when the JVM exits. Its connection comes from the
 * configuration key
 * {@code {{TargetServiceName}}.client.{{ClientName}}.connectionDescriptor}, which
 * must reach the bus that service declares as
 * {@code {{TargetServiceName}}_client_bus}.
 */
public final class {{ClientClassName}} extends {{TargetPackageName}}.AbstractClient {
    public static final String SERVICE_NAME = "{{TargetServiceName}}";
    public static final String CLIENT_NAME = "{{ClientName}}";

    private static {{ClientClassName}} instance;

    private {{ClientClassName}}() {
        // a unique instance id per JVM, so replies reach this process only; the
        // configuration is read at the client level and applies to every instance
        super(SERVICE_NAME, CLIENT_NAME, java.util.UUID.randomUUID().toString());
    }

    public static synchronized {{ClientClassName}} get() {
        if (instance == null) {
            final {{ClientClassName}} client = new {{ClientClassName}}();
            client.open();
            Runtime.getRuntime().addShutdownHook(new Thread(client::close, CLIENT_NAME + "->" + SERVICE_NAME));
            instance = client;
        }
        return instance;
    }
}
