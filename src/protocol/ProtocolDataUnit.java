
package protocol;

import utils.Log;
import utils.OctetBuffer;

/**
 *  Represents a Protocol Data Unit (PDU).
 *
 *  <pre>
 *                       1                   2                   3
 *   0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1        Octets:
 *  +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+  +---------------+
 *  |1|     Source Call Number      |0|   Destination Call Number   |    0   1   2   3
 *  +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+  +---------------+
 *  |                           Time-Stamp                          |    4   5   6   7
 *  +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+  +---------------+
 *  |  Out Seq No   |   In Seq No   |0|  PDU Type   |   Sub Class   |    8   9  10  11
 *  +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+  +---------------+
 *  |                                                               |   12  ...
 *  :                            Payload                            :
 *  |                                                               |
 *  +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 *  </pre>
 *
 */
public abstract class ProtocolDataUnit 
{
    /** PDU Type: Data Audio Compression Format Raw Voice Data */
    protected final static int VOICE = 0x02;

    /** The call object */
    protected CallContext call;

    /** The payload data */
    protected OctetBuffer payload;
    
    /** The time-stamp */
    protected Long timeStamp;

    /** The source call number */
    protected int sourceCallNumber;

    /** The destination call number */
    protected int destinationCallNumber;

    /** The outbound stream sequence number */
    protected int outSeqNo;

    /** The inbound stream sequence number */
    protected int inSeqNo;

    /** The frame type */
    protected int pduType;
    
    /** The subclass */
    protected int pduSubclass;

    /**
     * The constructor for outbound PDUs.
     *
     * @param call  The Call object
     */
    public ProtocolDataUnit( CallContext call ) 
    {
        this.call = call;
        
        this.destinationCallNumber = call.getDestinationCallNumber ();
        this.sourceCallNumber      = call.getSourceCallNumber  ();
        
        this.setTimestamp( call.getTimestamp () );
    }

    /**
     * The constructor for inbound PDUs.
     *
     * @param call      The Call object
     * @param pduOctets The incoming message bytes
     */
    public ProtocolDataUnit( CallContext call, byte[] pduOctets ) 
    {
        this.call = call;

        OctetBuffer buf = OctetBuffer.wrap( pduOctets );
        
        this.sourceCallNumber = buf.getShort ();
        this.sourceCallNumber &= 0x7FFF; // strip F bit
        
        this.destinationCallNumber = buf.getShort();
        this.destinationCallNumber &= 0x7FFF; // strip R bit
        
        this.setTimestamp( ( buf.getInt () + 0x100000000L ) & 0xFFFFFFFFL );
        
        this.outSeqNo = OctetBuffer.toInt( buf.get() );
        this.inSeqNo = OctetBuffer.toInt( buf.get() );
        
        this.pduType = OctetBuffer.toInt( buf.get() );
        this.pduSubclass = OctetBuffer.toInt( buf.get() );
        
        this.payload = buf.slice ();
    }

    /**
     * Sets the time stamp as long.
     */
    void setTimestamp( long v )
    {
        this.timeStamp = new Long( v );
    }

    /**
     * Returns the timestamp as long.
     */
    long getTimestamp ()
    {
        return this.timeStamp != null ? this.timeStamp.longValue () : 0;
    }
    
    /**
     * Creates a new PDU of the correct type.
     *
     * @param call Call
     * @param pduOctets byte[]
     * @return a PDU
     */
    public static ProtocolDataUnit create( CallContext call, byte[] pduOctets ) 
    {
        if ( pduOctets == null || pduOctets.length < 12 ) {
            return null;
        }

        ProtocolDataUnit pdu = null;

        int pduType = OctetBuffer.toInt( pduOctets[10] ); 

        switch ( pduType ) 
        {
            case VOICE:
                pdu = new VoicePDU( call, pduOctets );
                break;
            default:
                Log.warn( "Unknown PDU type " + pduType );
                pdu = new ProtocolDataUnit( call, pduOctets ) {};
                break;
        }

        return pdu;
    }

    /**
     *  Sends a specified payload. Payload represents the data field in
     *  the frame.
     *
     * @param payload  The payload data
     */
    public void sendPayload( byte[] payload )
    {
        this.outSeqNo = this.call.getOutSeqNoInc();
        this.inSeqNo = this.call.getInSeqNo();

        OctetBuffer pdu = OctetBuffer.allocate( payload.length + 12 );
        
        pdu.putChar( (char) ( this.sourceCallNumber | 0x8000 ) );
        pdu.putChar( (char) this.destinationCallNumber );
        
        long ts = this.getTimestamp ();
        ts =  ( (0x100000000L & ts) > 0 ) ? ts - 0x100000000L : ts;
        pdu.putInt( (int) ts );

        pdu.put( (byte) outSeqNo );
        pdu.put( (byte) inSeqNo );
        pdu.put( (byte) pduType );
        pdu.put( (byte) pduSubclass );
        
        pdu.put( payload );
        
        log( "Sent" );
        
        this.call.send( pdu );
    }

    /**
     *  Arrived is called when a packet arrives. This method doesn't do anything 
     *  more than dumping the frame. It should be overridden in derived class.
     */
    void onArrivedPDU ()
    {
        dump( "Inbound" );
    }

    /**
     * Logs the time-stamp and the inbound/outbound stream sequence number.
     *
     * @param prefix Text to include
     */
    protected void log( String prefix )
    {
        StringBuffer sb = new StringBuffer( "Time: " );
        
        sb.append( this.call.getTimestamp () ).append( ", " );
        sb.append( prefix );
        sb.append( ", Timestamp: " ).append( this.getTimestamp () );
        sb.append( ", iseq: "      ).append( inSeqNo              );
        sb.append( ", oseq: "      ).append( outSeqNo             );
        
        Log.debug( sb.toString () );
    }

    /**
     * Logs this frame.
     */
    void dump( String prefix )
    {
        if ( ! Log.isEnabled( Log.VERB ) ) {
            return;
        }

        StringBuffer sb = new StringBuffer ();
        
        if ( prefix != null ) {
            sb.append( prefix ).append( " " );
        }
        
        sb.append( "PDU:" );
        sb.append( "\n    Source Call = " ).append( sourceCallNumber      );
        sb.append( "\n    Dest Call   = " ).append( destinationCallNumber );
        sb.append( "\n    Timestamp   = " ).append( timeStamp             );
        sb.append( "\n    Out Seq No  = " ).append( outSeqNo              );
        sb.append( "\n    In Seq No   = " ).append( inSeqNo               );
        sb.append( "\n    PDU Type    = " ).append( pduType               );
        sb.append( "\n    Subclass    = " ).append( pduSubclass           );
        
        Log.verb( sb.toString () );
    }
}

