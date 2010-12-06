
package ui;

import java.awt.Color;
import java.awt.event.FocusEvent;
import java.awt.event.FocusListener;
import java.net.URL;

import javax.swing.ImageIcon;
import javax.swing.JButton;

/**
 *  JButton with transparent images and no borders.
 *
 *  @author Mikica B Kocic
 */
public class JImageButton extends JButton implements FocusListener
{
    private static final long serialVersionUID = 2446005873909591693L;

    private ImageIcon normalIcon;
    private ImageIcon inFocusIcon;
    
    /**
     *  Creates button with the tool tip and two icons (one normal and one rollover)
     */
    public JImageButton( Object resourceOwner, 
            String tooltip, String iconPath, String rolloverIconPath )
    {
        setToolTipText( tooltip );

        /* Set icons
         */
        normalIcon = loadIcon( resourceOwner, iconPath );
        inFocusIcon = loadIcon( resourceOwner, rolloverIconPath );
        
        setIcon( normalIcon );
        setRolloverIcon( inFocusIcon );
        
        /* Make button background transparent and without borders
         */
        setBorderPainted( false );
        setOpaque( false );
        setBackground( new Color(0,0,0,0) );
        
        addFocusListener( this );
    }

    /**
     *  Loads icon from resources or file system
     */
    public static ImageIcon loadIcon( Object resourceOwner, String name )
    {
        String path = "resources/images/" + name;
        
        /* First try from class resources
         */
        URL url = resourceOwner.getClass().getResource( path );
        
        if ( url != null ) {
            return new ImageIcon( url );
        }
        
        /* then fall back to file system...
         */
        return new ImageIcon( path );
    }

    
    /**
     *  On focus gained, sets icon to 'in focus' icon
     */
    @Override
    public void focusGained( FocusEvent evt ) 
    {
        setIcon( inFocusIcon );
        setBackground( null );
    }

    /**
     *  On focus lost, resets icon to normal
     */
    @Override
    public void focusLost( FocusEvent evt ) 
    {
        setIcon( normalIcon );
        setBackground( new Color(0,0,0,0) );
    }
}
