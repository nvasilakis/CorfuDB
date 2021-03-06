package org.corfudb.runtime.smr;

import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.corfudb.runtime.exceptions.OverwriteException;
import org.corfudb.runtime.entries.IStreamEntry;
import org.corfudb.runtime.smr.HoleFillingPolicy.IHoleFillingPolicy;
import org.corfudb.runtime.smr.HoleFillingPolicy.TimeoutHoleFillPolicy;
import org.corfudb.runtime.smr.smrprotocol.SMRCommand;
import org.corfudb.runtime.stream.IStream;
import org.corfudb.runtime.stream.ITimestamp;
import org.corfudb.runtime.view.ICorfuDBInstance;
import org.corfudb.runtime.view.IStreamAddressSpace;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Created by mwei on 5/1/15.
 */
@Slf4j
public class SimpleSMREngine<T> implements ISMREngine<T> {

    IStream stream;
    T underlyingObject;

    @Getter
    public ITimestamp streamPointer;

    @Getter
    ITimestamp lastProposal;

    Class<T> type;
    final ConcurrentHashMap<ITimestamp, CompletableFuture> completionTable = new ConcurrentHashMap<ITimestamp, CompletableFuture>();
    HashSet<ITimestamp> localTable;
    Map<UUID, IBufferedSMREngine> cachedEngines = Collections.synchronizedMap(new WeakHashMap<>());

    @Getter
    @Setter
    ICorfuDBObject implementingObject;

    @Setter
    @Getter
    transient IHoleFillingPolicy holePolicy = new TimeoutHoleFillPolicy();

    class SimpleSMREngineOptions<Y extends T> implements ISMREngineOptions<Y>
    {
        public ICorfuDBInstance getInstance() { return stream.getInstance(); }

        @Override
        public UUID getEngineID() {
            return stream.getStreamID();
        }

        @Override
        public void setUnderlyingObject(Y object) {
            underlyingObject = object;
        }
    }

    public SimpleSMREngine(IStream stream, Class<T> type, Class<?>... args)
    {
        try {
            this.stream = stream;
            this.type = type;
            if (!ITimestamp.isMin(stream.getCurrentPosition()))
            {
                throw new RuntimeException(
                        "Attempt to start SMR engine on a stream which is not at the beginning (pos="
                                + stream.getCurrentPosition() + ")");
            }
            streamPointer = stream.getCurrentPosition();
            localTable = new HashSet<ITimestamp>();

            underlyingObject = type
                    .getConstructor(Arrays.stream(args)
                            .map(Class::getClass)
                            .toArray(Class[]::new))
                    .newInstance(args);
        }
        catch (Exception e)
        {
            throw new RuntimeException(e);
        }
    }

    /**
     * Get the underlying object. The object is dynamically created by the SMR engine.
     *
     * @return The object maintained by the SMR engine.
     */
    @Override
    public T getObject() {
        return underlyingObject;
    }

    /**
     * Set the underlying object. This method should ONLY be used by a TX engine to
     * restore state.
     *
     * @param object
     */
    @Override
    public void setObject(T object) {
        underlyingObject = object;
    }

    public volatile ITimestamp lastApplied = ITimestamp.getMinTimestamp();
    public PriorityQueue<IStreamEntry> applyQueue = new PriorityQueue<IStreamEntry>(
            //sort by logical timestamp.
            (IStreamEntry x, IStreamEntry y) -> x.getLogicalTimestamp().compareTo(y.getLogicalTimestamp())
    );

    public <R> void apply(IStreamEntry entry)
    {
        try {
            log.trace("LearnApply[{}/{}]: Apply", entry.getTimestamp(), entry.getLogicalTimestamp());
            if (entry.getPayload() != null && entry.getPayload() instanceof SMRCommand) {
                SMRCommand command = (SMRCommand) entry.getPayload();
                command.setInstance(getInstance());
                ITimestamp entryTS = entry.getTimestamp();
                CompletableFuture<R> completion = completionTable.get(entryTS);
                completionTable.remove(entryTS);
                R r = (R) command.execute(underlyingObject, this, entryTS);
                if (completion != null) {
                    log.trace("LearnApply[{}/{}]: Completing Future.", entry.getTimestamp(), entry.getLogicalTimestamp());
                    completion.complete(r);
                }
            }
            lastApplied = entry.getLogicalTimestamp();
        }
        catch (Exception e)
        {
            log.error("LearnApply[{}/{}]: Error during apply of entry!", e);
        }
    }

    public synchronized void learnAndApply(IStreamEntry entry)
    {
        if(ITimestamp.isMin(lastApplied) && stream.getNextTimestamp(lastApplied).equals(entry.getLogicalTimestamp()))
        {
            apply(entry);
        }
        else
        {
            log.trace("LearnApply[{}/{}]: Enqueued, Previous={}, Next={}", entry.getTimestamp(), entry.getLogicalTimestamp(), lastApplied, stream.getNextTimestamp(lastApplied));
            applyQueue.offer(entry);
        }
        while (applyQueue.peek() != null && applyQueue.peek().getLogicalTimestamp().equals(stream.getNextTimestamp(lastApplied)))
        {
            apply(applyQueue.poll());
        }
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
        if (ts == null) {
            stream.checkAsync()
                    .thenApplyAsync(t -> {
                        log.trace("Sync to most recent @ {}", t);
                        return stream.readToAsync(t).thenApplyAsync(entryArray -> {
                                    if (entryArray != null) {
                                        Arrays.stream(entryArray)
                                                .forEach(this::learnAndApply);
                                    }
                                    return t;
                                });
                    }).join();
        }
        else
        {
            log.trace("Sync to {}", ts);
            stream.readToAsync(stream.getNextTimestamp(ts))
                    .thenApplyAsync(entryArray -> {
                        if (entryArray != null) {
                            Arrays.stream(entryArray)
                                    .forEach(this::learnAndApply);
                        }
                        return ts;
                    }
                    ).join();
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

    /**
     * Propose a new command to the SMR engine.
     *
     * @param command       A lambda (BiConsumer) representing the command to be proposed.
     *                      The first argument of the lambda is the object the engine is acting on.
     *                      The second argument of the lambda contains some TX that the engine
     *                      The lambda must be serializable.
     *
     * @param completion    A completable future which will be fulfilled once the command is proposed,
     *                      which is to be completed by the command.
     *
     * @param readOnly      Whether or not the command is read only.
     *
     * @return              The timestamp the command was proposed at.
     */
    @Override
    public <R> ITimestamp propose(SMRCommand<T,R> command, CompletableFuture<R> completion, boolean readOnly) {
        if (readOnly)
        {
            R r = command.execute(underlyingObject, this, streamPointer);
            if (completion != null) {
                completion.complete(r);
            }
            return streamPointer;
        }
        try {
            //ITimestamp t = stream.append(command);
            //if (completion != null) { completionTable.put(t, completion); }
            ITimestamp t = stream.reserve(1)[0];
                if (completion != null) {
                    completionTable.put(t, completion);
                }
            stream.write(t, command);
            lastProposal = t; //TODO: fix thread safety?
            return t;
        }
        catch (OverwriteException oe)
        {
            log.warn("Warning, propose resulted in overwrite @ {}, reproposing.", oe.address);
            return propose(command, completion, readOnly);
        }
        catch (Exception e)
        {
            log.warn("Exception proposing new command!", e);
            return null;
        }
    }

    @Override
    public <R> CompletableFuture<ITimestamp> proposeAsync(SMRCommand<T,R> command, CompletableFuture<R> completion, boolean readOnly) {

        if (readOnly)
        {
            /* TODO: pretty sure we need some kind of locking here (what if the object changes during a read?) */
            R result = command.execute(underlyingObject, this, streamPointer);
            if (completion != null)
            {
                completion.complete(result);
            }
            log.trace("Read-only proposal completed at {}", streamPointer);
            return CompletableFuture.completedFuture(streamPointer);
        }

        final ITimestamp[] proposalTimestamp = new ITimestamp[1];
        return stream.reserveAsync(1)
                .thenApply(
                        t -> {
                            log.trace("Proposal[{}]: Acquired token", t[0]);
                            if (completion != null) {
                                log.trace("Proposal[{}]: Inserted into completion table.", t[0]);
                                completionTable.put(t[0], completion);
                            }
                            try {
                                log.trace("Proposal[{}]: Writing proposal to stream", t[0]);
                                proposalTimestamp[0] = t[0];
                                return stream.writeAsync(t[0], command);
                            } catch (Exception e) {
                                log.error("Exception during write.", e);
                                throw new RuntimeException(e);
                            }
                        }
                )
                .thenApply( r -> {
                    log.trace("Proposal[{}]: Wrote proposal to stream", proposalTimestamp[0]);
                    lastProposal = proposalTimestamp[0]; //TODO: Should be max.
                    return proposalTimestamp[0];
                });
    }

    /**
     * Propose a local command to the SMR engine. A local command is one which is executed locally
     * only, but may propose other commands which affect multiple objects.
     *
     * @param command    A lambda representing the command to be proposed
     * @param completion A completion to be fulfilled.
     * @param readOnly   True, if the command is read only, false otherwise.
     * @return A timestamp representing the command proposal time.
     */
    @Override
    public <R> ITimestamp propose(ISMRLocalCommand<T, R> command, CompletableFuture<R> completion, boolean readOnly) {
        if (readOnly) {
            R result = command.apply(underlyingObject, new SimpleSMREngineOptions());
            if (completion != null)
            {
                completion.complete(result);
            }
            return streamPointer;
        }
        try {
            ITimestamp[] t = stream.reserve(2);
            localTable.add(t[0]);
            if (completion != null) { completionTable.put(t[0], completion); }
            stream.write(t[0], new SMRLocalCommandWrapper<>(command, t[1]));
            lastProposal = t[0];
            return t[0];
        }
        catch (Exception e)
        {
            //well, propose is technically not reliable, so we can just silently drop
            //any exceptions.
            log.warn("Exception in local command propose...", e);
            return null;
        }
    }

    /**
     * Checkpoint the current state of the SMR engine.
     *
     * @return The timestamp the checkpoint was inserted at.
     */
    @Override
    public ITimestamp checkpoint()
        throws IOException
    {
        SMRCheckpoint<T> checkpoint = new SMRCheckpoint<T>(streamPointer, underlyingObject);
        return stream.append(checkpoint);
    }


    /**
     * Pass through to check for the underlying stream.
     *
     * @return A timestamp representing the most recently proposed command on a stream.
     */
    @Override
    public ITimestamp check() {
        return stream.check();
    }

    /**
     * Get the underlying stream ID.
     *
     * @return A UUID representing the ID for the underlying stream.
     */
    @Override
    public UUID getStreamID() {
        return stream.getStreamID();
    }

    /**
     * Get the CorfuDB instance that supports this SMR engine.
     *
     * @return A CorfuDB instance.
     */
    @Override
    public ICorfuDBInstance getInstance() {
        return stream.getInstance();
    }


    public Map<UUID, IBufferedSMREngine> getCachedEngines() {
        return cachedEngines;
    }

    public void addCachedEngine(UUID stream, IBufferedSMREngine engine) {
        cachedEngines.put(stream, engine);
    }
}
