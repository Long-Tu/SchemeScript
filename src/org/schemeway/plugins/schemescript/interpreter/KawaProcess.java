/*
 * Copyright (c) 2004 Nu Echo Inc.
 * 
 * This is free software. For terms and warranty disclaimer, see ./COPYING
 */
package org.schemeway.plugins.schemescript.interpreter;

import gnu.expr.Compilation;
import gnu.expr.ModuleExp;
import gnu.kawa.functions.DisplayFormat;
import gnu.lists.Consumer;
import gnu.mapping.CallContext;
import gnu.mapping.CharArrayInPort;
import gnu.mapping.Environment;
import gnu.mapping.InPort;
import gnu.mapping.OutPort;
import gnu.mapping.WrongArguments;
import gnu.text.Lexer;
import gnu.text.SourceMessages;

import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.util.HashMap;
import java.util.Map;

import kawa.standard.Scheme;

import org.eclipse.debug.core.DebugException;
import org.eclipse.debug.core.ILaunch;
import org.eclipse.debug.core.model.IStreamMonitor;
import org.eclipse.debug.core.model.IStreamsProxy;
import org.schemeway.plugins.schemescript.views.KawaStackTraceView;

public class KawaProcess implements IInterpreterProcess {
    private static class KawaStreamsProxy implements IStreamsProxy {

        private MonitoredOutputStream mErrorMonitor = new MonitoredOutputStream();
        private MonitoredOutputStream mOutputMonitor = new MonitoredOutputStream();

        public KawaStreamsProxy() {
            OutPort.setOutDefault(new OutPort(new OutputStreamWriter(mOutputMonitor), false, true));
            OutPort.outDefault().objectFormat = DisplayFormat.getSchemeFormat(true);
            OutPort.setErrDefault(new OutPort(new OutputStreamWriter(mErrorMonitor), false, true));
        }

        public IStreamMonitor getErrorStreamMonitor() {
            return mErrorMonitor;
        }

        public IStreamMonitor getOutputStreamMonitor() {
            return mOutputMonitor;
        }

        public void write(final String input) throws IOException {
            final OutPort out = OutPort.outDefault();
            final OutPort err = OutPort.errDefault();
            out.setColumnNumber(0);
            KawaProxy.runInSchemeThread(new Runnable() {
            	public void run () {
                    KawaProcess.eval(Scheme.getInstance(), new CharArrayInPort(input), out, err);
            	}
            });
            if (out.getColumnNumber() != 0) {
                out.freshLine();
            }
            out.write(MonitoredOutputStream.PROMPT);
            out.flush();
        }
    }

    private Map mAttributes = new HashMap();
    private ILaunch mLaunch;
    private static KawaProcess mInstance = null;
    private static KawaStreamsProxy mProxyInstance = new KawaStreamsProxy();

    private KawaProcess() {
        super();
    }

    public String getLabel() {
        return "Embedded Kawa";
    }

    public ILaunch getLaunch() {
        return mLaunch;
    }

    public void setLaunch(ILaunch launch) {
        mLaunch = launch;
    }

    public IStreamsProxy getStreamsProxy() {
        return mProxyInstance;
    }

    public void setAttribute(String key, String value) {
        mAttributes.put(key, value);
    }

    public String getAttribute(String key) {
        return (String) mAttributes.get(key);
    }

    public int getExitValue() throws DebugException {
        return 0;
    }

    public Object getAdapter(Class adapter) {
        return null;
    }

    public boolean isRunning() {
        return getLaunch() != null;
    }
    
    public boolean canTerminate() {
        return false;
    }

    public boolean isTerminated() {
        return false;
    }

    public void terminate() throws DebugException {
    }

    static KawaProcess getInstance() {
        if (mInstance == null) {
            mInstance = new KawaProcess();
        }
        return mInstance;
    }

    public void sendToInterpreter(String text) {
        try {
            getInstance().getStreamsProxy().write(text);
        }
        catch (IOException exception) {
            // should not happen!
        }
    }

    static private int mCounter = 0;

    // Code adapter from Kawa.Shell
    static void eval(Scheme interp, InPort inp, OutPort out, OutPort perr) {
        Environment env = Environment.getCurrent();
        SourceMessages messages = new SourceMessages();
        Lexer lexer = interp.getLexer(inp, messages);
        lexer.setInteractive(false);
        CallContext ctx = CallContext.getInstance();
        Consumer saveConsumer = null;
        if (out != null) {
            saveConsumer = ctx.consumer;
            ctx.consumer = out;
        }
        try {
            int opts = gnu.expr.Language.PARSE_IMMEDIATE;
            try {
                Compilation comp = interp.parse(lexer, opts);
                boolean sawError = messages.checkErrors(perr, 20);
                if (comp == null) // ??? end-of-file
                    return; // break;
                if (sawError)
                    return; // continue;
                comp.getModule().setName("atInteractiveLevel$" + (++mCounter));

                ModuleExp.evalModule(env, ctx, comp);
                if (messages.checkErrors(perr, 20))
                    return;
                ctx.runUntilDone();
            }
            catch (WrongArguments e) {
                showError(e, perr);
                return;
            }
            catch (java.lang.ClassCastException e) {
                showError(e, perr);
                return;
            }
            catch (gnu.text.SyntaxException e) {
                showError(e, perr, "Syntax error!");
                return;
            }
            catch (java.io.IOException e) {
                showError(e, perr, "IO error!");
                return;
            }
            catch (Throwable e) {
                showError(e, perr);
                return;
            }
        }
        finally {
            if (out != null)
                ctx.consumer = saveConsumer;
        }
    }

    static void showError(Throwable exception, PrintWriter perr) {
        showError(exception, perr, "Error!");
    }

    static void showError(Throwable exception, PrintWriter perr, String dialogTitle) {
        KawaStackTraceView.logException(exception);
    }
}