package {{AppPackageName}}.{{ServicePackageName}}.connector;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.util.Properties;

import com.neeve.config.Config;
import com.neeve.sma.MessageView;
import com.neeve.sma.SmaPermanentException;
import com.neeve.sma.spi.connector.Connector;
import com.neeve.trace.Tracer;

import {{AppPackageName}}.roe.*;
import {{AppPackageName}}.{{ServicePackageName}}.messages.*;

public class Main implements Connector {
    private Tracer _tracer;
    private BufferedWriter _outputFileWriter;
    private BindingCallback _bindingCallback;
    private boolean _firstWrite;

    @Override
    final public void open(Properties config) throws Exception, SmaPermanentException {
        // get the _tracer
        _tracer = Tracer.get("{{AppTokenName}}.{{ServicePackageName}}");

        // open the output file
        final String fileLocation = Config.getValue("{{AppTokenName}}.{{ServicePackageName}}.file.location", null);
        if (fileLocation == null) {
            throw new SmaPermanentException("output file location has not been configured");
        }
        final File fileLocationFile = new File(fileLocation);
        if (!fileLocationFile.exists()) {
            fileLocationFile.mkdirs();
        }
        if (!fileLocationFile.isDirectory()) {
            throw new SmaPermanentException("the configured output file location '" + fileLocationFile.getAbsolutePath() + "' is not a directory");
        }
        final String fileName = Config.getValue("{{AppTokenName}}.{{ServicePackageName}}.file.name", "file.csv");
        final File file = new File(fileLocation, fileName);
        _firstWrite = !file.exists() || file.length() == 0;
        _outputFileWriter = new BufferedWriter(new FileWriter(file, true));
    }

    @Override
    final public void run(final BindingCallback callback) throws Exception {
        // store the binding callback
        _bindingCallback = callback;
    }

    @Override
    final public void processOutbound(final MessageView view, final OutboundAcknowledger acknowledger, int flags) throws Exception {
        // trace
        _tracer.log("<--" + view.toString(), Tracer.Level.VERBOSE);

        // process
        try {
            if (view instanceof FlushMessage) {
                _outputFileWriter.flush();
            }
        }
        finally {
            acknowledger.acknowledge();
        }
    }

    @Override
    final public void close() throws Exception {
        _outputFileWriter.close();
    }
}
