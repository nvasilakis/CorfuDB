package org.corfudb.runtime.smr;

import org.corfudb.runtime.stream.IStream;
import org.corfudb.runtime.stream.ITimestamp;

/**
 * Created by mwei on 5/6/15.
 */
public class TimeTravelSMREngine<T> extends SimpleSMREngine<T> {

    ITimestamp lockTS;

    public TimeTravelSMREngine(IStream stream, Class<T> type, Class<?>... initArgs)
    {
        super(stream, type, initArgs);
    }

    /**
     * Synchronize the SMR engine to a given timestamp, or pass null to synchronize
     * the SMR engine as far as possible.
     *
     * @param ts The timestamp to synchronize to, or null, to synchronize to the most
     *           recent version.
     */
    @Override
    @SuppressWarnings("unchecked")
    public <R> void sync(ITimestamp ts) {
        if (this.lockTS != null)
        {
            return;
        }

        super.sync(ts);
    }

    @SuppressWarnings("unchecked")
    public void travelAndLock(ITimestamp lockTS)
    {
        this.lockTS = lockTS;

        synchronized (this)
        {
            // Reverse operations on the object until we get
            // to the desired timestamp.

            if (streamPointer.compareTo(lockTS) == 0) return;
            if (streamPointer.compareTo(lockTS) < 0)
            {
                while (streamPointer.compareTo(lockTS) < 0)
                {
                    streamPointer = stream.getNextTimestamp(streamPointer);
                    try {
                            ISMREngineCommand o = (ISMREngineCommand) stream.readObject(streamPointer);
                            o.apply(underlyingObject, new SimpleSMREngineOptions());
                    } catch (Exception e)
                    {

                    }
                }
            }
            else {
                while (streamPointer.compareTo(lockTS) > 0) {
                    // Can we reverse this operation?
                    try {
                        Object o = stream.readObject(streamPointer);
                        if (o instanceof ReversibleSMREngineCommand) {
                            ReversibleSMREngineCommand c = (ReversibleSMREngineCommand) o;
                            c.reverse(underlyingObject, new SimpleSMREngineOptions());
                        }
                        //this operation is non reversible, so unfortunately we have to play from the beginning...
                        OneShotSMREngine<T> smrOS = new OneShotSMREngine<T>(stream.getInstance().openStream(stream.getStreamID()), type, streamPointer);
                        smrOS.sync(streamPointer);
                        underlyingObject = smrOS.getObject();
                    } catch (Exception e) {

                    }
                    streamPointer = stream.getPreviousTimestamp(streamPointer);
                }
            }
        }
    }

    /**
     * Execute a read only command against this engine.
     *
     * @param command The command to execute. It must be read only.
     * @return The return value.
     */
    @Override
    public <R> R read(ISMREngineCommand<T, R> command) {
        return command.apply(underlyingObject, new SimpleSMREngineOptions<>());
    }

    public void unlock(ITimestamp ts)
    {
        this.lockTS = null;
    }
}
