package org.corfudb.infrastructure.configmaster.policies;

import org.corfudb.runtime.exceptions.NetworkException;
import org.corfudb.runtime.protocols.IServerProtocol;
import org.corfudb.runtime.protocols.logunits.IWriteOnceLogUnit;
import org.corfudb.runtime.protocols.sequencers.ISimpleSequencer;
import org.corfudb.runtime.view.CorfuDBView;
import org.corfudb.runtime.view.CorfuDBViewSegment;
import org.corfudb.runtime.view.Serializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Created by mwei on 5/14/15.
 */
public class SimpleReconfigurationPolicy implements IReconfigurationPolicy {

    private Logger log = LoggerFactory.getLogger(SimpleReconfigurationPolicy.class);

    @Override
    public CorfuDBView getNewView(CorfuDBView oldView, NetworkException e) {
        /* it's null, don't change anything */
        if (e == null) { return oldView; }
        /* Is the exception for a Logging Unit? */
        if (e.protocol instanceof IWriteOnceLogUnit)
        {
            /* Okay, so was it a read or a write? */
            if (e.write)
            {
                /* in the case of a write, find the segment belonging to the protocol,
                   and remove that protocol from the segment.
                 */
                CorfuDBView newView = (CorfuDBView) Serializer.copyShallow(oldView);

                for (CorfuDBViewSegment segment : newView.getSegments())
                {
                    for (List<IServerProtocol> nodeList : segment.getGroups())
                    {
                        if (nodeList.size() > 1) {
                            nodeList.removeIf(n -> n.getFullString().equals(e.protocol.getFullString()));
                        }
                    }
                }

                log.info("Reconfiguring all nodes in view to new epoch " + oldView.getEpoch() + 1);
                newView.moveAllToNewEpoch(oldView.getEpoch() + 1);
                return newView;
            }
            /* for reads, we don't do anything, for now...
             */
            log.warn("Reconfigure due to read, ignoring");
            return oldView;
        }
        else if (e.protocol instanceof ISimpleSequencer)
        {
            if (oldView.getSequencers().size() <= 1)
            {
                log.warn("Request reconfiguration of sequencers but there is no fail-over available! [available sequencers=" + oldView.getSequencers().size() + "]");
                return oldView;
            }
            else
            {
                CorfuDBView newView = (CorfuDBView) Serializer.copyShallow(oldView);
                newView.moveAllToNewEpoch(oldView.getEpoch() + 1);

                /* Interrogate each log unit to figure out last issued token */
                long last = -1;
                for (CorfuDBViewSegment segment : newView.getSegments())
                {
                    int groupNum = 1;
                    for (List<IServerProtocol> nodeList : segment.getGroups()) {
                        for (IServerProtocol n : nodeList) {
                                try {
                                    last = Long.max(last, ((IWriteOnceLogUnit) n).highestAddress() * segment.getGroups().size() + groupNum);
                                } catch (NetworkException ne) {

                                }
                        }
                        groupNum++;
                    }
                }

                log.warn("Removing sequencer " + e.protocol.getFullString() + " from configuration, discover last sequence was " + last);
                newView.getSequencers().removeIf(n -> n.getFullString().equals(e.protocol.getFullString()));
                try {
                    ((ISimpleSequencer) newView.getSequencers().get(0)).recover(last);
                } catch (Exception ex){
                    log.warn("Tried to install recovered sequence from sequencer, but failed", e);
                }
                return newView;
            }
        }
        else
        {
            log.warn("Request reconfiguration for protocol we don't know how to reconfigure", e.protocol);
            return (CorfuDBView) Serializer.copyShallow(oldView);
        }
    }
}
