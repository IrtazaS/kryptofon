
package crypto;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.Serializable;
import java.security.InvalidKeyException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Security;
import java.security.Signature;
import java.security.SignatureException;
import java.security.SignedObject;
import java.text.SimpleDateFormat;
import java.util.Calendar;

import javax.crypto.Cipher;
import javax.crypto.NoSuchPaddingException;
import javax.crypto.SecretKey;

import utils.Log;
import utils.Base64;

/**
 *  Implements asymmetric cipher with public and private keys used to retrieve
 *  secret key (used for symmetric ciphering of peer-to-peer datagram packets)
 *  from remote peer. Remote peer sends its secret key encrypted with our public key.
 *  
 *  Transmission of the SecretKey can be schematically shown:
 *  <pre>
 *  Send secret key:
 *  secretKey >> serialize >> encrypt (with PubKey) >> encode to Base64 >> transmit
 *  
 *  Reconstruct secret key:
 *  receive >> decode from Base64 >> decrypt (with PrivKey) >> deserialize >> secretKey
 *  </pre> 
 *  
 *  @author Mikica B Kocic
 */
public class AsymmetricCipher
{
    /**
     *  Asymmetric cipher algorithm
     */
    private final static String algorithm = "RSA";
    
    /**
     *  Default key size for algorithm
     */
    private final static int keySize = 1024;

    /**
     *  Padding to be used when ciphering/deciphering.
     *  JCE does not support RSA/CBC so the CBC mode is built 
     *  on the top of ECB in AsymmetricCipher.decrypt().
     */
    private final static String padding = "/ECB/PKCS1PADDING";

    /**
     *  Message digest used for creating/validating signatures
     */
    private final static String digest  = "SHA1";
    
    /**
     *  The name of the file holding saved private key
     */
    private final static String privateKeyFile = "mykf-private-key.txt";
    
    /**
     *  The name of the file holding saved public key
     */
    private final static String publicKeyFile = "mykf-public-key.txt";

    /**
     *  Private key used for deciphering and signing messages
     */
    private PrivateKey privateKey = null;
    
    /**
     *  Public key corresponding to our private key
     */
    private PublicKey publicKey = null;
    
    /**
     *  The comment (description) of the key pair
     */
    private String keyPairComment = null;

    /**
     *  Instance of the decrypting engine based on our private key
     */
    private Cipher cipher = null;
    
    /**
     *  Our public key: serialized and encoded as Base64 string.
     */
    private String serializedPublicKey = null;

    /**
     *  Generates a pair of keys and serializes public key as Base64 string.
     */
    public AsymmetricCipher ()
    {
        boolean loadedKeys = loadSavedKeyPair ();
        boolean sanity = false;
        
        while( true )
        {
            if ( ! loadedKeys  ) {
                generateKeyPair ();
            }
    
            instantiateCipher ();
            
            serializePublicKey ();
            
            if ( ! isActive () ) {
                destruct (); // make sure everyting is clean
                if ( loadedKeys ) {
                    Log.warn( "Load key pair inactive; Generating new key pair..." );
                    continue;
                } else {
                    Log.error( "AsymmetricCipher: Generated key pair inactive." );
                    return;
                }
            }
    
            sanity = sanityCheck ();
            if ( sanity ) {
                break; // everything is ok
            }
            
            if ( loadedKeys )  {
                Log.warn( "Sanity check failed on loaded key pair: Retrying..." );
                loadedKeys = false;
            } else {
                Log.error( "AsymmetricCipher: Sanity check failed on generated key pair." );
                destruct ();
                return;
            }
        }

        /* Save the key pair (if the key pair is generated i.e. not loaded)
         */
        if ( isActive () && sanity && ! loadedKeys ) {
            saveKeyPair ();
            exportPublicKey( null );
        }
    }
    
    /**
     *  Destructs object (makes it inactive)
     */
    private void destruct ()
    {
        this.privateKey = null;
        this.publicKey = null;
        this.cipher = null;
        this.serializedPublicKey = null;
    }
    
    /**
     *  Load saved key pair
     */
    private boolean loadSavedKeyPair ()
    {
        this.privateKey = null;
        this.publicKey = null;
        
        String keyFilePath = CipherEngine.getPrivateKeyDirectory() 
                           + AsymmetricCipher.privateKeyFile;

        Object oPair = loadObject( keyFilePath );
        
        if ( oPair != null && ( oPair instanceof NamedKeyPair ) ) 
        {
            NamedKeyPair keyPair = (NamedKeyPair) oPair;
            this.privateKey = keyPair.privateKey;
            this.publicKey = keyPair.publicKey;
            this.keyPairComment = keyPair.comment;
            
            Log.attn( "Loaded private key '" + this.keyPairComment 
                    + "' from file '" + AsymmetricCipher.privateKeyFile + "'" );
        }

        return ( this.privateKey != null && this.publicKey != null ); 
    }

    /**
     *  Saves private/public key pair with description (this.keyPairComment)
     */
    private void saveKeyPair ()
    {
        if ( ! isActive () ) {
            return;
        }

        String keyFilePath = CipherEngine.getPrivateKeyDirectory() 
                           + AsymmetricCipher.privateKeyFile;
        
        if ( saveObject( 
                new NamedKeyPair( this.publicKey, this.privateKey, this.keyPairComment ),
                keyFilePath, null ) )
        {
            Log.attn( "Private key saved as '" + keyFilePath + "'" );
            
            /* Change file permissions using native OS 'chmod' command (ignoring Windows), 
             * so that no one but the owner might read its contents.
             */
            String osName = System.getProperty( "os.name" ).toLowerCase();
            if ( ! osName.matches( "^.*windows.*$" ) ) 
            {
                try 
                {
                    Runtime.getRuntime().exec( new String[] { "chmod", "400", keyFilePath } );
                }
                catch( IOException e ) 
                {
                    Log.trace( "Failed to do chmod; OS = " + osName );
                    Log.exception( Log.TRACE, e );
                }
            }
        }
    }

    /**
     *  Generates a key pair
     */
    private void generateKeyPair ()
    {
        this.privateKey = null;
        this.publicKey = null;
        this.keyPairComment = null;
        
        try
        {
            KeyPairGenerator keyGen = KeyPairGenerator.getInstance( algorithm );
            keyGen.initialize( keySize );
            
            KeyPair keyPair = keyGen.generateKeyPair ();
            this.privateKey = keyPair.getPrivate ();
            this.publicKey  = keyPair.getPublic ();
            
            /* Default comment is name of the cipher plus time-stamp 
             */
            Calendar cal = Calendar.getInstance ();
            SimpleDateFormat sdf = new SimpleDateFormat( "yyyy-MM-dd-HHmmssSSS" );
            this.keyPairComment = algorithm.toLowerCase () + "-key-" 
                                + sdf.format( cal.getTime() ); 

            Log.attn( "Generated a new " + algorithm + "/" 
                    + keySize + " key pair: '" + this.keyPairComment + "'" );
        }
        catch( NoSuchAlgorithmException e )
        {
            StringBuffer algos = new StringBuffer( "Available algorithms:" );
            
            for( String s : Security.getAlgorithms( "Cipher" ) ) {
                algos.append( " " ).append( s );
            }
            
            Log.exception( Log.ERROR, e );
            Log.warn( algos.toString () );
        }
    }

    /**
     *  Instantiates a cipher
     */
    private void instantiateCipher ()
    {
        this.cipher = null;

        if ( this.privateKey == null || this.publicKey == null ) {
            return; // must have key pair
        }

        try
        {
            this.cipher = Cipher.getInstance( algorithm + padding );

            Log.trace( "Instantiated asymmetric cipher: " + this.cipher.getAlgorithm () );
        }
        catch( NoSuchAlgorithmException e )
        {
            StringBuffer algos = new StringBuffer( "Available algorithms:" );
            
            for( String s : Security.getAlgorithms( "Cipher" ) ) {
                algos.append( " " ).append( s );
            }
            
            Log.exception( Log.ERROR, e );
            Log.warn( algos.toString () );
        }
        catch( NoSuchPaddingException e )
        {
            Log.exception( Log.ERROR, e );
        }
    }

    /**
     *  Serializes the public key, signs it and encodes in Base64 format
     */
    private void serializePublicKey ()
    {
        if ( this.privateKey == null || this.publicKey == null || this.cipher == null ) {
            return; // must have key pair and cipher
        }

        try
        {
            SignedObject signedPublicKey = this.signObject( this.publicKey );
  
            this.serializedPublicKey = Base64.encodeObject( signedPublicKey, Base64.GZIP );

            Log.trace( "Serialized Public Key in Base64; length = " 
                    + this.serializedPublicKey.length () );
        }
        catch( IOException e )
        {
            Log.exception( Log.ERROR, e );
        }
    }

    /**
     *  Returns if cipher is properly initialized
     */
    public boolean isActive ()
    {
        return this.cipher != null && this.serializedPublicKey != null;
    }
    
    /**
     *  Returns serialized public key used for encryption of datagrams as 
     *  Base64 string.
     */
    public String getSerializedAndSignedPublicKey ()
    {
        return this.serializedPublicKey;
    }

    /**
     *  Save public key into file
     *  
     *  @param fileName file where to save public key; if null, it will be
     *                  the default: AssymmetricCipher.publicKeyFile 
     */
    public void exportPublicKey( String fileName )
    {
        if ( ! isActive () ) {
            return;
        }

        if ( fileName == null ) {
            fileName = CipherEngine.getPrivateKeyDirectory()
                     + AsymmetricCipher.publicKeyFile;
        }

        if ( saveObject( 
                new NamedPublicKey( this.publicKey, this.keyPairComment ), 
                fileName,
                "  " + this.keyPairComment + "\n" ) )
        {
            Log.attn( "Public key exported to '" + fileName + "'" );
        }
    }

    /**
     *  Returns serializable named publicKey (with comment) encoded as Base64
     */
    public String getNamedPublicKey ()
    {
        StringBuffer sb = new StringBuffer ();

        try {
            String encodedKey = Base64.encodeObject( new NamedPublicKey( 
                    this.publicKey, this.keyPairComment ), Base64.GZIP );
            
            sb.append( encodedKey );
            sb.append( " " );
            sb.append( this.keyPairComment );
        }
        catch( IOException e )
        {
            Log.exception( Log.ERROR, e );
        }

        return sb.toString ();
    }
    
    /**
     *  Saves serializable object encoded in Base64 to file
     */
    public static boolean saveObject( Serializable object, String fileName, String comment )
    {
        boolean result = false;

        try {
            String text = Base64.encodeObject( object, Base64.GZIP );
            
            BufferedWriter out = new BufferedWriter( new FileWriter( fileName ) );
            
            out.write( text );
            
            if ( comment != null ) {
                out.write( comment );
            }

            out.flush ();
            out.close ();
            
            Log.trace( "Saved " + object.getClass().toString () + " into " + fileName );
            
            result = true;
        }
        catch( IOException e )
        {
            Log.exception( Log.ERROR, e );
        }

        return result;
    }
    
    /**
     *  Loads serializable object encoded in Base64 from file
     */
    public static Object loadObject( String fileName )
    {
        Object object = null;

        try {
            StringBuffer sb = new StringBuffer ();
            
            BufferedReader in = new BufferedReader( new FileReader( fileName ) );
            String line;
            while( ( line = in.readLine () ) != null ) {
                sb.append( line );
            }
            in.close ();

            object = Base64.decodeToObject( sb.toString () );
        }
        catch( FileNotFoundException e )
        {
            Log.exception( Log.TRACE, e );
        }
        catch( IOException e )
        {
            Log.exception( Log.WARN, e );
        }
        catch( ClassNotFoundException e )
        {
            Log.exception( Log.ERROR, e );
        }

        return object;
    }

    /**
     *  Sanity check whether PublicEncryptor works with PrivateEncryption
     */
    private boolean sanityCheck ()
    {
        /* Random data 
         */
        byte[] plainText = new byte[ 2048 ];
        for ( int i = 0; i < plainText.length; ++i ) {
            plainText[i] = (byte)( Math.random() * 256 );
        }
        
        /* Simulate encryption at remote end and local decryption
         */
        PublicEncryptor testPublic = new PublicEncryptor( this.serializedPublicKey, null );
        byte[] cipherText = testPublic.encrypt( plainText );
        byte[] output =  this.decrypt( cipherText );
        
        if ( ! java.util.Arrays.equals( plainText, output ) )
        {
            Log.error( "Public encryption / private decryption sanity check failed." );

            Log.trace( Log.toHex( plainText ) );
            Log.trace( Log.toHex( cipherText ) );
            Log.trace( Log.toHex( output ) );
            
            return false;
        }
        
        return true;
    }
    
    /**
     *  Decrypts cipher text using the private key. 
     *  Emulates CBC (cipher-block chaining) using plain ECB.
     *  Why? -- Because JCE does not support RSA/CBC cipher (only RSA/ECB).
     *  
     *  See <a href="http://en.wikipedia.org/wiki/Block_cipher_modes_of_operation#Cipher-block_chaining_.28CBC.29" target="_new">
     *  Cipher-block chaining (CBC)</a>
     *  \image html cbc_decryption.png
     *  
     *  @see PublicEncryptor#encrypt
     */
    public byte[] decrypt( byte[] cipherText )
    {
        if ( this.cipher == null )  {
            return null;
        }
        
        byte[] output = null;
        
        synchronized( this.cipher )
        {
            try 
            {
                this.cipher.init( Cipher.DECRYPT_MODE, privateKey );

                int blockSize = cipher.getOutputSize( 1 );
                
                byte[] xorBlock = new byte[ blockSize ];
                
                ByteArrayOutputStream bOut = new ByteArrayOutputStream ();

                for ( int pos = 0; pos < cipherText.length; pos += blockSize ) 
                {
                    int len = Math.min( cipherText.length - pos, blockSize );
                    
                    byte[] plainBlock = this.cipher.doFinal( cipherText, pos, len );
                    
                    for ( int i = 0; i < plainBlock.length; ++i ) {
                        plainBlock[i] = (byte)( plainBlock[i] ^ xorBlock[i] );
                    }
                    
                    bOut.write( plainBlock );

                    System.arraycopy( cipherText, pos, xorBlock, 0, blockSize );
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
     *  Signs object using private key
     */
    public SignedObject signObject( Serializable object )
    {
        String signatureAlgorithm = digest + "with" + this.privateKey.getAlgorithm ();
        
        Signature signature = null;
        SignedObject result = null;

        try 
        {
            signature = Signature.getInstance( signatureAlgorithm );
            signature.initSign( this.privateKey );
            result = new SignedObject( object, this.privateKey, signature ); 
        }
        catch( NoSuchAlgorithmException e )
        {
            Log.exception( Log.ERROR, e );
        }
        catch( InvalidKeyException e )
        {
            Log.exception( Log.ERROR, e );
        }
        catch( SignatureException e )
        {
            Log.exception( Log.ERROR, e );
        }
        catch( IOException e )
        {
            Log.exception( Log.ERROR, e );
        }
        
        return result;
    }
    
    /**
     *  Reconstructs secret key from Base64 respresentation of encrypted 
     *  (using our public key) serialized secret key.
     */
    public SymmetricCipher deserializeEncryptedSecretKey( String serializedSecretKey )
    {
        SymmetricCipher result = null;
        
        ByteArrayInputStream bIn = null; 
        ObjectInputStream oIn = null;
        
        try
        {
            byte[] cipherText = Base64.decode( serializedSecretKey );
            byte[] plainText = decrypt( cipherText );
            
            bIn = new ByteArrayInputStream( plainText );
            
            oIn = new ObjectInputStream( bIn );
            
            Object object = oIn.readObject ();
            
            oIn.close ();
            bIn.close ();

            String verificator = null;
            SignedObject signedObject = null;

            if ( object instanceof SignedObject ) 
            {
                signedObject = (SignedObject) object;
                verificator = PublicEncryptor.verifyObject( signedObject );
                object = signedObject.getObject ();
            }
            
            if ( object instanceof SecretKey ) 
            {
                result = new SymmetricCipher( (SecretKey)object, verificator );
            }
            else 
            {
                Log.error( "Invalid object when trying to deserialize encrypted secret key" );
            }
        }
        catch( Exception e ) 
        {
            Log.exception( Log.ERROR, e );
        }
        
        return result;
    }
}
