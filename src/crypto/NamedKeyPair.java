
package crypto;

import java.security.PrivateKey;
import java.security.PublicKey;
import java.io.Serializable;

/**
 *  Encapsulates a public/private key pair together with some comment
 *  (textual description) that describes them like type, owner, time-stamp etc.
 */
public class NamedKeyPair implements Serializable
{
    /**
     *  Implements java.io.Serializable interface
     */
    private static final long serialVersionUID = -7283024661187692775L;

    /**
     *  The public key
     */
    public PublicKey publicKey;

    /**
     *  The private key
     */
    public PrivateKey privateKey;
    
    /**
     *  The description of the public key, e.g. owner, timestamp etc.
     */
    public String comment;
    
    /**
     *  Constructs object 
     */
    public NamedKeyPair( PublicKey publicKey, PrivateKey privateKey, String comment ) 
    {
        this.publicKey = publicKey;
        this.privateKey = privateKey;
        this.comment = comment;
    }
}
