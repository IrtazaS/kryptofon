
package crypto;

import java.security.PublicKey;
import java.io.Serializable;

/**
 *  Encapsulates a public key together with some comment
 *  (textual description) that describes it (like type, owner, time-stamp etc.)
 */
public class NamedPublicKey implements Serializable
{
    /**
     *  Implements java.io.Serializable interface
     */
    private static final long serialVersionUID = -1173558142358298160L;
    
    /**
     *  Encapsulated public key 
     */
    public PublicKey publicKey;

    /**
     *  The description of the public key, e.g. owner, timestamp etc.
     */
    public String comment;
    
    /**
     *  Constructs object 
     */
    public NamedPublicKey( PublicKey publicKey, String comment ) 
    {
        this.publicKey = publicKey;
        this.comment = comment;
    }
}
