
/**
 *  Package with components extending Swing GUI 
 */
package ui;

import java.awt.Dimension;

import javax.swing.ImageIcon;
import javax.swing.JLabel;

/**
 *  Image indicating security state (unsecured/secured-unverified/secured-trusted). 
 *
 *  @author Mikica B Kocic
 */
public class JSecState extends JLabel 
{
    private static final long serialVersionUID = 1853852857708971435L;

    /**
     *  Possible security states
     */
    public enum State
    {
        /** Not secured (un-encrypted) */
        UNSECURED,
        
        /** Untrusted secured (remote party is not verified) */
        VERIFIED,
        
        /** Trusted secured (remote party is verified) */
        UNVERIFIED
    };
    
    /**
     *  Current security state
     */
    private State state;
    
    /*  Security state icons
     */
    private ImageIcon iconUnsecured;
    private ImageIcon iconVerified;
    private ImageIcon iconUnverified;
    
    /**
     *  Constructs object in UNSECURED state by default
     */
    public JSecState( Object resourceOwner )
    {
        iconUnsecured  = JImageButton.loadIcon( resourceOwner, "unsecured.png" );
        iconVerified   = JImageButton.loadIcon( resourceOwner, "verified.png" );
        iconUnverified = JImageButton.loadIcon( resourceOwner, "unverified.png" );
        
        setState( State.UNSECURED );
        setMinimumSize( new Dimension( 32, 32 ) );
        setMaximumSize( new Dimension( 32, 32 ) );
    }
    
    /**
     *  Gets current security state
     */
    public State getState ()
    {
        return this.state;
    }

    /**
     *  Stes new security state
     */
    public void setState( State newState )
    {
        this.state = newState;
        switch( this.state )
        {
        case UNSECURED:
            setIcon( iconUnsecured );
            setToolTipText( 
                    "<html><head></head><body><p><span style='color:red'>"
                    + "Unsecured and untrusted communication.</span>"
                    + "<br/>Instant messages will be unciphered and broadcasted to public."
                    + "</p></body></html>"
                    );
            break;
        case UNVERIFIED:
            setIcon( iconUnverified );
            setToolTipText( 
                    "<html><head></head><body><p><span style='color:#8000FF'>"
                    + "Secured (encrypted) communication with unverified peer.</span>"
                    + "<br/>Instant messages will be ciphered and sent to peer only."
                    + "</p></body></html>"
                    );
            break;
        case VERIFIED:
            setIcon( iconVerified );
            setToolTipText( 
                    "<html><head></head><body><p><span style='color:green'>"
                    + "Secured communication with the trusted peer.</span>"
                    + "<br/>Instant messages will be ciphered and sent to peer only."
                    + "</p></body></html>"
                    );
            break;
        }
    }
}
