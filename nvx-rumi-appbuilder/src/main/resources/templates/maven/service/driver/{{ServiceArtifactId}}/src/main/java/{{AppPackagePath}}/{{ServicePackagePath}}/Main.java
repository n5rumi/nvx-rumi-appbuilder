package {{AppPackageName}}.{{ServicePackageName}};

import java.util.concurrent.atomic.AtomicReference;

import com.neeve.aep.AepEngine;
import com.neeve.aep.AepMessageSender;
import com.neeve.cli.annotations.Argument;
import com.neeve.cli.annotations.Command;
import com.neeve.server.app.annotations.AppInjectionPoint;
import com.neeve.util.UtlGovernor;

import {{AppPackageName}}.roe.*;

public class Main {
    private class SendingThread extends Thread {
        private final int _count;
        private final int _rate;
        private volatile boolean _stopped;

        SendingThread(int count, int rate) {
            _count = count;
            _rate = rate; 
        }

        private void sendMessages(int count, int rate) {
            UtlGovernor sendGoverner = new UtlGovernor(rate);
            for (int i = 0 ; !_stopped && i < count ; i++) {
                // put code here to send a message

                // govern rate
                sendGoverner.blockToNext();
            }
        }

        void stopSending() {
            _stopped = true;
        }

        @Override
        public void run() {
            try {
                sendMessages(_count, _rate);
            }
            catch (Throwable e) {
                e.printStackTrace();
            }
            finally {
                _sendingThread.set(null);
            }
        }
    }

    private AepEngine _engine;
    private AepMessageSender _messageSender;
    private final AtomicReference<SendingThread> _sendingThread;

    public Main() {
        _sendingThread = new AtomicReference<SendingThread>();
    }

    @AppInjectionPoint
    final public void setEngine(AepEngine engine) {
        _engine = engine;
    }

    @AppInjectionPoint
    public void setMessageSender(AepMessageSender messageSender) {
        _messageSender = messageSender;
    }

    @Command(name = "start", displayName = "Send Messages", description = "Instructs the driver to send messages")
    public void start(@Argument(name = "count", position = 1, required = true, description = "The number of messages to send") int count,
                      @Argument(name = "rate", position = 2, required = true, description = "The rate at which to send") int rate) throws Exception {
        SendingThread sendingThread = new SendingThread(count, rate);
        if (_sendingThread.compareAndSet(null, sendingThread)) {
            sendingThread.start();
        }
        else {
            throw new IllegalStateException("send in progress");
        }
    }

    @Command(name = "stop", displayName = "Stop Sending", description = "Instructs the driver to stop sending messages.")
    final void stop() throws Exception {
        SendingThread sendingThread = _sendingThread.getAndSet(null);
        if (sendingThread != null) {
            sendingThread.stopSending();
        }
    }
}
