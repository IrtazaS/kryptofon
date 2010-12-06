
/**
 *  Implementation of the Peer-to-Peer protocol over datagram channel 
 */
package protocol;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketException;
import java.net.UnknownHostException;

import crypto.SymmetricCipher;

import utils.Log;
import utils.OctetBuffer;

/**
 *  Binds the UDP port. Each peer is associated with one DatagramChannel object.
 *  
 *  @author Mikica B Kocic
 */
public class DatagramChannel implements Runnable
{
    /** The default size for the inbound UDP buffer */
    private final static int INBOUND_UDP_BUFFER_SIZE = 4096;

    /** The local UDP port where datagram socket is bound */
    private int localPort;

    /** The UDP receiver socket */
    private DatagramSocket udpReceiver;
    
    /** The UDP receiver worker thread */
    private Thread pduReceiverThread;
    
    /** Indicates that worker thread should be running (receiving datagrams) */
    private volatile boolean running;
    
    /** Current remote peer receiving datagrams from UDP socket*/
    private RemotePeer remotePeer;
    
    /** Currently used symmetric cipher */
    private SymmetricCipher usedPduCipher;

    /**
     *  Constructor for the DatagramChannel object
     *
     *  @param localPort  local UDP port receiving peer's PDUs
     * 
     *  @throws SocketException Thrown if the UDP socket cannot be created
     *  @throws UnknownHostException Thrown if the remoteHost cannot be resolved
     */
    public DatagramChannel( int localPort )
    {
        this.remotePeer = null;
        this.usedPduCipher = null;

        this.running = false;
        this.localPort = -1;
        
        for( int i = localPort; i < localPort + 100; ++i )
        {
            try {
                this.udpReceiver = new DatagramSocket( i );
                this.localPort = i;
                break;
            } catch( SocketException e ) {
                /* ignore error and continue search for unbound port */
            }
        }
        
        /* If binded UDP port, start worker thread.
         */
        if ( this.localPort > 0 ) 
        {
            Log.trace( "Bound to UDP port " + this.localPort );
            this.running = true;
            pduReceiverThread = new Thread( this, "UDP" );
            pduReceiverThread.setPriority( Thread.MAX_PRIORITY - 1 );
            pduReceiverThread.start ();
        }
    }

    /**
     *  Returns used local UDP port.
     */
    public int getLocalPort ()
    {
        return this.udpReceiver.getLocalPort ();
    }

    /**
     *  Sets cipher to be used for PDU ciphering 
     */
    public void useSymmetricCipher( SymmetricCipher cipherEngine )
    {
        usedPduCipher = cipherEngine;
        
        if ( cipherEngine != null ) {
            Log.trace( "Using PDU cipher: " + cipherEngine.getAlgorithmDesc () );
        }
    }

    /**
     *  Gets current cipher used for PDU ciphering 
     */
    public SymmetricCipher getUsedSymmetricCipher ()
    {
        return this.usedPduCipher;
    }

    /**
     *  Adds new peer to receive incoming PDUs
     */
    public void addNewPeer( RemotePeer remotePeer )
    {
        this.remotePeer = remotePeer;
    }

    /**
     *  Returns if there is active remote peer
     */
    public boolean hasRemotePeer ()
    {
        return this.remotePeer != null;
    }

    /**
     *  Returns the remote peer
     */
    public RemotePeer getRemotePeer ()
    {
        return this.remotePeer;
    }

    /**
     *  Returns if peer seems to be dead (we are not receiving PDUs from it).
     */
    public boolean isPearDead( int maxIdleTimeMillis )
    {
        if ( this.remotePeer == null ) {
            return false; // cannot be dead if does not exists
        }
        
        return this.remotePeer.receiverIdleTime () > maxIdleTimeMillis;
    }
    
    /**
     *  Detaches peer and all its call from the UDP receiver
     */
    public void removePeer ()
    {
        this.usedPduCipher = null;

        if ( this.remotePeer != null ) 
        {
            this.remotePeer.cleanUp ();
            this.remotePeer = null;
        }
    }

    /**
     *  Stops PDU receiver thread
     */
    public void stop ()
    {
        running = false;
        
        if ( pduReceiverThread != null )
        {
            udpReceiver.close ();
            Log.debug( "Closed Socket" );
            
            try
            {
                pduReceiverThread.join ();
                Log.debug( "Joined binder thread" );
            }
            catch( InterruptedException e )
            {
                Log.exception( Log.WARN, e );
            }
            
            pduReceiverThread = null;
        }
    }


    /**
     *  Receives (and deciphers) PDUs from remote peers in a loop. 
     *  Dispatches inbound PDUs to associated instance of the RemotePeer
     *  that will handle PDUs.
     */
    public void run ()
    {
        Log.trace( "Thread started" );

        /* Receive and dispatch datagrams
         */
        byte[] buff = new byte[ INBOUND_UDP_BUFFER_SIZE ];
        
        while( running )
        {
            DatagramPacket packet = new DatagramPacket( buff, buff.length );
            
            try 
            {
                udpReceiver.receive( packet );
                
                byte[] pdu = new byte[ packet.getLength () ];
                System.arraycopy( buff, 0, pdu, 0, pdu.length );

                InetAddress peerAddr = packet.getAddress ();
                int peerPort = packet.getPort ();

                // packetDump( pdu, pdu.length, peerAddr, peerPort, true );
                
                if ( usedPduCipher != null ) {
                    pdu = usedPduCipher.decrypt( /*randomPreambleLen*/ 8, pdu );
                }

                if ( pdu != null ) 
                {
                    packetDump( pdu, pdu.length, peerAddr, peerPort, true );
    
                    if ( remotePeer != null ) {
                        remotePeer.addIncomingPDU( pdu );
                    }
                }
            }
            catch( IOException e )
            {
                if ( running ) {
                    Log.exception( Log.WARN, e );
                }
            }
        }
    }

    /**
     *  Encrypts and sends PDUs to remote peer
     */
    public void send( OctetBuffer pdu, InetAddress peerAddr, int peerPort )
    {
        try
        {
            packetDump( pdu.getStore (), pdu.getPosition (), peerAddr, peerPort, false );

            byte[] datagram = new byte[ pdu.getPosition () ];
            System.arraycopy( pdu.getStore (), 0, datagram, 0, datagram.length );
            
            if ( usedPduCipher != null ) {
                datagram = usedPduCipher.encrypt( /*randomPreambleLen*/ 8, datagram );
            }

            if ( datagram != null ) 
            {
                // packetDump( datagram, datagram.length, peerAddr, peerPort, false );
                
                DatagramPacket packet = new DatagramPacket(
                        datagram, datagram.length, peerAddr, peerPort );
    
                this.udpReceiver.send( packet );
            }
        }
        catch( Exception e )
        {
            Log.exception( Log.WARN, e );
        }
    }
    
    /**
     * Dumps information of a frame (in bytes) to standard error.
     *
     * @param octets   The octets of the in- or outgoing PDU
     * @param len      The size of PDU
     * @param addr     The remote host address
     * @param port     The port number
     * @param incoming Indicates if it is inbound (true) or outgoing (false) PDU
     */
    protected void packetDump(
            byte[] octets, int len, 
            InetAddress addr, int port, 
            boolean incoming )
    {
        if ( ! Log.isEnabled( Log.PDU ) ) {
            return;
        }
        
        StringBuffer sb = new StringBuffer( 500 );
        
        sb.append( incoming ? "Packet from <--- " : "Packet to ---> " );
        
        sb.append( addr.getHostAddress() ).append( ":" ).append( port );
        
        sb.append( ", size = ").append( len ).append( "\n\n" );
        
        sb.append( Log.toHex( octets, len, " " ) ).append( "\n" );
        
        Log.pdu( sb.toString () );
    }
}

