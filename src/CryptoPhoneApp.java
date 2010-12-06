
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Image;
import java.awt.Point;
import java.awt.Toolkit;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.FocusEvent;
import java.awt.event.FocusListener;
import java.awt.event.KeyEvent;
import java.awt.event.KeyListener;
import java.awt.event.WindowEvent;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.UnknownHostException;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.LinkedList;
import java.util.List;

import javax.swing.GroupLayout;
import javax.swing.JCheckBox;
import javax.swing.JEditorPane;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JScrollPane;
import javax.swing.JSeparator;
import javax.swing.JTextField;
import javax.swing.Timer;
import javax.swing.UIManager;
import javax.swing.WindowConstants;
import javax.swing.plaf.ColorUIResource;

import crypto.CipherEngine;
import crypto.PublicEncryptor;
import crypto.SymmetricCipher;

import pbx.PBXClient;

import protocol.CallContext;
import protocol.DatagramChannel;
import protocol.RemotePeer;
import protocol.VoicePDU;

import audio.AudioInterface;

import ui.JImageButton;
import ui.JSecState;
import utils.Log;

/**
 *  The Swing based GUI front-end of the Kryptofon application that
 *  implements simple VoIP phone and chat client with encrypted peer-to-peer
 *  communication.
 * 
 *  @author Mikica B Kocic
 */
public class CryptoPhoneApp extends JFrame 
       implements ActionListener, KeyListener, PBXClient.Context, Log.AttentionContext
{
    /**
     *  Implements java.io.Serializable interface
     */
    private static final long serialVersionUID = -1830703904673318918L;

    /**
     *  The common application title prefix.
     */
    private static final String appTitle = "IP1-10: Kryptofon";

    /**
     *  The initial message content of the input text message field.  
     */
    private static final String defaultInputMsg = 
        "<type in message, command or command arguments here>";

    /**
     *  The default file name where to dump log area contents
     *  with <code>:dump</code> command
     */
    private static final String defaultLogAreaDumpFilename = "mykf-log-area-";

    /**
     *  The host name or IP address of the remote chat server 
     */
    private String serverName = "atlas.dsv.su.se"; // Connection defaults
    
    /**
     *  The TCP port where to connect to on remote chat server 
     */
    private int serverPort = 9494; // Connection defaults
    
    /**
     *  The instance of PBX client connected to remote chat server.
     */
    private PBXClient pbxChannel = null;

    /**
     *  The last status message of the PBX channel (posted by the setPbxStatus()).
     */
    private String pbxChannelStatus = "";
    
    /**
     *  Main timer (elapses every 1000 ms)
     */
    private Timer mainTimer = null;

    /**
     *  The reconnect delay timer (for timing delay between two reconnections). 
     *  Value -1 means 'disabled'.
     */
    private int reconnectTimeout = -1;
    
    /**
     *  Retry counter of number of failed reconnecting attempts.
     */
    private int reconnectRetryCount = 0;

    /**
     *  The default local UDP port.
     */
    private int localUdpPort = 47000;
    
    /**
     *  The instance of the UDP transceiver responsible for  
     *  peer-to-peer communication between two Kryptofons. 
     */
    private DatagramChannel udpChannel = null;
    
    /**
     *  The instance of the Audio interface used to access microphone and speaker.
     */
    private AudioInterface audioInterface = null;

    /**
     *  The last PBX control message sent to us (waiting to be handled)
     */
    private PBXClient.ControlMessage lastMessageFromPBX = null;

    /**
     *  The last public key received from remote peer
     */
    PublicEncryptor remotePublicKey = null;
    
    /**
     *  The remote peer (its user id) that we are currently inviting to a call.
     *  Null if we are not inviting anyone.
     */
    private String currentInvite = null;
    
    /**
     *  Timer used to detect unresolved invite (i.e. invite to non-existing peer). 
     *  Value -1 means 'disabled'.
     */
    private int inviteTimeout = -1;
    
    /**
     *  Indicates whether to monitor if peer is sending voice PDUs to us.
     *  Should be set to 'true' always when set call established. 
     */
    private boolean monitorIfPeerIsSendingVoice = false;

    /**
     *  The log area formatted in HTML
     */
    private JEditorPane logArea;
    
    /*  The GUI components
     */
    private JSecState    securityState;
    private JImageButton sendButton;
    private JImageButton listPeersButton;
    private JImageButton dialButton;
    private JImageButton secureDialButton;
    private JImageButton hangupButton;
    private JLabel       imsgLabel;
    private JTextField   inputMsg;
    private JLabel       idLabel;
    private JTextField   userId;
    private JCheckBox    autoAnswer;

    /**
     *  Creates a new instance of the <code>CryptoPhoneApp</code>.
     *  
     *  @param args the command line arguments passed to main
     */
    public CryptoPhoneApp( String args[] )
    {
        super( appTitle );
        
        setDefaultCloseOperation( WindowConstants.EXIT_ON_CLOSE );

        //////////////////////////////////////////////////////// Create GUI elements /////
        
        /* Fonts and colors
         */
        Font textFont = new Font( Font.SANS_SERIF, Font.PLAIN, 14 );
        UIManager.put( "ToolTip.background", new ColorUIResource( 0xFF, 0xFF, 0xC7 ) );

        /* Application icons
         */
        List<Image> icons = new LinkedList<Image> ();
        icons.add( JImageButton.loadIcon( this, "favicon48.png" ).getImage () );
        icons.add( JImageButton.loadIcon( this, "favicon24.png" ).getImage () );
        icons.add( JImageButton.loadIcon( this, "favicon16.png" ).getImage () );
        setIconImages( icons );
         
        /* Components
         */
        inputMsg = new JTextField( defaultInputMsg, 30 );
        inputMsg.setFont( textFont );
        inputMsg.selectAll ();
        inputMsg.setToolTipText( 
                "<html><head></head><body><p>"
                + "Enter a text message or command here, then press <tt>Enter</tt><br/>"
                + "If you are going to use command buttons or command mnemonics,<br/>"
                + "then put command arguments here (or leave this field empty)."
                + "</p></body></html>"
                );
        
        imsgLabel = new JLabel (); // without the text; holds only mnemonic for the field
        imsgLabel.setFont( textFont );
        imsgLabel.setDisplayedMnemonic( KeyEvent.VK_I );
        imsgLabel.setLabelFor( inputMsg );

        securityState = new JSecState( this );
        
        sendButton = new JImageButton( this,
                "Send message", "chat.png", "chat2.png" );
        sendButton.setMnemonic( KeyEvent.VK_ENTER );
        
        listPeersButton = new JImageButton( this,
                "List kryptofon users (Alt + L)", "listPeers.png", "listPeers2.png" );
        listPeersButton.setMnemonic( KeyEvent.VK_L );
        
        dialButton = new JImageButton( this,
                "Make a call (Alt+C)", "dial.png", "dial2.png" );
        dialButton.setMnemonic( KeyEvent.VK_C );
        
        secureDialButton = new JImageButton( this,
                "Make a secure call (Alt+S)", "secureDial.png", "secureDial2.png" );
        secureDialButton.setMnemonic( KeyEvent.VK_S );
        
        hangupButton = new JImageButton( this,
                "Clear or reject the call (Alt+H)", "hangup.png", "hangup2.png" );
        hangupButton.setMnemonic( KeyEvent.VK_H );
        
        userId = new JTextField ();
        userId.setFont( textFont );
        userId.setText( getUserId () ); // set default user id
        userId.setToolTipText( 
                "<html><head></head><body><p>"
                + "Enter name, which will be used to identify you to other kryptofon peers.<br/>"
                + "The name should be unique and possibly without whitespaces."
                + "</p></body></html>"
                );
        
        idLabel = new JLabel( "My Name:" );
        idLabel.setFont( textFont );
        idLabel.setDisplayedMnemonic( KeyEvent.VK_N );
        idLabel.setLabelFor( userId );

        autoAnswer = new JCheckBox( "Autoanswer" );
        autoAnswer.setMnemonic( KeyEvent.VK_A );
        autoAnswer.setToolTipText( "Toggle auto-answer mode (Alt+A)" );

        logArea = new JEditorPane ();
        logArea.setFont( textFont );
        logArea.setEditable( false );
        logArea.setToolTipText( 
                "<html><head></head><body><p>"
                + "<strong>Log Area</strong><br/>"
                + "Use <tt>:dump</tt> command to save contents into a file"
                + "</p></body></html>"
                );

        clearLogArea ();

        //////////////////////////////////////////////////////// GUI Layout //////////////

        createLayout ();

        /* Set inputMsg field ready for user to type in something...
         */
        inputMsg.requestFocus ();

        //////////////////////////////////////////////////////// Event listeners /////////
        
        createEventListeners ();

        //////////////////////////////////////////////////////// Window Size /////////////
        
        /* Adjust window dimensions not to exceed screen dimensions ...
         */
        Dimension win = new Dimension( 1024, 600 );
        Dimension scsz = Toolkit.getDefaultToolkit().getScreenSize();
        win.width  = Math.min( win.width, scsz.width );
        win.height = Math.min( win.height, scsz.height - 40 );
        setSize( win );
        setMinimumSize( new Dimension( 600, 400 ) );
        
        /* ... then center window on the screen.
         */
        setLocation( ( scsz.width - win.width )/2, ( scsz.height - 40 - win.height )/2 );

        //////////////////////////////////////////////////////// Enable Logging //////////

        /* Enable Log
         */
        Log.setEnabled( Log.ALL   , false );
        Log.setEnabled( Log.VERB  , false );
        Log.setEnabled( Log.PDU   , false );
        Log.setEnabled( Log.AUDIO , false );
        Log.setEnabled( Log.DEBUG , false );
        Log.setEnabled( Log.ATTN  , true  );
        Log.setEnabled( Log.TRACE , true  );
        Log.setEnabled( Log.INFO  , true  );
        Log.setEnabled( Log.WARN  , true  );
        Log.setEnabled( Log.ERROR , true  );

        Log.attn = this; // catch messages in 'attention' channel

        /* Display initial contents in the log area
         */
        displayUsage ();

        //////////////////////////////////////////////////////// Start services //////////
        
        /* Parse arguments: [ <host> [ <port> ] ]
         */
        if ( args.length >= 1 )
        {
            serverName = args[ 0 ];
            if( args.length >= 2 ) try {
                serverPort = Integer.parseInt( args[ 1 ] );
            } catch ( NumberFormatException e ) {
                Log.warn( "Using the default destination TCP port: " + serverPort );
            }
        }

        /* Load authorized public keys and initializes asymmetric and symmetric 
         * ciphering engines
         */
        CipherEngine.initialize ();
        
        /* Start UDP listener and audio interface...
         */
        startKryptofonServices ();
        
        /* Open communication link to server...
         */
        pbxChannel = new PBXClient( serverName, serverPort, this );
        pbxChannel.start ();

        /* Instantiate connection monitor timer (for reconnect supervision)
         */
        mainTimer = new Timer( 1000, this );
        mainTimer.start ();

        Log.trace( "Created instance of the " + this.getClass().toString () );
    }

    /////////////////////////////////////////////////////////// GUI LAYOUT ///////////////
    
    /**
     *  Creates layout of the GUI components
     */
    private void createLayout ()
    {
        /*  Upper: status, inputMsg | buttons | idLabel, userId 
         *  Lower: scrolled logArea 
         */
        GroupLayout layout = new GroupLayout( getContentPane () );
        getContentPane().setLayout( layout );
        layout.setAutoCreateContainerGaps( true );
        layout.setAutoCreateGaps( true );
        
        JScrollPane logPane = new JScrollPane ();
        logPane.setViewportView( logArea );

        JSeparator vertS1 = new JSeparator( JSeparator.VERTICAL );
        JSeparator vertS2 = new JSeparator( JSeparator.VERTICAL );

        int textH = 26; // fixed text field height
        int iconH = 32; // fixed icon height
        int iconW = 32; // fixed icon width
        
        layout.setHorizontalGroup
        (
            layout
                .createParallelGroup( GroupLayout.Alignment.LEADING )
                .addGroup
                ( 
                    layout
                        .createSequentialGroup ()
                        .addGroup
                        ( 
                            layout
                                .createParallelGroup( GroupLayout.Alignment.LEADING )
                                .addGroup
                                (
                                    GroupLayout.Alignment.TRAILING, 
                                    layout
                                        .createSequentialGroup ()
                                        .addComponent( securityState )
                                        .addGap( 5 )
                                        .addComponent( imsgLabel )
                                        .addGap( 0 )
                                        .addComponent( inputMsg )
                                        .addComponent( sendButton, iconW, iconW, iconW )
                                        .addGap( 15 )
                                        .addComponent( vertS1, 4, 4, 4 )
                                        .addGap( 10 )
                                        .addComponent( listPeersButton, iconW, iconW, iconW )
                                        .addComponent( dialButton, iconW, iconW, iconW )
                                        .addComponent( secureDialButton, iconW, iconW, iconW )
                                        .addComponent( hangupButton, iconW, iconW, iconW )
                                        .addGap( 15 )
                                        // .addComponent( autoAnswer )
                                        // .addGap( 10 )
                                        .addComponent( vertS2, 4, 4, 4 )
                                        .addGap( 10 )
                                        .addComponent( idLabel )
                                        .addGap( 5 )
                                        .addComponent( userId )
                                )
                                .addComponent( logPane )
                        )
                )
        );
        
        layout.setVerticalGroup
        (
            layout
                .createParallelGroup( GroupLayout.Alignment.LEADING )
                .addGroup
                (
                    layout
                        .createSequentialGroup ()
                        .addGroup
                        (
                            layout
                                .createParallelGroup( GroupLayout.Alignment.CENTER )
                                .addComponent( securityState )
                                .addComponent( imsgLabel )
                                .addComponent( inputMsg, textH, textH, textH )
                                .addComponent( sendButton, iconH, iconH, iconH )
                                .addComponent( vertS1, iconH, iconH, iconH )
                                .addComponent( listPeersButton, iconH, iconH, iconH )
                                .addComponent( dialButton, iconH, iconH, iconH )
                                .addComponent( secureDialButton, iconH, iconH, iconH )
                                .addComponent( hangupButton, iconH, iconH, iconH )
                                .addComponent( vertS2, iconH, iconH, iconH )
                                .addComponent( idLabel )
                                .addComponent( userId, textH, textH, textH )
                                //.addComponent( autoAnswer )
                        )
                        .addComponent( logPane )
                )
        );
        
        pack();
    }

    /////////////////////////////////////////////////////////// Event Listeners //////////
    
    /**
     *  Creates event listeners
     */
    private void createEventListeners ()
    {
        addWindowListener( new java.awt.event.WindowAdapter () {
            public void windowClosing( java.awt.event.WindowEvent evt ) {
                formWindowClosing( evt );
            }
        } );

        inputMsg.addKeyListener( this );
        userId.addKeyListener( this );
        autoAnswer.addKeyListener( this );
        logArea.addKeyListener( this );

        sendButton.addKeyListener( this );
        listPeersButton.addKeyListener( this );
        dialButton.addKeyListener( this );
        secureDialButton.addKeyListener( this );
        hangupButton.addKeyListener( this );
        
        sendButton.addActionListener( this );
        listPeersButton.addActionListener( this );
        dialButton.addActionListener( this );
        secureDialButton.addActionListener( this );
        hangupButton.addActionListener( this );

        inputMsg.addFocusListener(
                new FocusListener () {
                    public void focusGained( FocusEvent evt ) {
                        inputMsg.selectAll ();
                    }
                    public void focusLost( FocusEvent evt ) {
                    }
                }
            );

        userId.addFocusListener(
                new FocusListener () {
                    public void focusGained( FocusEvent evt ) {
                        userId.selectAll ();
                    }
                    public void focusLost( FocusEvent evt ) {
                        synchronized( userId ) {
                            userId.setText( getUserId () );
                        }
                    }
                }
            );
    }

    /////////////////////////////////////////////////////////// Audio & UDP services /////
    
    /**
     *  Starts audio interface and UDP peer-to-peer channel.
     */
    public void startKryptofonServices ()
    {
        audioInterface = new audio.AudioInterfacePCM ();
        udpChannel = new DatagramChannel( localUdpPort );
        
        /* Now, check if UDP channel is bound to the the local port that we requested.
         * If not, it means that we have multiple instance of the CryptoPhoneApp 
         * running possibly with the same user ID. To fix this, we're going to 
         * adjust our user ID with the suffix containing distance from the originally
         * requested UDP port, e.g. if we've requested port 47000 and now bound to 47001,
         * then we will adjust original 'username' to be 'username-2'.
         * This helps testing when starting multiple instances of the CryptoPhoneApp
         * on the same single-user machine (like Windows or Mac). 
         */
        int udpDifference = udpChannel.getLocalPort () - localUdpPort;
        if ( udpDifference > 0 ) {
            userId.setText( getUserId() + "-" + ( udpDifference + 1 ) );
            Point winPos = getLocation ();
            winPos.x += 40 * udpDifference; winPos.y += 40 * udpDifference;
            setLocation( winPos );
        }
    }
    
    /**
     *  Stops audio interface and UDP peer-to-peer channel.
     */
    public void stopKryptofonServices ()
    {
        /* Clear existing call, if any
         */
        executeCommand( ":bye", null );

        /* Stop listening UDP
         */
        if ( udpChannel != null ) {
            udpChannel.stop ();
        }

        /* Stop audio services
         */
        if ( audioInterface != null ) {
            audioInterface.stopPlay ();
            audioInterface.stopRecording ();
            audioInterface.cleanUp ();
        }

        /* Done
         */
        udpChannel = null;
        audioInterface = null;
    }

    /////////////////////////////////////////////////////////// GUI Events ///////////////
    
    /**
     *  Parses input text entered by the user in inputMsg.
     */
    private void sendButton_Clicked () 
    {
        if ( defaultInputMsg.equalsIgnoreCase( inputMsg.getText () ) ) { 
            return; // ignore default input message
        }

        parseInputMessage ();
    }

    /**
     *  Performs :list command.
     */
    private void listPeersButton_Clicked () 
    {
        executeCommand( ":list", null );
    }

    /**
     *  Performs :invite command.
     */
    private void dialButton_Clicked () 
    {
        /* If there is outstanding INVITE waiting to be accepted,
         * do accept incoming call instead of making new outgoing call.
         */
        if ( this.lastMessageFromPBX == null ) {
            executeCommand( ":invite", tokenizeInputMessage () );
        } else {
            acceptIncomingCall( /*secured*/ false );
        }
    }

    /**
     *  Performs :invite+ command.
     */
    private void secureDialButton_Clicked () 
    {
        /* If there is outstanding INVITE waiting to be accepted,
         * do accept incoming call instead of making new outgoing call.
         */
        if ( this.lastMessageFromPBX == null ) {
            executeCommand( ":invite+", tokenizeInputMessage () );
        } else {
            acceptIncomingCall( /*secured*/ true );
        }
    }

    /**
     *  Performs :bye command.
     */
    private void hangupButton_Clicked () 
    {
        executeCommand( ":bye", tokenizeInputMessage () );
    }

    /**
     *  Closes application by gracefully terminating all threads.
     */
    private void formWindowClosing( WindowEvent evt )
    {
        stopKryptofonServices ();
        
        if ( pbxChannel != null ) {
            pbxChannel.close ();
        }

        System.exit( 0 );
    }

    /**
     *  Implements <code>KeyListener</code>'s key pressed event.
     *  Parses input message on ENTER (the same action as it was sendButton_Clicked).
     */
    public void keyPressed( KeyEvent ke ) {
        
        int keyCode = ke.getKeyCode ();
        
        if( ke.getSource () == inputMsg && keyCode == KeyEvent.VK_ENTER )
        {
            if ( defaultInputMsg.equalsIgnoreCase( inputMsg.getText () ) ) { 
                return; // ignore default input message
            }
            
            parseInputMessage ();
        }
        else if( ke.getSource () == userId && keyCode == KeyEvent.VK_ENTER )
        {
            inputMsg.requestFocus ();
        }
        else if( keyCode == KeyEvent.VK_F1 )
        {
            executeCommand( ":help", null );
        }
    }

    /**
     *  Implements <code>KeyListener</code>'s key released event.
     */
    public void keyReleased( KeyEvent ke )
    {
        /* unused */
    }
    
    /**
     *  Implements <code>KeyListener</code>'s key typed event.
     */
    public void keyTyped( KeyEvent ke )
    {
        /* unused */
    }

    /**
     *  Handles events from Swing timer and buttons.
     */
    public void actionPerformed( ActionEvent ae )
    {
        if ( ae.getSource () == mainTimer ) {
            mainTimerEvent ();
        } else if ( ae.getSource () == sendButton ) {
            sendButton_Clicked ();
        } else if ( ae.getSource () == listPeersButton ) {
            listPeersButton_Clicked ();
        } else if ( ae.getSource () == dialButton ) {
            dialButton_Clicked ();
        } else if ( ae.getSource () == secureDialButton ) {
            secureDialButton_Clicked ();
        } else if ( ae.getSource () == hangupButton ) {
            hangupButton_Clicked ();
        }
    }

    /**
     *  Handles events from application's mainTimer (an instance of the Swing timer).
     *  It monitors status of:
     *  
     *   -#  PBXClient connection 
     *   -#  connection to remote peer (if any)
     *   -#  awaiting acknowledgment for our last invite message (if any) 
     *  
     *  If PBXClient connection is detected to be down, the procedure will try to 
     *  reconnect to chat server after some period of time. If reconnection retry 
     *  count exceeded maximum, timer will stop retrying.
     *  
     *  In case of dead remote peer (not sending UDP packets to us), 
     *  udpChannel.isPearDead() timer will clear down the call.
     *  
     *  In case of unacknowledged invite message, inviteTimeout timer will 
     *  cancel current inviting.
     */
    private void mainTimerEvent ()
    {
        //////////////////////////////////////////////////////////////////////////////////
        /* Monitor remote peer's status
         */
        RemotePeer peer = udpChannel.getRemotePeer();
        if ( monitorIfPeerIsSendingVoice 
                && peer != null && udpChannel.isPearDead( /*timeout-millis*/ 2500 ) ) 
        {
            monitorIfPeerIsSendingVoice = false;
            
            report( "logWarn", "Warning: Not receiving voice from '" 
                    + peer.getRemoteUserId () + "'; Maybe it's dead?" );
        }

        //////////////////////////////////////////////////////////////////////////////////
        /* Monitor the last issued invite
         */
        if ( inviteTimeout >= 0 ) // invite timeout is active
        {
            if ( --inviteTimeout < 0 ) // invite timeout reached 
            {
                report( "logError", "It seems that kryptofon user '" + currentInvite 
                        + "' is not connected." );
                report( "logInfo", "Use :list to query available users..." );
                logMessage( "<hr/>" );

                inviteTimeout = -1;
                currentInvite = null;
            }
        }
        
        //////////////////////////////////////////////////////////////////////////////////
        /* Monitor current connection.
         */
        final int maxRetryCount = 3;
        final int reconnectDelay = 2;
        
        if ( pbxChannel != null && pbxChannel.isAlive () ) {
            reconnectTimeout = -1; // disables timer
            return;
        }

        if ( reconnectRetryCount >= maxRetryCount ) {

            if ( reconnectRetryCount == maxRetryCount ) {
                
                ++reconnectRetryCount;
                
                logMessage( "<hr/><div class='logDiv'>"
                    + "<span class='logError'>Press ENTER to quit or type<br/><br/>"
                    + "&nbsp;&nbsp; :open [ &lt;hostname&gt; [ &lt;port&gt; ] ]<br/><br/>"
                    + "to open new connection...</span><br/><br/></div>" 
                    );
                setPbxStatus( "Dead" );
                
                /* Reset defaults */
                serverName = "atlas.dsv.su.se";
                serverPort = 9494;
            }
            
            return; // Leave to the user to quit by pressing ENTER
        } 
            
        if ( reconnectTimeout < 0 )  {
            setPbxStatus( "Disconnected" );
            logMessage( "<hr/><div class='logDiv'>Reconnecting in " 
                    + reconnectDelay + " seconds...</div>"
                    );
            reconnectTimeout = reconnectDelay; // start timer
            return;
        }
        
        if ( --reconnectTimeout > 0 ) {
            return;
        }
        
        logMessage( "<div class='logDiv'>Retry #"
               + ( ++reconnectRetryCount ) 
               + " of max " 
               + maxRetryCount
               + ":<br/></div>"
               );

        reconnectTimeout = -1; // disables timer and restarts new connection

        pbxChannel = new PBXClient( serverName, serverPort , this );
        pbxChannel.start ();
    }

    /////////////////////////////////////////////////////////// Log Area Messages ////////

    /**
     *  Logs message formated with limited HTML (limited because of JEditorPane)
     */
    public void logMessage( final String str )
    {
        java.awt.EventQueue.invokeLater( 
                new Runnable() {
                    public void run() {
                        synchronized( logArea )
                        {
                            /* Append the string to the end of <body> element...
                             */
                            String html = logArea.getText ();
                            html = html.replace( "</body>", str + "\n</body>" );
                            logArea.setText( html );
                        }
                    }
                }
            );
    }

    /////////////////////////////////////////////////////////// PBXClient.Context ////////
    
    /**
     *  Displays attention messages to the user from Log subsystem.
     */
    @Override
    public void attention( String message ) 
    {
        if ( message.startsWith( "Error" ) ) {
            report( "logError", message );
        } else {
            report( "logInfo", message );
        }
    }
    
    /////////////////////////////////////////////////////////// PBXClient.Context ////////

    /**
     *  Returns configured user ID (username).
     */
    @Override
    public String getUserId ()
    {
        String newId = null;
        
        synchronized( userId )
        {
            String oldId = userId.getText ().trim();

            /* Default user id, if not specified */
            if ( oldId.isEmpty () ) {
                oldId = System.getProperty( "user.name" );
            }
            
            /* Ouch. Again empty. Then we are really an anonymous */
            if ( oldId.isEmpty () ) {
                oldId = "[Anonymous]";
            }

            /* Discard spaces from the local userId */
            newId = oldId.replaceAll( "\\s{1,}", "-" );
        }
        
        return newId;
    }

    /**
     *  Updates status message by updating window title
     */
    @Override
    public void setPbxStatus( String str )
    {
        final String strCopy = new String( str );
        
        java.awt.EventQueue.invokeLater( 
                new Runnable() {
                    public void run() {
                        synchronized( pbxChannelStatus )
                        {
                            pbxChannelStatus = appTitle + "; " + strCopy;
                            setTitle( pbxChannelStatus );
                        }
                    }
                }
            );
    }

    /**
     *  Reports a system message to log 
     */
    @Override
    public void report( String cssClass, String str )
    {
        logMessage(
            "<div class='logDiv'>" + Log.nowMillis () 
                     + "&nbsp;&nbsp;<span class='" + cssClass + "'>"
                     + Log.EscapeHTML( str )
                     + "</span></div>"
            );
    }

    /**
     *  Reports incoming message
     */
    @Override
    public void reportIncomingTextMessage( String cssClass, 
            String userId, String message )
    {
        logMessage(
                "<div class='logDiv'>" + Log.nowMillis () 
                         + "&nbsp;&nbsp;" 
                         + Log.EscapeHTML( userId ).trim () 
                         + ": <span class='" + cssClass + "'>"
                         + Log.EscapeHTML( message ).trim ()
                         + "</span></div>"
                );
    }
    
    /**
     *  Handles on INVITE call-back from the PBXClient.
     */
    @Override
    public void onInvite( final PBXClient.ControlMessage m )
    {
        java.awt.EventQueue.invokeLater( 
                new Runnable() {
                    public void run() {
                        deferredOnInvite( m );
                    }
                }
            );
    }
    
    /**
     *  Handles on RING call-back from the PBXClient.
     */
    @Override
    public void onRing( final PBXClient.ControlMessage m )
    {
        java.awt.EventQueue.invokeLater( 
                new Runnable() {
                    public void run() {
                        deferredOnRing( m );
                    }
                }
            );
    }
    
    /**
     *  Handles on ACCEPT call-back from the PBXClient.
     */
    @Override
    public void onAccept( final PBXClient.ControlMessage m )
    {
        java.awt.EventQueue.invokeLater( 
                new Runnable() {
                    public void run() {
                        deferredOnAccept( m );
                    }
                }
            );
    }
    
    /**
     *  Handles on BYE call-back from the PBXClient.
     */
    @Override
    public void onBye( final PBXClient.ControlMessage m )
    {
        java.awt.EventQueue.invokeLater( 
                new Runnable() {
                    public void run() {
                        deferredOnBye( m );
                    }
                }
            );
    }
    
    /**
     *  Handles on IMSG call-back from the PBXClient.
     */
    @Override
    public void onInstantMessage( final PBXClient.ControlMessage m )
    {
        java.awt.EventQueue.invokeLater( 
                new Runnable() {
                    public void run() {
                        deferredOnInstantMessage( m );
                    }
                }
            );
    }
    
    /////////////////////////////////////////////////////////// Command Line parsers /////

    /**
     *  Tokenizes inputMsg into words and returns array of strings.
     */
    private String[] tokenizeInputMessage ()
    {
        String message = inputMsg.getText();
        
        if ( defaultInputMsg.equalsIgnoreCase( message ) ) { 
            return new String[0]; // ignore default input message
        }

        /* Split string into words, removing all leading, trailing 
         * and superfluous white-spaces between words
         */
        String[] words = message.trim().split( "\\s{1,}" );
        
        if ( words.length >= 1 && words[0].isEmpty () ) {
            return new String[0]; // if contains empty words, then it is really empty
        }

        return words;
    }

    /**
     *  Parses input message from inputMsg and sends it to chat server. If it is 
     *  a command (the first word is prefixed with ':' character) it spawns 
     *  executeCommand() for further parsing.
     */
    private void parseInputMessage ()
    {
        /* Split string into words, removing all leading, trailing 
         * and superfluous white-spaces between words
         */
        String[] words = tokenizeInputMessage ();
        
        String cmd = words.length >= 1 ? words[0].toLowerCase () : "";

        /* If it is a command, parse it separately
         */
        if ( cmd.startsWith( ":" ) ) 
        {
            int argCount = words.length - 1;
            int argOffset = 1;
            
            /* If ":" is alone (i.e. separated by whitespace from the next word), 
             * then join it with the next word.
             */
            if ( cmd.equals( ":" ) && words.length >= 2 ) {
                cmd = cmd + words[1];
                argCount = words.length - 2;
                argOffset = 2;
            }

            /* Prepare command arguments... */
            String[] args = new String[ argCount ];
            System.arraycopy( words, argOffset, args, 0, argCount );
            
            /* ... then execute command */
            if ( executeCommand( cmd, args ) ) {
                inputMsg.setText( "" );
            }
            return;
        }

        if ( pbxChannel == null || ! pbxChannel.isAlive () ) // confirms dead-server question
        {
            formWindowClosing( null );
            System.exit( 0 );
        }

        /* Default task: send message to chat server
         */
        String inputInstantMessage = inputMsg.getText ();

        if ( defaultInputMsg.equalsIgnoreCase( inputInstantMessage ) ) 
        { 
            /* ignore default input message */
            return;
        }
        else if ( getUserId().isEmpty () ) // no user id
        {
            pbxChannel.send( inputInstantMessage );
            inputMsg.setText( "" );
            return;
        }

        sendInstantMessage( inputInstantMessage, /*forceUnencrypted*/ false );

        inputMsg.setText( "" );
    }

    /**
     *  Sends chat message (encrypted if we have established secure channel
     *  with the user).
     */
    private void sendInstantMessage( String message, boolean forceUnencrypted )
    {
        /* Get symmetric ciphering engine for PDUs and messages, if any.
         */
        RemotePeer remotePeer = udpChannel.getRemotePeer ();
        SymmetricCipher cipher = udpChannel.getUsedSymmetricCipher ();
        if ( forceUnencrypted || remotePeer == null || cipher == null ) 
        {
            /* Send un-encrypted message if there is no common ciphering engine 
             */
            pbxChannel.send( message, getUserId () );
            inputMsg.setText( "" );
            return;
        }

        /* Send encrypted message to peer and echo the highlighted message back to our 
         * user. Wwe must echo the message because our PBXClient will not catch chat 
         * servers broadcast with the encrypted message, as the instant message will 
         * be explicitly directed to the remote peer. 
         */
        String secret = cipher.encrypt( message );
        if ( secret != null )
        {
            pbxChannel.sendInstantMessage( remotePeer.getRemoteUserId(), secret );
            
            reportIncomingTextMessage( "secureMessage", 
                    getUserId() + " [encrypted]", message ); 
        }
    }

    /**
     *  Parses commands prefixed with ':' character. Recognized commands are:
     *  <pre>
     *  VoIP Calls:
     *  
     *     :inv[ite]    username             aliases: :ca[ll]
     *     :inv[ite]+   username             aliases: :ca[ll]+
     *     :acc[ept]                         aliases: :ans[wer]
     *     :by[e]                            aliases: :ha[ngup]
     *     :shk[ey]
     *     
     *  VoIP Peers:
     *  
     *     :li[st]    [ username-regex ]
     *     
     *  Chat Messages:
     *
     *     :br[oadcast]  message
     *     
     *  Connection to Chat Server:
     *  
     *     :clo[se]
     *     :op[en]    [ host  [ port ] ] 
     *     
     *  Application:
     *     :cl[ear]s[creen]
     *     :reauth
     *     :newsecret  [ algorithm [ keysize ] ]
     *     :du[mp]
     *     :ex[it]                           aliases: :qu[it]
     *     :he[lp]
     *  </pre>
     *  
     *  @param cmd  command name; must begin with ":"
     *  @param args command arguments; may be null or empty array
     *  @return true if command is parsed and executed
     */
    private boolean executeCommand( String cmd, String[] args )
    {
        cmd = cmd.trim().toLowerCase();

        boolean executed = false;

        if ( args == null ) {
            args = new String[0];
        }

        /* Note bellow that we use both equals() and matches() to resolve command.
         * We need equals() to speed-up lookup for those commands that are executed
         * internally by directly calling executeCommand() with hard-coded argument
         * (contrary to when executeCommand() is called with commnands entered by
         * the user).
         */

        /*------------------------------------------------------------------------------*/
        if ( cmd.equals( ":list" ) || cmd.matches( "^:li(st?)?$" ) )
        {
            pbxChannel.sendListPeers( args.length >= 1 ? args[0] : null );

            executed = true;
        }
        /*------------------------------------------------------------------------------*/
        else if ( cmd.equals( ":who" ) )
        {
            pbxChannel.send( "wwhhoo" );

            executed = true;
        }
        /*------------------------------------------------------------------------------*/
        else if ( cmd.equals( ":bye" ) 
               || cmd.matches( "^:by(e)?$" ) 
               || cmd.matches( "^:ha(n(g(up?)?)?)?$" ) )
        {
            RemotePeer remotePeer = udpChannel.getRemotePeer ();

            if ( remotePeer != null ) 
            {
                report( "logInfo", "***** Call Ended *****" );
                logMessage( "<hr/>" );

                /* Send 'bye' message to remote peer
                 */
                pbxChannel.sendBye( remotePeer.getRemoteUserId (),  
                        pbxChannel.getLocalAddress (), udpChannel.getLocalPort () );
            }
            else if ( currentInvite != null )
            {
                report( "logInfo", "Invite cancelled." );
                logMessage( "<hr/>" );

                /* Send 'bye' message to remote peer
                 */
                pbxChannel.sendBye( currentInvite,  
                        pbxChannel.getLocalAddress (), udpChannel.getLocalPort () );
            }
            else if ( this.lastMessageFromPBX != null ) // call waiting to be accepted
            {
                PBXClient.ControlMessage m = this.lastMessageFromPBX;
                
                String verboseRemote = m.getVerboseRemote ();
                
                report( "logInfo", "Rejecting invite from " + verboseRemote );
                logMessage( "<hr/>" );

                /* Send 'bye' message to remote peer
                 */
                pbxChannel.sendBye( m.peerUserId,  
                        pbxChannel.getLocalAddress (), udpChannel.getLocalPort () );
                
                this.lastMessageFromPBX = null;
            }

            udpChannel.removePeer ();
            audioInterface.stopRinging ();
            userId.setEnabled( true ); // enable back changing user ID
            
            lastMessageFromPBX = null;
            remotePublicKey = null;

            securityState.setState( JSecState.State.UNSECURED );
            setTitle( pbxChannelStatus );
            
            inviteTimeout = -1;
            currentInvite = null;

            executed = true;
        }
        /*------------------------------------------------------------------------------*/
        else if ( cmd.equals( ":invite+" )
               || cmd.matches( "^:inv(i(te?)?)?\\+$" ) 
               || cmd.matches( "^:ca(ll?)?\\+$" ) )
        {
            /* Calls another user WITH encryption enabled 
             */
            if ( udpChannel.hasRemotePeer () || currentInvite != null )
            {
                report( "logError", "Call in progress. Hang up first!" );
                
                executed = true;
            }
            else if ( args.length >= 1 && pbxChannel.isAlive () )
            {
                logMessage( "<hr/>" );
                report( "logInfo", "Inviting '" + args[0] 
                        + "' to encrypted voice call..." );

                currentInvite = args[0];
                inviteTimeout = 3;
                
                userId.setEnabled( false ); // disable changing user ID

                pbxChannel.sendInvite( currentInvite, pbxChannel.getLocalAddress (),
                        udpChannel.getLocalPort (), CipherEngine.getSignedPublicKey () );

                executed = true;
            }
        }
        /*------------------------------------------------------------------------------*/
        else if ( cmd.equals( ":invite" )
               || cmd.matches( "^:inv(i(te?)?)?$" ) 
               || cmd.matches( "^:ca(ll?)?$" ) )
        {
            /* Calls another user WITHOUT encryption enabled 
             */
            if ( udpChannel.hasRemotePeer () || currentInvite != null )
            {
                report( "logError", "Call in progress. Hang up first!" );
                
                executed = true;
            }
            else if ( args.length >= 1 && pbxChannel.isAlive () )
            {
                logMessage( "<hr/>" );
                report( "logInfo", "Inviting '" + args[0]
                        + "' to un-encrypted voice call..." );
                
                currentInvite = args[0];
                inviteTimeout = 3;
                
                userId.setEnabled( false ); // disable changing user ID

                pbxChannel.sendInvite( args[0], pbxChannel.getLocalAddress (),
                        udpChannel.getLocalPort (), null );

                executed = true;
            }
        }
        /*------------------------------------------------------------------------------*/
        else if ( cmd.equals( ":accept" )
               || cmd.matches( "^:acc(e(pt?)?)?$" ) 
               || cmd.matches( "^:ans(w(er?)?)?$" ) )
        {
            acceptIncomingCall( /*secured*/ true );

            executed = true;
        }
        /*------------------------------------------------------------------------------*/
        else if ( cmd.equals( ":broadcast" )
               || cmd.matches( "^:br(o(a(d(c(a(st?)?)?)?)?)?)?$" ) )
        {
            if ( args.length >= 1 ) 
            {
                StringBuffer msg = new StringBuffer ();
                for ( String s : args ) {
                    if ( msg.length () > 0 ) {
                        msg.append( " " );
                    }
                    msg.append( s );
                }
                
                sendInstantMessage( msg.toString () , /*forceUnencrypted*/ true );
    
                executed = true;
            }
        }
        /*------------------------------------------------------------------------------*/
        else if ( cmd.equals( ":mykey" )
               || cmd.matches( "^:my(k(ey?)?)?$" ) )
        {
            sendInstantMessage( "\f========= BEGIN PUBLIC KEY =========\f\f"
                    + CipherEngine.getNamedPublicKey ()
                    + "\f\f========= END PUBLIC KEY ==========="
                    , /*forceUnencrypted*/ false );
        }
        /*------------------------------------------------------------------------------*/
        else if ( cmd.equals( ":close" )
               || cmd.matches( "^:clo(se?)?$" ) )
        {
            logMessage( "<hr/>" );
            reconnectRetryCount = Integer.MAX_VALUE; // suppresses reconnection
            pbxChannel.close ();

            executed = true;
        }
        /*------------------------------------------------------------------------------*/
        else if ( cmd.equals( ":open" )
               || cmd.matches( "^:op(en?)?$" ) )
        {
            /* Opens new connection to chat server
             */
            if ( args.length >= 1 )
            {
                /* Parse arguments first: host name and port
                 */
                serverName = args[0];
                serverPort = 2000; // the default port
                
                if( args.length >= 2 ) try {
                    serverPort = Integer.parseInt( args[1] );
                } catch ( NumberFormatException e ) {
                    report( "logError", "The port must be integer." );
                    return false;
                }
            }
            
            logMessage( "<hr/>" );

            reconnectRetryCount = Integer.MAX_VALUE; // suppresses reconnection
            pbxChannel.close ();
            
            pbxChannel = new PBXClient( serverName, serverPort , this );
            pbxChannel.start ();
            reconnectRetryCount = 0;

            executed = true;
        }
        /*------------------------------------------------------------------------------*/
        else if ( cmd.equals( ":exit" )
               || cmd.matches( "^:ex(it?)?$" ) || cmd.matches( "^:qu(it?)?$" ) )
        {
            stopKryptofonServices ();
            pbxChannel.close ();
            System.exit( 0 );
        }
        /*------------------------------------------------------------------------------*/
        else if ( cmd.equals( ":reauth" ) )
        {
            CipherEngine.reloadAuthorizedPublicKeys ();
            
            executed = true;
        }
        /*------------------------------------------------------------------------------*/
        else if ( cmd.equals( ":newsecret" ) )
        {
            String algorithm = "Blowfish";
            int keySize = 32;
            
            if ( args.length >= 1 ) {
                algorithm = args[0];
            }

            if ( args.length >= 2 ) {
                try {
                    keySize = Integer.parseInt( args[1] );
                } catch ( NumberFormatException e ) {
                    report( "logError", "The key size must be integer." );
                    return false;
                }
            }

            if ( ! CipherEngine.generateNewSecret( algorithm, keySize, /*verbose*/true ) ) 
            {
                /* fail back to blowfish (not to leave user without symmetric cipher)
                 */
                CipherEngine.generateNewSecret( "Blowfish", 32, /*verbose*/false ); 
                return false;
            }

            executed = true;
        }
        /*------------------------------------------------------------------------------*/
        else if ( cmd.equals( ":cls" )
               || cmd.matches( "^:cl(ear)?s(c(r(e(en?)?)?)?)?$" ) )
        {
            clearLogArea (); // clears screen
            displayUsage ();

            executed = true;
        }
        /*------------------------------------------------------------------------------*/
        else if ( cmd.equals( ":help" )
               || cmd.matches( "^:he(lp?)?$" ) )
        {
            displayHelp ();

            executed = true;
        }
        /*------------------------------------------------------------------------------*/
        else if ( cmd.equals( ":dump" )
               || cmd.matches( "^:du(mp?)?$" ) )
        {
            dumpLogArea( args.length >= 1 ? args[0] : null );

            executed = true;
        }
        /*------------------------------------------------------------------------------*/
        
        return executed;
    }

    //////////////////////////////////////////////////////////// PBX functionality ///////
    
    /**
     *  On INVITE call-back is triggered when PBXClient receives inviting message
     *  (indicating that someone is calling us).
     *  
     *  It starts ringing and if the application is in auto-answer mode, it
     *  accepts incoming call immediately. Otherwise it presents alerting message
     *  to the user with information about who's calling.
     */
    public void deferredOnInvite( final PBXClient.ControlMessage m )
    {
        if ( m.peerPort < 1 || m.peerPort > 65535 ) {
            return;
        }
        
        String verboseRemote = m.getVerboseRemote ();

        /* Reject new calls if we already have the call in progress
         */
        if ( udpChannel.hasRemotePeer () ) 
        {
            /* Send 'bye' message to remote peer who is inviting us
             */
            pbxChannel.sendBye( m.peerUserId, "0.0.0.0", 0 );  
            return;
        }

        this.lastMessageFromPBX = m;
        
        logMessage( "<hr/>" );
        report( "logInfo", "User " + verboseRemote + " is inviting us..." );

        if ( m.secret == null ) {
            setTitle( appTitle + "; Incoming PLAIN call from " + verboseRemote ); 
        } else {
            setTitle( appTitle + "; Incoming ENCRYPTED call from " + verboseRemote ); 
        }
        
        this.audioInterface.startRinging ();
        userId.setEnabled( false ); // disable changing user ID

        if ( autoAnswer.isSelected () )
        {
            report( "logInfo", "Auto-answering the call..." );
            acceptIncomingCall( /*secured*/ true );
        }
        else
        {
            tryToVerifyInvitingCall( /*silent*/ false );
            report( "logInfo", "Respond with :accept to answer the call!" );
            
            /* Send 'ringing' message to remote peer with our public key
             */
            pbxChannel.sendRing( m.peerUserId,  pbxChannel.getLocalAddress (), 
                    udpChannel.getLocalPort (), CipherEngine.getSignedPublicKey () );
        }
    }

    /**
     *  On RING call-back is triggered when PBXClient receives ringing message
     *  (indicating that the peer is alerting end-user). 
     */
    public void deferredOnRing( final PBXClient.ControlMessage m )
    {
        if ( m.peerPort < 1 || m.peerPort > 65535 ) {
            return;
        }
        
        String verboseRemote = m.getVerboseRemote ();
        
        /* Ignore the message if we already have the call in progress.
         */
        if ( udpChannel.hasRemotePeer () ) {
            return;
        }
        
        /* Ignore the message if the remote peer is not that we are inviting to a call
         */
        if ( currentInvite == null || ! currentInvite.equalsIgnoreCase( m.peerUserId ) ) {
            return;
        }

        /* Deserialize remote public key.
         * Deserialization also verifies the public key against authorized keys.
         * \see constructor of the PublicEncryptor
         */
        remotePublicKey = null;

        if ( m.secret != null ) 
        {
            remotePublicKey = new PublicEncryptor( m.secret, m.peerUserId );
            if ( ! remotePublicKey.isActive () ) {
                remotePublicKey = null;
            }
        }

        /* Cancel inviteTimeout timer and give information and ringing tone to our user
         */
        report( "logInfo", "User " + verboseRemote + " is alerted..." );
        
        if ( remotePublicKey != null && remotePublicKey.isActive () )
        {
            if ( ! remotePublicKey.isVerified () ) 
            {
                report( "logError", "Reply from " + verboseRemote
                        + " could not be authenticated." );
            }
            else 
            {
                report( "logOk", "Reply from " + verboseRemote
                        + " authenticated with public key '" 
                        + remotePublicKey.getVerificatorName () + "'" );
            }
        }

        inviteTimeout = -1; // Reset only invite timeout (do not reset currentInvite)

        this.audioInterface.startRinging ();
        userId.setEnabled( false ); // disable changing user ID
    }

    /**
     *  On ACCEPT call-back is triggered when PBXClient receives accepting message
     *  (indicating that the peer has accepted our invite).
     *  
     *  It deserializes encrypted secret key used for peer-to-peer communication
     *  (if any) and creates instance for the call (CallContext class) as well the remote 
     *  peer (RemotePeer class), and binds them to specific CODEC (of the audio interface) 
     *  and the UDP channel (for voice transmission). 
     *  
     *  The call is finally established at this point.
     */
    public void deferredOnAccept( final PBXClient.ControlMessage m )
    {
        if ( m.peerPort < 1 || m.peerPort > 65535 ) {
            return;
        }
        
        String verboseRemote = m.getVerboseRemote ();
        
        /* Ignore message if we already have the call in progress.
         */
        if ( udpChannel.hasRemotePeer () ) {
            return;
        }
        
        inviteTimeout = -1;
        currentInvite = null;
        
        /* Resolve peers IP address
         */
        InetAddress peerAddr = null;
        try {
            peerAddr = InetAddress.getByName( m.peerAddr );
        } catch( UnknownHostException e ) {
            Log.exception( Log.ERROR, e );
            this.lastMessageFromPBX = null;
            report( "logError", "Unknown remote host '" + m.peerAddr 
                    + "'; clearing the call..." );
            return;
        }

        report( "logInfo", "User " + verboseRemote + " has accepted our invite" );
        
        /* Deserialize encrypted remote secret key and decrypt it with our private key
         */
        SymmetricCipher cipher = null;
        udpChannel.useSymmetricCipher( null );

        if ( m.secret != null ) 
        {
            cipher = CipherEngine.deserializeEncryptedSecretKey( m.secret );
            
            if ( cipher.isActive () ) {
                udpChannel.useSymmetricCipher( cipher );
            } else {
                cipher = null;
            }
        }

        /* Create necessary objects needed to establish the call
         */
        RemotePeer remotePeer = new RemotePeer( this.udpChannel, m.peerUserId, 
                peerAddr, m.peerPort );
        
        AudioInterface codec = this.audioInterface.getByFormat( VoicePDU.ALAW );
        
        CallContext call = new CallContext( remotePeer, codec );
        call.setCallEstablished( true );
        monitorIfPeerIsSendingVoice = true;

        if ( cipher != null && cipher.isActive () )
        {
            if ( ! cipher.isVerified () ) 
            {
                securityState.setState( JSecState.State.UNVERIFIED );
                report( "logError", "Secret key from " + verboseRemote
                        + " could not be authenticated." );
            }
            else 
            {
                securityState.setState( JSecState.State.VERIFIED );
                report( "logOk", "Secret key from " + verboseRemote
                        + " authenticated with public key '" 
                        + cipher.getVerificatorName () + "'" );
            }
            
            report( "logOk", "***** Encrypted call established *****" );
            setTitle( appTitle + "; Established ENCRYPTED call with " + verboseRemote );
        }
        else
        {
            securityState.setState( JSecState.State.UNSECURED );
            report( "logError", "***** Un-encrypted call established *****" );
            setTitle( appTitle + "; Established PLAIN call with " + verboseRemote ); 
        }
    }
    
    /**
     *  On BYE call-back is triggered when PBXClient receives 'bye' message
     *  (indicating that the peer is clearing i.e. hanging-up the call). 
     */
    public void deferredOnBye( final PBXClient.ControlMessage m )
    {
        String verboseRemote = m.getVerboseRemote ();
        
        /* If we do not have a call, than remote has rejected our earlier invite
         */
        if ( ! udpChannel.hasRemotePeer () && currentInvite != null ) 
        {
            report( "logInfo", "User " + verboseRemote + " rejected our invite" );
        }
        else /* otheresie, it is a hang-up of the existing call */
        {
            report( "logInfo", "User " + verboseRemote + " is clearing the call" );
            udpChannel.removePeer ();
        }

        audioInterface.stopRinging ();
        userId.setEnabled( true ); // enable back changing user ID
        
        lastMessageFromPBX = null;
        remotePublicKey = null;
        
        report( "logInfo", "***** Call Ended *****" );
        logMessage( "<hr/>" );
        
        securityState.setState( JSecState.State.UNSECURED );
        setTitle( pbxChannelStatus );
        
        inviteTimeout = -1;
        currentInvite = null;
    }
    
    /**
     *  On IMSG call-back is triggered when PBXClient receives private
     *  instant message (encrypted with session's secret key).
     */
    public void deferredOnInstantMessage( final PBXClient.ControlMessage m )
    {
        /* Get symmetric ciphering engine for PDUs and messages (if any).
         */
        SymmetricCipher cipher = udpChannel.getUsedSymmetricCipher ();
        if ( cipher == null ) {
            return;
        }

        /* Decrypt instant message and display highlighted contents to the user
         */
        String clearText = cipher.decrypt( m.secret );
        if ( clearText != null ) 
        {
            /* 
             */
            reportIncomingTextMessage( "secureMessage", 
                    m.peerUserId + " [encrypted]", clearText ); 
        }
    }
    
    /**
     *  Verifies invitor's public key (signed by invitor's private key) 
     *  against the public keys from authorized keys file.
     */
    private PublicEncryptor tryToVerifyInvitingCall( boolean silent )
    {
        if ( this.lastMessageFromPBX == null ) { // There is no INVITE to accept
            return null;
        }
        
        PBXClient.ControlMessage m = this.lastMessageFromPBX;
        String remoteId = m.getVerboseRemote ();

        /* Can accept only if there is no call in progress.
         */
        if ( udpChannel.hasRemotePeer () ) {
            return null;
        }
        
        /* Deserialize remote public key.
         * Deserialization also verifies the public key against authorized keys.
         * \see constructor of the PublicEncryptor
         */
        PublicEncryptor pubKey = null;
        
        if ( m.secret != null ) 
        {
            pubKey = new PublicEncryptor( m.secret, m.peerUserId );
            if ( ! pubKey.isActive () ) {
                pubKey = null;
            }
        }

        if ( silent ) {
            return pubKey;
        }
        
        /* If not silent, be loud...
         */
        if ( pubKey != null && pubKey.isActive () )
        {
            if ( ! pubKey.isVerified () ) 
            {
                securityState.setState( JSecState.State.UNVERIFIED );
                report( "logError", "Invite from " + remoteId
                        + " could not be authenticated." );
            }
            else 
            {
                securityState.setState( JSecState.State.VERIFIED );
                report( "logOk", "Invite from " + remoteId
                        + " authenticated with public key '" 
                        + pubKey.getVerificatorName () + "'" );
            }
        }
        else
        {
            securityState.setState( JSecState.State.UNSECURED );
            report( "logError", "The call will be without encryption." );
        }

        return pubKey;
    }

    /**
     *  Accepts incoming invite. Procedure is called either automatically from 
     *  deferredOnInvite() when the auto-answer is turned on, 
     *  or manually by the user from :accept command).
     *  
     *  It first verifies invitor's public key (signed by invitor's private key) 
     *  against the public keys from authorized keys file.
     *  
     *  It then signs local secret key with the local private key, then 
     *  encrypts signed secret key with our with verified invitor's public key 
     *  and serializes it as Base64 string.
     *  
     *  It sends ACCEPTING message to the peer with the
     *  information how to rich us (local IP address and UDP port as well serialized
     *  encrypted secret key) and signed/encrypted/encoded secret key. 
     *  
     *  At the end it creates instance for the call (CallContext class) and the remote 
     *  peer (RemotePeer class), and binds them to specific CODEC of the audio interface 
     *  and UDP channel.
     *  
     *  The call is finally established at this point.
     */
    private void acceptIncomingCall( boolean securedIfPossible )
    {
        if ( this.lastMessageFromPBX == null ) { // There is no INVITE to accept
            return;
        }
        
        PBXClient.ControlMessage m = this.lastMessageFromPBX;
        String verboseRemote = m.getVerboseRemote ();

        /* Can accept only if there is no call in progress.
         */
        if ( udpChannel.hasRemotePeer () ) {
            return;
        }
        
        /* Resolve peers IP address
         */
        InetAddress peerAddr = null;
        try {
            peerAddr = InetAddress.getByName( m.peerAddr );
        } catch( UnknownHostException e ) {
            Log.exception( Log.ERROR, e );
            this.lastMessageFromPBX = null;
            report( "logError", "Unknown remote host '" + m.peerAddr 
                    + "'; clearing the call..." );
            return;
        }

        /* Deserialize remote public key and encrypt our secret key with it.
         * Deserialization also verifies the public key against authorized keys.
         * \see constructor of the PublicEncryptor
         */
        String mySecret = null;
        remotePublicKey = null;
        udpChannel.useSymmetricCipher( null );
        
        if ( securedIfPossible )
        {
            remotePublicKey = tryToVerifyInvitingCall( /*silent*/ true );
            
            if ( remotePublicKey != null && remotePublicKey.isActive () ) 
            {
                mySecret = remotePublicKey.encryptAndSerialize( CipherEngine.getSignedSecretKey() );
                
                udpChannel.useSymmetricCipher( CipherEngine.getCipher () );
            }
        }

        /* Send accepting message to remote peer
         */
        pbxChannel.sendAccept( m.peerUserId,  pbxChannel.getLocalAddress (), 
                udpChannel.getLocalPort (), mySecret );

        /* Create necessary objects needed to establish the call:
         * instances of the RemotePeer and CallContext. 
         */
        RemotePeer remotePeer = new RemotePeer( this.udpChannel, 
                m.peerUserId, peerAddr, m.peerPort );
        
        AudioInterface codec = this.audioInterface.getByFormat( VoicePDU.ALAW );
        
        CallContext call = new CallContext( remotePeer, codec );
        call.setCallEstablished( true );
        monitorIfPeerIsSendingVoice = true;
        
        /* Report what we've done.
         */
        if ( remotePublicKey != null && remotePublicKey.isActive () )
        {
            if ( ! remotePublicKey.isVerified () ) {
                securityState.setState( JSecState.State.UNVERIFIED );
            } else  {
                securityState.setState( JSecState.State.VERIFIED );
            }
            
            report( "logOk", "***** Encrypted call established *****" );
            setTitle( appTitle + "; Established ENCRYPTED call with " + verboseRemote ); 
        }
        else
        {
            securityState.setState( JSecState.State.UNSECURED );
            report( "logError", "***** Un-encrypted call established *****" );
            setTitle( appTitle + "; Established PLAIN call with " + verboseRemote ); 
        }

        this.lastMessageFromPBX = null;
    }

    //////////////////////////////////////////////////////////////////////////////////////
    
    /**
     *  Dumps log area into html file
     */
    public void dumpLogArea( String fileName )
    {
        if ( fileName == null || fileName.isEmpty () ) {
            Calendar cal = Calendar.getInstance ();
            SimpleDateFormat sdf = new SimpleDateFormat( "yyyy-MM-dd-HHmmssSSS" );
            fileName = defaultLogAreaDumpFilename + sdf.format( cal.getTime () ) + ".html";
        }

        try 
        {
            BufferedWriter out = new BufferedWriter( new FileWriter( fileName ) );
            
            synchronized( logArea )
            {
                out.write( logArea.getText () );
            }

            out.close ();
            
            report( "logInfo", "Dumped log area into '" + fileName + "'" );
        }
        catch( IOException e )
        {
            report( "logError", "Failed to dump log area: " + e.getMessage () );
        }
    }

    /**
     *  Reads contents of the text file using URL class.
     *  
     *  @return string buffer containing file contents; null in case of error
     */
    public StringBuffer getContentsFromResourceOrFile( String path )
    {
        StringBuffer sb = new StringBuffer ();

        try 
        {
            /* First, try to read resource from JAR
             */
            URL url = getClass().getResource( path );
            
            /* Then fail to local file 
             */
            if ( url == null ) {
                url = new URL( "file:" + path );
            }
            
            /* Append contents of the file to string buffer
             */
            if ( url != null ) 
            {
                InputStream in = url.openStream ();
                BufferedReader dis = new BufferedReader( new InputStreamReader( in ) );

                String line;
                while ( ( line = dis.readLine () ) != null )
                {
                    sb.append( line + "\n" );
                }

                in.close ();
            }
        }
        catch( MalformedURLException e ) 
        {
            Log.exception( Log.TRACE, e );
            sb = null;
        }
        catch( IOException e )
        {
            Log.exception( Log.TRACE, e );
            sb = null;
        }

        return sb;
    }

    /**
     *  Clears log area (with the contents of the "empty" template)
     */
    public void clearLogArea ()
    {
        logArea.setContentType( "text/html" );
        StringBuffer sb = getContentsFromResourceOrFile( "resources/empty.html" );
        if ( sb != null ) {
            logArea.setText( sb.toString () );
        } else {
            logArea.setText( "<html><head></head><body></body></html>" );
        }
    }

    /**
     *  Displays usage information
     */
    public void displayUsage ()
    {
        StringBuffer sb = getContentsFromResourceOrFile( "resources/usage.html" );
        
        if ( sb != null && sb.length () > 0 ) {
            logMessage( sb.toString () );
        }
    }

    /**
     *  Displays help
     */
    public void displayHelp ()
    {
        StringBuffer sb = getContentsFromResourceOrFile( "resources/help.html" );
        
        if ( sb != null && sb.length () > 0 ) {
            logMessage( sb.toString () );
        }
    }

    /**
     *  Main entry point. 
     *  Creates GUI instance of the CryptoPhoneApp application.
     *  
     *  @param args the command line arguments
     */
    public static void main( final String args[] ) 
    {
        // System.getProperties().list( System.out );
        
        java.awt.EventQueue.invokeLater( 
                new Runnable() {
                    public void run() {
                        new CryptoPhoneApp( args ).setVisible( true );
                    }
                }
            );
    }
}

/*! \mainpage The Kryptofon - Secure VoIP Phone

  \section s_intro Introduction
  
  Welcome to Kryptofon, a Java based application for secured voice and short message 
  communication between internet users.  Kryptofon was written as a part of final 
  project (\ref p_task) of the 
  <a href="http://dsv.su.se/utbildning/distans/ip1" target="_blank"><b>SU/IP1 
  course</b></a>.
  
  \section s_desc Documentation
  
   - <a href="../description/Kryptofon_UsersGuide.pdf" target="_top">
       Kryptofon User's Guide (PDF)</a>
   - <a href="../description/Kryptofon_SysInternals.pdf" target="_top">
       Kryptofon System Internals (PDF)</a>
   - <a href="../docs/index.html" target="_top">
       Javadoc</a> (instead of Doxygen)
 
  \section s_jar Executable
  
  The jar file of the package can be found <a href="../kryptofon.jar"><b>here</b></a>.
  
  \image html kryptofon.png
*/

/*!
  \page p_task IP1 - Gesällprov

  Gesällprovet är en uppgift för den egna kreativiteten och är ett fritt valt 
  (men obligatoriskt) arbete där du får chansen att visa vad du går för 
  konstruktionsmässigt (istället för minnesmässigt som på en tenta) i "lugn och ro". 

   - Ska baseras på de tekniker som kursen bygger på men får givetvis även i
     nnehålla tekniker från andra områden
   - Ska vara nätverksbaserat
   - Ska vara bra både till form och funktion
   - Ska inte vara en av de tidigare uppgifterna på kursen men gärna 
     en utökning av en eller flera av dessa 
*/
