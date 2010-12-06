
package crypto;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.security.NoSuchAlgorithmException;
import java.security.PublicKey;
import java.security.Signature;
import java.security.SignedObject;
import java.util.ArrayList;

import javax.crypto.Cipher;
import javax.crypto.NoSuchPaddingException;

import utils.Log;
import utils.Base64;

/**
 *  Implements public part of the asymmetric cipher (with public key) used to send 
 *  encrypted local secret key (used for symmetric ciphering of peer-to-peer 
 *  datagram packets) to remote peer.
 *  
 *  The class holds also list of authorized public keys, which is used to verify
 *  signed objects received from peers.
 *  
 *  @author Mikica B Kocic
 */
public class PublicEncryptor
{
    /**
     *  Padding to be used when ciphering/deciphering
     *  JCE does not support RSA/CBC so the CBC mode is built 
     *  on the top of ECB in PublicEncryptor.encrypt().
     */
    private final static String padding = "/ECB/PKCS1PADDING";

    /**
     *  Message digest used for creating/validating signatures
     */
    private final static String digest  = "SHA1";

    /**
     *  The name of the file holding authorized public keys of remote peers
     */
    private final static String authorizedKeysFile = "mykf-authorized-keys.txt";

    /**
     *  Authorized public keys (loaded from file)
     */
    private static ArrayList<NamedPublicKey> authorizedKeys = null;

    /**
     *  Public key
     */
    private PublicKey publicKey = null;
    
    /**
     *  Instance of the encrypting engine based on remote public key
     */
    private Cipher cipher = null;
    
    /**
     *  Remote public key: serialized and encoded as Base64 string.
     */
    private String serializedPublicKey = null;

    /**
     *  Contains name of the verificator (i.e the name associated with authorized public
     *  key that has verified this public key). Not null indicates that the public key 
     *  was successfully verified.
     */
    private String verificator = null;

    /**
     *  Deserializes public key from the Base64 string and instantiates PublicEncryptor.
     *  Verifies public key with the public key retrieved from the authorized keys.
     */
    public PublicEncryptor( String serializedPublicKey, String remoteUserId )
    {
        /* Deserialize and verify public key used for encryption.
         */
        this.serializedPublicKey = serializedPublicKey;

        try
        {
            Object object = Base64.decodeToObject( this.serializedPublicKey );
            SignedObject signedObject = null;

            if ( object instanceof SignedObject ) 
            {
                signedObject = (SignedObject) object;
                this.verificator = PublicEncryptor.verifyObject( signedObject );
                object = signedObject.getObject ();
            }
            
            if ( object instanceof PublicKey ) 
            {
                this.publicKey = (PublicKey)object;
                String algorithm = this.publicKey.getAlgorithm (); 
                this.cipher = Cipher.getInstance( algorithm + padding );
            }
        }
        catch( ClassNotFoundException e )
        {
            Log.exception( Log.WARN, e );
        }
        catch( NoSuchAlgorithmException e )
        {
            Log.exception( Log.WARN, e );
        }
        catch( NoSuchPaddingException e )
        {
            Log.exception( Log.WARN, e );
        }
        catch( IOException e )
        {
            Log.exception( Log.WARN, e );
        }

        /* If failed to create serializedPublicKey, then fail for good.
         */
        if ( this.publicKey == null )
        {
            this.cipher = null;
        }
    }

    /**
     *  Create empty authorized keys file if it does not exist
     *  and adjust permissions.
     */
    private static void createEmptyAuthorizedPublicKeys( String filename )
    {
        try
        {
            File file = new File( filename );

            if (  ! file.exists () ) {
                file.createNewFile ();
            }

            if (  file.exists () )
            {
                /* Change permissions using native OS 'chmod' command (ignoring Windows), 
                 * so that no one but the owner might read its contents.
                 */
                String osName = System.getProperty( "os.name" ).toLowerCase();
                if ( ! osName.matches( "^.*windows.*$" ) ) 
                {
                    try 
                    {
                        Runtime.getRuntime().exec( new String[] { "chmod", "go=", filename } );
                    }
                    catch( IOException e ) 
                    {
                        Log.trace( "Failed to do chmod; OS = " + osName );
                        Log.exception( Log.TRACE, e );
                    }
                }
            }
        }
        catch( Exception e )
        {
            Log.exception( Log.ERROR, e );
        }
    }

    /**
     *  Loads authorized keys
     */
    public static void loadAuthorizedPublicKeys ()
    {
        StringBuffer report = new StringBuffer ();

        ArrayList<NamedPublicKey> newAuthKeys = new ArrayList<NamedPublicKey> ();

        try 
        {
            String filePath = CipherEngine.getPrivateKeyDirectory ()
                            + authorizedKeysFile;
            
            createEmptyAuthorizedPublicKeys( filePath );

            FileReader inf = new FileReader( filePath );
            BufferedReader ins = new BufferedReader( inf );

            String line;
            while ( ( line = ins.readLine () ) != null ) 
            {
                /* Split line into 'words'; Our key should be the first word 
                 */
                String[] parts = line.trim().split( "\\s{1,}" );
                
                /* Skip empty lines
                 */
                if ( parts.length < 1 || parts[0].length () <= 0 ) {
                    continue;
                }

                /* Skip lines starting with '#' (comments) 
                 */
                if ( parts[0].equals( "#" ) ) {
                    continue;
                }

                /* Now, deserialize public key from the first word
                 */
                String encodedKey = parts[0];
                Object object = null;

                try {
                    object = Base64.decodeToObject( encodedKey.toString () );
                }
                catch( IOException e )
                {
                    Log.warn( "Failed to deserialize authorized key at line: [" + encodedKey + "]" );
                    Log.exception( Log.WARN, e );
                }
                catch( ClassNotFoundException e )
                {
                    Log.warn( "Failed to deserialize authorized key at line: [" + encodedKey + "]" );
                    Log.exception( Log.WARN, e );
                }

                if ( object != null && ( object instanceof NamedPublicKey ) ) 
                {
                    NamedPublicKey authKey = (NamedPublicKey) object; 
                    newAuthKeys.add( authKey );
                    if ( report.length () != 0 ) {
                        report.append( ", " );
                    }
                    report.append( authKey.comment );
                }
                else if ( object != null )
                {
                    Log.warn( "Line: [" + encodedKey + "]" );
                    Log.warn( "Ignored class: " + object.getClass().toString () );
                }
            }
        }
        catch( FileNotFoundException e )
        {
            Log.exception( Log.TRACE, e );
        }
        catch( IOException e )
        {
            Log.exception( Log.WARN, e );
        }

        if ( newAuthKeys.size() > 1 ) 
        {
            report.insert( 0,  "Loaded " + newAuthKeys.size() + " authorized keys: " );
            Log.attn( report.toString () );
        }
        else if ( newAuthKeys.size() == 1 ) 
        {
            report.insert( 0,  "Loaded authorized key: " );
            Log.attn( report.toString () );
        }

        authorizedKeys = newAuthKeys;
    }
    
    /**
     *  Verifies signed object with a public key from the authorized public keys
     *  
     *  @return not null if verified with the name associated to authorized public key
     */
    public static String verifyObject( SignedObject object )
    {
        if ( authorizedKeys == null ) {
            return null;
        }

        String verificator = null;

        for ( NamedPublicKey authKey : authorizedKeys )
        {
            try 
            {
                String signAlgorithm = digest + "with" + authKey.publicKey.getAlgorithm ();
                
                Signature signature = Signature.getInstance( signAlgorithm );
                
                if ( object.verify( authKey.publicKey, signature ) ) {
                    verificator = authKey.comment;
                    break;
                }
            }
            catch( Exception e )
            {
                /* ignore all errors; search until exhausted keys or verify succeeds */
            }
        }
        
        return verificator;
    }

    /**
     *  Returns if cipher is properly initialized
     */
    public boolean isActive ()
    {
        return this.cipher != null;
    }

    /**
     *  Returns if public key was verified
     */
    public boolean isVerified ()
    {
        return this.verificator != null;
    }

    /**
     *  Returns if name of the verificator from authorized keys that verified this public key
     *  
     *  @return name of the verificator; May be null indicating not verified public key
     */
    public String getVerificatorName ()
    {
        return this.verificator;
    }

    /**
     *  Encrypts plain text using public key.
     *  Emulates CBC (cipher-block chaining) using plain ECB.
     *  Why? -- Because JCE does not support RSA/CBC cipher (only RSA/ECB).
     *
     *  See <a href="http://en.wikipedia.org/wiki/Block_cipher_modes_of_operation#Cipher-block_chaining_.28CBC.29" target="_new">
     *  Cipher-block chaining (CBC)</a>
     *  \image html cbc_encryption.png
     *  
     *  @see AsymmetricCipher#decrypt
     */
    public byte[] encrypt( byte[] plainText )
    {
        if ( this.cipher == null ) {
            return null;
        }

        byte[] output = null;
        
        synchronized( this.cipher ) 
        {
            try 
            {
                this.cipher.init( Cipher.ENCRYPT_MODE, publicKey );
                
                /* cipher.getBlockSize () returns always 0, so for the RSA
                 * we can calculate block size as output size - 11 octets overhead.
                 * e.g. 117 octets for 1024-bit RSA key size (= 1024/8 - 11 )
                 */
                int blockSize = cipher.getOutputSize( 1 ) - 11;
                
                ByteArrayOutputStream bOut = new ByteArrayOutputStream ();
                
                byte[] xorBlock = new byte[ blockSize ];

                for ( int pos = 0; pos < plainText.length; pos += blockSize ) 
                {
                    int len = Math.min( plainText.length - pos, blockSize );
                    
                    for ( int i = 0; i < len; ++i ) {
                        xorBlock[i] = (byte)( xorBlock[i] ^ plainText[ pos + i ] );
                    }

                    byte[] cipherBlock = this.cipher.doFinal( xorBlock, 0, len );
                    bOut.write( cipherBlock );

                    System.arraycopy( cipherBlock, 0, xorBlock, 0, blockSize );
                }

                output = bOut.toByteArray ();
            }
            catch( Exception e )
            {
                Log.exception( Log.ERROR, e );
            }
        }

        return output;
    }
    
    /**
     *  Returns Base64 of encrypted (using our public key) object.
     */
    public String encryptAndSerialize( Serializable object )
    {
        // TODO sign object first with our public key !

        String result = null;
        
        /* Serialize instance of the secret key into secret key file
         */
        ByteArrayOutputStream bOut = null; 
        ObjectOutputStream oOut = null;
        
        try
        {
            bOut = new ByteArrayOutputStream ();
            oOut = new ObjectOutputStream( bOut );
            oOut.writeObject( object );
            
            byte[] plainText = bOut.toByteArray ();
            
            oOut.close ();
            bOut.close ();
            
            result = Base64.encodeBytes( encrypt( plainText ), Base64.GZIP );
        }
        catch( Exception e ) 
        {
            Log.exception( Log.ERROR, e );
        }
        
        return result;
    }
}
