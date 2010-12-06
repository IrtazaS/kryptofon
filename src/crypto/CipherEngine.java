
/**
 *  Application-wide cryptographic functions 
 */
package crypto;

import java.io.File;
import java.io.IOException;
import java.security.SignedObject;

import utils.Log;

/**
 *  Common ciphering engine (for the whole application) providing:
 *  
 *   - Asymmetric ciphering: used for signing/verification and 
 *     encryption/decryption of secret key used in symmetric ciphering.
 *     
 *   - Symmetric ciphering: used for encryption/decryption of PDUs and 
 *     secret chat text messages.
 */
public class CipherEngine 
{
    /**
     *  Asymmetric cipher used to encrypt secret keys
     */
    private static AsymmetricCipher privateCipher = null;

    /** 
     *  The default cipher algorithm for data (PDUs and secret chat messages)
     */
    private final static String myCipherAlgorithm = "Blowfish";
    
    /** 
     *  The default key size for cipher algorithm 
     */
    private final static int myCipherKeySize = 32; // Start velue for reduced CPU usage

    /**
     *  Subdirectory of the user.home where private key is stored
     */
    private static final String defaultPrivateKeyDirectory = ".mykf";

    /**
     *  Full path of the directory holding our private key
     */
    private static String myPrivateKeyPath = "";

    /**
     *  Symmetric cipher used to encrypt data (PDUs and secret chat messages)
     */
    private static SymmetricCipher myPduCipher = null;
    
    /**
     *  Returns path to the directory holding our private key
     */
    public static String getPrivateKeyDirectory ()
    {
        return myPrivateKeyPath;
    }

    /**
     *  Returns serialized signed public key (used for encryption of datagrams) as 
     *  Base64 string.
     */
    public static String getSignedPublicKey ()
    {
        if ( privateCipher == null ) {
            return null;
        }
        
        return privateCipher.getSerializedAndSignedPublicKey ();
    }

    /**
     *  Returns serialized named public key encoded as Base64 string.
     */
    public static String getNamedPublicKey ()
    {
        if ( privateCipher == null ) {
            return null;
        }
        
        return privateCipher.getNamedPublicKey ();
    }

    /**
     *  Returns local symmetric ciphering engine
     */
    public static SymmetricCipher getCipher ()
    {
        return myPduCipher;
    }

    /**
     *  Returns secret key signed with our private key
     */
    public static SignedObject getSignedSecretKey ()
    {
        if ( privateCipher == null ) {
            return null;
        }
        
        return privateCipher.signObject( myPduCipher.getSecretKey () );
    }
    
    /**
     *  Reconstructs secret key from Base64 respresentation of encrypted 
     *  (using our public key) serialized secret key and verifies signature
     *  of the remote peer.
     */
    public static SymmetricCipher deserializeEncryptedSecretKey( String encryptedSecret )
    {
        if ( privateCipher == null ) {
            return null;
        }
        
        return privateCipher.deserializeEncryptedSecretKey( encryptedSecret );
    }
    
    /**
     *  Loads authorized public keys and initializes asymmetric and symmetric 
     *  ciphering engines, where:
     *  
     *   - Asymmetric ciphering is used for verification and encryption/decryption of
     *     secret key used in symmetric ciphering.
     *   - Symmetric ciphering is used for encryption/decryption of PDUs and 
     *     secret chat text messages.
     *  
     *  Initialization is non-blocking and performed in separate worker thread.
     */
    public static void initialize ()
    {
        /* Create directory that will hold private key. Also change permissions using 
         * native OS 'chmod' command (ignoring Windows), so that no one but the 
         * owner might read its contents.
         */
        try
        {
            myPrivateKeyPath = "";
            
            String dirPath = System.getProperty( "user.home" )
                           + System.getProperty( "file.separator" )
                           + defaultPrivateKeyDirectory;

            File directory = new File( dirPath );

            if (  ! directory.exists () ) {
                directory.mkdir ();
            }

            if (  directory.exists () )
            {
                myPrivateKeyPath = dirPath + System.getProperty( "file.separator" );
                
                String osName = System.getProperty( "os.name" ).toLowerCase();
                if ( ! osName.matches( "^.*windows.*$" ) ) 
                {
                    try 
                    {
                        Runtime.getRuntime().exec( new String[] { "chmod", "go=", dirPath } );
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
            
        Log.trace( "Private Key directory: " + myPrivateKeyPath );

        /* Initialize (load or generate) private/public keys and
         * generate secret key in worker thread, then load authorized public keys. 
         */
        Runnable nonBlockingInitThread = new Runnable () 
        {
            @Override
            public void run() 
            {
                /* Instantiate our ciphers first...
                 */
                if ( myPduCipher == null ) {
                    myPduCipher = new SymmetricCipher( 
                            myCipherAlgorithm, myCipherKeySize, /*report*/ false );
                }

                if ( privateCipher == null ) {
                    privateCipher = new AsymmetricCipher ();
                }
                
                /* ...then load authorized public keys
                 */
                PublicEncryptor.loadAuthorizedPublicKeys ();
            }
        };

        ( new Thread( nonBlockingInitThread, "CipherEngine" ) ).start ();
    }
    
    /**
     *  Reloads only authorized public keys
     */
    public static void reloadAuthorizedPublicKeys ()
    {
        PublicEncryptor.loadAuthorizedPublicKeys ();
    }
    
    /**
     *  Generates new symmetric secret key.
     *  
     *  @return true if generated cipher may be used (i.e. false in case of error)
     */
    public static boolean generateNewSecret( String algorithm, int keySize, boolean verbose )
    {
        if ( algorithm == null || algorithm.isEmpty () ) {
            algorithm = myCipherAlgorithm;
        }

        if ( keySize <= 0 ) {
            keySize = myCipherKeySize;
        }

        myPduCipher = new SymmetricCipher( algorithm, keySize, verbose );
        
        return myPduCipher.isActive ();
    }
}
