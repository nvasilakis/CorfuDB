package org.corfudb.infrastructure.wireprotocol;

import io.netty.buffer.ByteBuf;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.corfudb.infrastructure.NettyLogUnitServer;

import java.util.*;


/**
 * Created by mwei on 9/15/15.
 */
@Getter
@Setter
@NoArgsConstructor
public class NettyLogUnitWriteMsg extends NettyLogUnitPayloadMsg {


    /** The address to write to */
    long address;

    public NettyLogUnitWriteMsg(long address)
    {
        this.msgType = NettyCorfuMsgType.WRITE;
        this.address = address;
        this.metadataMap = new EnumMap<>(NettyLogUnitServer.LogUnitMetadataType.class);
    }


    /**
     * Serialize the message into the given bytebuffer.
     *
     * @param buffer The buffer to serialize to.
     */
    @Override
    @SuppressWarnings("unchecked")
    public void serialize(ByteBuf buffer) {
        super.serialize(buffer);
        buffer.writeLong(address);
    }

    /**
     * Parse the rest of the message from the buffer. Classes that extend NettyCorfuMsg
     * should parse their fields in this method.
     *
     * @param buffer
     */
    @Override
    public void fromBuffer(ByteBuf buffer) {
        super.fromBuffer(buffer);
        address = buffer.readLong();
    }
}
