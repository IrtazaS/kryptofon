
/**
 *  Functionality of the private branch exchange (PBX) 
 */
package pbx;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.Socket;
import java.net.UnknownHostException;
import java.util.regex.Pattern;

import utils.Log;

/**
 *  Encapsulates rudimentary functionality of a PBX to list and invite users (peers)
 *  to secure calls.
 *  
 *  The instances of PBXClient class expect to be connected to plain public chat server 
 *  that distributes (broadcasts their messages (terminated by the new-line) to all 
 *  other connected users (possible kryptofon peers).
 *  
 *  Communication with the upper layer (which owns instance of the PBXClient) is done
 *  using call-backs over the PBXClient.Context interface. 
 *
 *  @author Mikica B Kocic
 */
public class PBXClient extends Thread 
{
    /**
     *  PBX Signaling Messages' Types
     */
    public enum CMType
    {
        /** Invalid CMType */
        _INVALID_,

        /** The message sent to remote peer to start the call */
        INVITE, 
        
        /** The message sent back from remote peer informing about remote alerting status */
        RING, 
        
        /** The message sent back from the remote peer indicating accepted call */
        ACCEPT,
        
        /** Indicates call clear down -- either normal or abrupt (like call reject). */
        BYE, 
        
        /** Instant message exchanged between users with encrypted messages */
        INSTANTMESSAGE,
        
        /** Query all peers */
        LIST,
        
        /** Respond to Query all pears */
        ALIVE
    }

    /**
     *  PBX signaling message sent via call-back to the upper layer 
     */
    public class ControlMessage
    {
        public CMType msgType;      // invite, ring, ...
        public String peerUserId;   // remote peer's user name
        public String localUserId;  // local user name
        public String peerAddr;     // remote peer's IP address
        public int    peerPort;     // remote peer's UDP port
        public String secret;       // remote peer's public or secret key
        
        public ControlMessage( CMType msgToken, String peerUserId, String localUserId, 
                String peerAddr, int peerPort, String secret ) 
        {
            this.msgType      = msgToken;
            this.peerUserId   = peerUserId;
            this.localUserId  = localUserId;
            this.peerAddr     = peerAddr;
            this.peerPort     = peerPort;
            this.secret       = secret;
        }

        public String getVerboseRemote () {
            if ( peerAddr.length () == 0 || peerPort == 0 ) {
                return "'" + peerUserId + "'";
            } else {
                return "'" + peerUserId + "' at " + peerAddr + ":" + peerPort;
            }
        }
    }

    //////////////////////////////////////////////////////////////////////////////////////
    
    /**
     *  Provides a call-back context for the instance of PBXClient.
     */
    public interface Context
    {
        /**
         *  Returns configured user ID.
         */
        public abstract String getUserId ();

        /**
         *  Updates PBX status message of the parent
         */
        public abstract void setPbxStatus( String str );

        /**
         *  Reports a system message to log 
         */
        public abstract void report( String style, String str );
        
        /**
         *  Reports incoming textual message
         */
        public void reportIncomingTextMessage( String cssClass, 
                String userId, String message );

        /**
         *  Informs upper layer of incoming INVITE. 
         */
        public abstract void onInvite( final ControlMessage cmsg );
        
        /**
         *  Informs upper layer of incoming RING. 
         */
        public abstract void onRing( final ControlMessage cmsg );
        
        /**
         *  Informs upper layer of incoming ACCEPT. 
         */
        public abstract void onAccept( final ControlMessage cmsg );
        
        /**
         *  Informs upper layer of incoming BYE. 
         */
        public abstract void onBye( final ControlMessage cmsg ); 
        
        /**
         *  Informs upper layer of incoming IMSG. 
         */
        public abstract void onInstantMessage( final ControlMessage cmsg ); 
    }

    //////////////////////////////////////////////////////////////////////////////////////

    /**
     *  HTML CSS class for warning and error messages
     */
    private final static String WARN = "logWarn";
    
    /**
     *  HTML CSS class for info messages 
     */
    private final static String INFO = "logInfo";

    /**
     *  Host name or IP address of the remote chat server 
     */
    private String host = null;
    
    /**
     *  TCP port where to connect to on remote chat server 
     */
    private int port = -1;

    /**
     *  Chat client ID when presented to user (== host + ":" + port)
     */
    private String myID;
    
    /**
     *  Output stream to remote server
     */
    private PrintWriter out = null;
    
    /**
     *  Instance of the TCP socket to chat server.
     */
    private Socket socket = null;
    
    /**
     *  Indicates/enables the thread to be running
     */
    private volatile boolean running = false;
    
    /**
     *  Event (call-back) context for this instance of the PBXClient
     */
    private Context context = null;

    //////////////////////////////////////////////////////////////////////////////////////

    /**
     *  Creates new instance of <code>PBXClient</code> that posts messages 
     *  to specified <code>Context</code>. 
     *  
     *  @param host     host name or IP address of the chat server
     *  @param port     TCP port
     *  @param context  where to log messages (also error and info messages)
     */
    public PBXClient( String host, int port, Context context )
    {
        super( "Chat-" + host + ":" + port );

        this.host        = host;
        this.port        = port;
        this.context     = context;
        
        this.myID = host + ":" + port; 
    }

    /**
     *  Returns local IP address.
     */
    public String getLocalAddress ()
    {
        return socket.getLocalAddress ().getHostAddress ();
    }

    /**
     * Starts the thread.
     */
    @Override
    public void start ()
    {
        if ( isAlive () || running ) {
            return;  // Allow only one thread per instance
        }

        running = true;
        super.start ();
    }

    /**
     *  Sends message (appended with new-line) to chat server
     *  
     *  @param message   message to be sent
     */
    public void send( String message )
    {
        synchronized( this )
        {
            if ( out == null || message == null ) {
                return;
            }
            
            out.println( message );
            out.flush ();
        }
    }

    /**
     *  Sends message (appended with new-line) to chat server prefixed with userId
     *  
     *  @param message    message to be sent
     *  @param userId    user identifier
     */
    public void send( String message, String userId )
    {
        /* Discard spaces from the userId first
         */
        userId = userId.trim().replaceAll( "\\s{1,}", "-" ); 
        
        /* Send message with userId as a prefix
         */
        send( userId + " :: " + message );
    }

    /**
     *  Closes the connection gracefully
     */
    public void close ()
    {
        synchronized( this )
        {
            running = false;

            if ( socket != null && ! socket.isClosed () ) {
                try {
                    socket.close ();
                } catch( IOException e ) {
                    /* ignore */
                }
            }
        }
    }
    
    /**
     *  Reports a system message to log 
     */
    private void report( String style, String str )
    {
        context.report( style, str );
    }

    /**
     *  Reports incoming message
     */
    private void reportIncomingTextMessage( String userId, String message )
    {
        context.reportIncomingTextMessage( "chatMessage", userId, message );
    }
    
    /**
     *  Parses input message. 
     *  
     *  Syntax:
     *  <pre>
     *      [ [ &lt;userId&gt; ] : ] &lt;text-or-control&gt;
     *  </pre>
     *  where default \a userId is <code>[Anonymous]</code>.
     *  
     *  If the \a text-or-control begins with "[$]" it represents control message
     *  and it will not be displayed to the user.   
     */
    private void parseInputMessage( String message )
    {
        /* Parse input with syntax: [ [ <userId> ] ":: " ] <message>
         * where default userId is [Anonymous].
         */
        String[] parts = message.split( ":: ", 2 );
        String userId = "[Anonymous]";
        
        if ( message.startsWith( "WWHHOO: " ) ) {
            userId = "WWHHOO";
            message = message.substring( 8 );
        } else if ( parts.length == 0 ) {
            message = parts[0];
        } else if ( parts[0].trim().length () == 0 && parts.length >= 2 ) {
            message = parts[1];
        } else if ( parts.length >= 2 ) {
            userId  = parts[0].trim ();
            message = parts[1];
        } else {
            message = parts[0];
        }

        /* Now, check if we have a control message beginning with token [$]
         * but not comming from the anonymous user.
         */
        parts = message.trim().split( "\\s{1,}" );

        if ( ! userId.equals( "[Anonymous]" ) 
                && parts.length >= 1 
                && parts[0].equals( "[$]" ) )
        {
            parseControlMessage( userId, parts, message );
        }
        else
        {
            reportIncomingTextMessage( userId, message );
        }
    }

    /**
     *  Parses control messages.
     *  
     *  TODO Format messages in XML instead.
     *  (It would be too much to do for IP1 course.)
     *  
     *  Syntax:
     *  <pre>
     *     [$] INVITE    local-name   remote-ip-address   remote-udp-port  [ public-key ]   
     *     [$] RING      local-name   remote-ip-address   remote-udp-port  [ public-key ]
     *     [$] ACCEPT    local-name   remote-ip-address   remote-udp-port  [ secret-key ]
     *     [$] BYE       local-name [ remote-ip-address [ remote-udp-port ] ]
     *     [$] IMSG      local-name   encrypted-message
     *     [$] LIST    [ username-regex ]
     *     [$] ALIVE
     *  </pre>   
     */
    private void parseControlMessage( String remoteUserId, String[] args, String original )
    {
        assert args.length >= 1 && args[0].equals( "[$]" );

        /* Parse args[1] as CMType 
         */
        CMType cmType = CMType._INVALID_;
        
        if ( args[1].equalsIgnoreCase( "invite" ) ) {
            cmType = CMType.INVITE;
        } else if ( args[1].equalsIgnoreCase( "ring" ) ) {
            cmType = CMType.RING;
        } else if ( args[1].equalsIgnoreCase( "accept" ) ) {
            cmType = CMType.ACCEPT;
        } else if ( args[1].equalsIgnoreCase( "bye" ) ) {
            cmType = CMType.BYE;
        } else if ( args[1].equalsIgnoreCase( "imsg" ) ) {
            cmType = CMType.INSTANTMESSAGE;
        } else if ( args[1].equalsIgnoreCase( "list" ) ) {
            cmType = CMType.LIST;
        } else if ( args[1].equalsIgnoreCase( "alive" ) ) {
            cmType = CMType.ALIVE;
        } else {
            return; // ignore unknown types
        }
        
        String destinationUserId = null;
        
        /* Parse destination user id, then ignore loop messages and 
         * messages that are not explicitly for us.
         */
        if ( cmType != CMType.LIST && cmType != CMType.ALIVE && args.length >= 3 ) 
        {
            destinationUserId = args[2];

            if ( remoteUserId.equalsIgnoreCase( destinationUserId ) ) {
                return;
            } else if ( ! destinationUserId.equalsIgnoreCase( context.getUserId () ) ) { 
                return;
            }
        }

        //////////////////////////////////////////////////////////////////////////////////
        /* [$]  INVITE  local-name remote-ip-address remote-udp-port [ public-key ]
         *  0     1         2           3              4                  5 opt.      
         */
        if ( args.length >= 5  && args[1].equalsIgnoreCase( "invite" ) )
        {
            String publicKey = args.length >= 6 ? args[5] : null;

            try
            {
                int port = Integer.parseInt( args[4] ); // remote port

                context.onInvite( new ControlMessage( CMType.INVITE,
                        remoteUserId, destinationUserId, args[3], port, publicKey ) );
            }
            catch( NumberFormatException e )
            {
                /* ignore message */
            }
        }
        //////////////////////////////////////////////////////////////////////////////////
        /* [$]  RING   local-name remote-ip-address remote-udp-port  [ public-key ]
         *  0     1         2           3              4                   5
         */
        else if ( args.length >= 5 && args[1].equalsIgnoreCase( "ring" ) ) 
        {
            String publicKey = args.length >= 6 ? args[5] : null;

            try
            {
                int port = Integer.parseInt( args[4] ); // remote port

                context.onRing( new ControlMessage( CMType.RING,
                        remoteUserId, destinationUserId, args[3], port, publicKey ) );
            }
            catch( NumberFormatException e )
            {
                /* ignore message if port is not integer */
            }
        }
        //////////////////////////////////////////////////////////////////////////////////
        /* [$]  ACCEPT  local-name remote-ip-address remote-udp-port [ secret-key ]
         *  0     1         2           3                  4               5        
         */
        else if ( args.length >= 5 && args[1].equalsIgnoreCase( "accept" ) ) 
        {
            String secretKey = args.length >= 6 ? args[5] : null;

            try
            {
                int port = Integer.parseInt( args[4] ); // remote port
                
                context.onAccept( new ControlMessage( CMType.ACCEPT, 
                        remoteUserId, destinationUserId, args[3], port, secretKey ) );
            }
            catch( NumberFormatException e )
            {
                /* ignore message if port is not integer */
            }
        }
        //////////////////////////////////////////////////////////////////////////////////
        /* [$]   BYE   local-name [ remote-ip-address [ remote-udp-port ] ]
         *  0     1         2            3                   4      
         */
        else if ( args.length >= 3 && args[1].equalsIgnoreCase( "bye" ) ) 
        {
            try
            {
                String host = args.length >= 4 ? args[3] : "";
                int port = args.length >= 5 ? Integer.parseInt( args[4] ) : 0;

                context.onBye( new ControlMessage( CMType.BYE,
                        remoteUserId, destinationUserId, host, port, null ) );
            }
            catch( NumberFormatException e )
            {
                /* ignore message if port is not integer */
            }
        }
        //////////////////////////////////////////////////////////////////////////////////
        /* [$]   IMSG   local-name  encrypted-message
         *  0     1         2              3      
         */
        else if ( args.length >= 4 && args[1].equalsIgnoreCase( "imsg" ) ) 
        {
            context.onInstantMessage( new ControlMessage( CMType.INSTANTMESSAGE,
                    remoteUserId, destinationUserId, "", 0, args[3] ) );
        }
        //////////////////////////////////////////////////////////////////////////////////
        /* [$]  LIST   [ username-regex ]
         *  0     1          2 opt.
         */
        else if ( args.length >= 2 && args[1].equalsIgnoreCase( "list" ) )
        {
            String myUserId = context.getUserId ();

            report( INFO, "Listing users..." );
            
            if ( ! myUserId.isEmpty () )
            {
                if ( args.length < 3 ) // query all users (without regex)
                { 
                    /* Respond back to query
                     */
                    send( "[$] ALIVE", myUserId );
                }
                else  // case-insensitive query with regex
                {
                    Pattern p = null;
                    try {
                        p = Pattern.compile( args[2], Pattern.CASE_INSENSITIVE );
                    } catch ( Throwable e ) {
                        /* ignored */
                    }
                    if ( p != null && p.matcher( myUserId ).find () )
                    {
                        /* Respond back to query
                         */
                        send( "[$] ALIVE", myUserId );
                    }
                }
            }
        }
        //////////////////////////////////////////////////////////////////////////////////
        /* [$]  ALIVE
         *  0     1   
         */
        else if ( args.length >= 2 
                && args[1].equalsIgnoreCase( "alive" ) )
        {
            report( INFO, "-- User '" + remoteUserId + "' is alive." );
            // TODO this might as well update some list of possible peers?
            // -- but that requires little more functionality on the PBX (chat) server
            // side.
        }
    }

    /**
     *  Broadcasts INVITE message
     */
    public void sendInvite( String remoteUserId, 
            String localIpAddress, int localUdpPort, String publicKey )
    {
        this.send( "[$] INVITE " + remoteUserId + " " 
                + localIpAddress + " "  + localUdpPort 
                + ( publicKey != null ? " " + publicKey : "" ),
                context.getUserId () );
    }

    /**
     *  Broadcasts RING message
     */
    public void sendRing( String remoteUserId,
            String localIpAddress, int localUdpPort, String publicKey )
    {
        this.send( "[$] RING " + remoteUserId + " " 
                + localIpAddress + " "  + localUdpPort 
                + ( publicKey != null ? " " + publicKey : "" ),
                context.getUserId () );
    }

    /**
     *  Broadcasts ACCEPT message
     */
    public void sendAccept( String remoteUserId, 
            String localIpAddress, int localUdpPort, String publicKey )
    {
        this.send( "[$] ACCEPT " + remoteUserId + " " 
                + localIpAddress + " "  + localUdpPort 
                + ( publicKey != null ? " " + publicKey : "" ),
                context.getUserId () );
    }

    /**
     *  Broadcasts BYE message
     */
    public void sendBye( String remoteUserId,
            String localIpAddress, int localUdpPort )
    {
        this.send( "[$] BYE " + remoteUserId + " " 
                + localIpAddress + " "  + localUdpPort, 
                context.getUserId () );
    }

    /**
     *  Broadcasts IMSG message
     */
    public void sendInstantMessage( String remoteUserId, String encryptedMessage )
    {
        this.send( "[$] IMSG " + remoteUserId + " " + encryptedMessage, 
                context.getUserId () );
    }

    /**
     *  Broadcasts LIST message (to list potential peers)
     */
    public void sendListPeers( String regex )
    {
        this.send( "[$] LIST" + ( regex != null ? " " + regex : "" ),
                context.getUserId () );
    }

    /**
     *  Connects socket, then reads messages from server while <code>running</code> flag 
     *  is enabled. Finally, closes connection in graceful manner.
     *  
     *  Incoming messages are dispatched to the owner via the call-back context
     *  (see PBXClient.Context).
     */
    @Override
    public void run ()
    {
        Log.trace( "Thread started" );

        //////////////////////////////////////////////////////////////////////////////////
        /* Open connection
         */
        report( INFO, "Connecting to " + myID + "..." );
        context.setPbxStatus( "Connecting to " + myID + "..." );

        try
        {
            synchronized( this ) {
                socket = new Socket( host, port );
            }
        }
        catch( UnknownHostException e )
        {
            report( WARN, "'Unknown host' exception while creating socket" );
            report( WARN, e.toString () );
            running = false;
        }
        catch( IOException e )
        {
            report( WARN, "I/O exception while connecting" );
            report( WARN, e.toString () );
            running = false;
        }

        //////////////////////////////////////////////////////////////////////////////////
        /* Get input stream. Consider input characters UTF-8 encoded.
         * TODO: It would be nice to have this as a parameter.
         */
        InputStreamReader reader = null;
        try
        {
            if ( socket != null ) {
                reader = new InputStreamReader( socket.getInputStream (), "utf-8" );
            }
        }
        catch( IOException e )
        {
            report( WARN, "I/O exception while getting input stream" );
            report( WARN, e.toString () );
            running = false;
            reader = null;
        }
        
        //////////////////////////////////////////////////////////////////////////////////
        /* Get output stream. Encode our character strings as UTF-8.
         */
        try 
        {
            if ( socket != null ) {
                out = new PrintWriter(
                        new OutputStreamWriter( socket.getOutputStream(), "utf-8" ),
                        /*autoflush*/ true );
            }
        }
        catch( IOException e )
        {
            report( WARN, "I/O exception while getting output stream" );
            report( WARN, e.toString () );
            running = false;
            out = null;
        }

        //////////////////////////////////////////////////////////////////////////////////
        /* Finally connected... (if running == true survived until here)
         */
        BufferedReader in = null;
        if ( running )
        {
            context.setPbxStatus( "Connected to " + myID );
            report( "logOk", "Connected to " + myID + ". Ready to communicate..." );
            
            in = new BufferedReader( reader );
        }
        
        //////////////////////////////////////////////////////////////////////////////////
        /* Read messages from the socket and dump them on log area
         */
        while( running )
        {
            try
            {
                parseInputMessage( in.readLine () );
            }
            catch( IOException e )
            {
                report( WARN, "Connection lost!" );
                report( WARN, e.toString () );
                running = false;
            }
        }

        report( INFO, "Closing connection " + myID + "..." );

        //////////////////////////////////////////////////////////////////////////////////
        /* Close connection gracefully
         */
        try
        {
            if ( out != null ) {
                out.close ();
            }
            if ( in != null ) {
                in.close ();
            }
            synchronized( this )
            {
                if ( socket != null && ! socket.isClosed () ) {
                    socket.close ();
                }
            }
        }
        catch( IOException e )
        {
            report( WARN, "I/O exception while closing connection" );
            report( WARN, e.toString () );
        }
        
        report( INFO, "... connection closed " + myID );
        Log.trace( "Thread completed" );
    }
}
