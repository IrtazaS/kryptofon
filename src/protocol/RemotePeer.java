
package protocol;

import java.net.InetAddress;
import java.util.Vector;

import utils.Log;
import utils.OctetBuffer;

/**
 *  Encapsulates the link between the UDP channel and a CallContext.
 *  UDP channel receives packets from all remote peers and dispatches them 
 *  to particular RemotePeer handler. RemotePeer might have multiple calls
 *  in real PBX, howerever, this implementation allows only single CallContext
 *  per RemotePeer.
 *  
 *  @author Mikica B Kocic
 */
public class RemotePeer implements Runnable
{
    /** The UDP channel */
    private DatagramChannel socket = null;
    
    /** The call context */
    private CallContext call = null;

    /** PBXClient's (chat server's) User ID of the remote peer */
    private String remoteUserId = null;
    
    /** Remote IP address where to send PDUs */
    private InetAddress remoteAddr = null;
    
    /** Remote UDP port where to send PDUs */
    private int remotePort = -1;

    /** The queue of incoming PDUs from remote peer via our UDP channel */
    protected Vector<byte[]> inboundPDUs = null;
    
    /** The receiving process thread. */
    protected Thread pduReceiverThread = null;

    /** Indicates whether communication with peer is active or not */
    private volatile boolean transmitting = false;

    /** The time-stamp of the last received PDU */
    private long lastReceiverTimestamp = 0;

    /**
     *  Constructor for the RemotePeer object
     *
     *  @param socket           instance of DatagramChannel used for communication
     *  @param remoteUserId     peer's user ID
     *  @param remoteAddr       peer's IP address
     *  @param remotePort       peer's UDP port listening for ours PDUs       
     */
    public RemotePeer( DatagramChannel socket, String remoteUserId, 
            InetAddress remoteAddr, int remotePort )
    {
        this.remoteUserId = remoteUserId;
        this.remoteAddr   = remoteAddr;
        this.remotePort   = remotePort;
        
        this.call = null;

        this.inboundPDUs = new Vector<byte[]> ();
        
        synchronized( socket )
        {
            this.socket = socket;
            this.socket.addNewPeer( this );
            
            if ( this.remoteAddr != null 
                    && this.remotePort > 0 && this.remotePort <= 65535 ) 
            {
                this.startReceiver ();
            }
        }
    }

    /**
     *  Returns remote name (remote userid) of the peer
     */
    public String getRemoteUserId ()
    {
        return remoteUserId;
    }

    /**
     *  This method starts the receiver thread for inbound PDUs.
     */
    synchronized private void startReceiver () 
    {
        this.transmitting = true;

        this.lastReceiverTimestamp = System.currentTimeMillis ();
            
        this.pduReceiverThread = new Thread( this, 
                "Peer-" + remoteAddr.getHostAddress() + ":" + remotePort );
        
        this.pduReceiverThread.setPriority( Thread.MAX_PRIORITY - 1 );
        
        if ( this.call != null ) {
            this.call.resetClock ();
        }
        
        this.pduReceiverThread.start ();
    }

    /**
     *  Returns elapsed time since last received packet
     */
    synchronized public long receiverIdleTime ()
    {
        return System.currentTimeMillis () - this.lastReceiverTimestamp;
    }

    /**
     * Adds an incoming PDUs (as bytes) to the PDUs queue. We are
     * still on the recv thread; this data was received by binder and
     * passed to us via friend. The PDUs are stored in the queue and
     * we deal with them on our own thread to relief the recv thread.
     * In other words, this is the last thing we do on the recv thread!
     *
     * @param data  The PDU octets
     */
    synchronized public void addIncomingPDU( byte[] data ) 
    {
        if ( ! this.transmitting || data == null ) {
            return;
        }

        this.lastReceiverTimestamp = System.currentTimeMillis ();
        this.inboundPDUs.addElement( data );
        this.notifyAll ();
    }

    /**
     *  Manages the incoming PDUs stored in the PDUs queue.
     *  This thread is started by startReceiver() and run separately from the
     *  binder's receiver thread.
     */
    public void run () 
    {
        Log.trace( "Thread started" );
        
        while ( this.transmitting )
        {
            Object[] pdusToSend = new Object[0];
            
            synchronized( this ) 
            {
                try {
                    this.wait ();
                }
                catch( InterruptedException e ) {
                    /* ignored */
                }
                
                int pduCount = this.inboundPDUs.size ();
                
                // Do some smart stuff here? (limit the take to only 20 PDUs?)
                // You'd hope that normally we'd get 1 or maybe 2 PDUs here.
                //
                if ( pduCount > 0 )
                {
                    pdusToSend = new Object[ pduCount ];
                    for ( int i = 0; i < pduCount; ++i ) {
                        pdusToSend[i] = this.inboundPDUs.elementAt(i);
                    }
                    this.inboundPDUs.removeAllElements ();
                }
            }
            
            // After releasing the lock, let's deal with the list...
            // we are now on the thread of the call, any time we waste is
            // our own.
            // should really sort these into sequence before we dispose of them.
            
            for( int i = 0; i < pdusToSend.length; ++i )
            {
                try {
                    parsePDU( (byte[]) pdusToSend[i] );
                }
                catch( Throwable e ) {
                    Log.error( "ParsePDU failed; " + e.toString () );
                    Log.where ();
                }
            }
        }
        
        this.socket = null;
        this.call = null;
    }

    /**
     *  Deals with newly received PDU octets. This method encapsulates them
     *  into a instance of PDU class, deal with internal counters, sends an
     *  acknowledgement and notifies the PDU it has arrived.
     */
    void parsePDU( byte[] octets ) 
    {
        if ( this.call != null && octets != null ) 
        {
            /* Parse PDU
             */
            ProtocolDataUnit pdu = ProtocolDataUnit.create( this.call, octets );

            /* Dispatch PDU if it is tagged with valid call numbers.
             * Note: Dispatching is hard-coded here, but this place might be
             * a core for real PBX call-id handling.
             */
            if ( pdu != null && pdu.destinationCallNumber == 0x5926 
                             && pdu.sourceCallNumber == 0x3141 )
            {
                pdu.onArrivedPDU ();
            }
            else if ( pdu != null )
            {
                Log.warn( "Ignored PDU with destCall# " + pdu.destinationCallNumber
                        + ", srcCalL# " + pdu.sourceCallNumber );
            }
        }
    }

    /**
     *  Adds the new (not owned) call to the peer.
     */
    public void addNewCall( CallContext call )
    {
        this.call = call;
        
        /* Tag the call with call numbers. 
         * Note: Tagging is hard-coded here, but this place might be a core 
         * for real PBX call-id handling.
         */
        this.call.setSourceCallNumber( 0x3141 );
        this.call.setDestinationCallNumber( 0x5926 );
    }

    /**
     *  Stops transmitting and c cleans up resources (local and used by the calls).
     */
    public void cleanUp ()
    {
        if ( this.call != null )
        {
            this.call.cleanUp ();
            this.transmitting = false;
        }
    }

    /**
     *  Sends packet to remote peer over datagram channel
     */
    public void send( OctetBuffer pdu )
    {
        if ( this.transmitting ) 
        {
            this.socket.send( pdu, this.remoteAddr, this.remotePort );
        }
    }
}
