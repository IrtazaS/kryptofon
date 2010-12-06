
package crypto;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.security.InvalidParameterException;
import java.security.NoSuchAlgorithmException;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.NoSuchPaddingException;
import javax.crypto.SecretKey;
import javax.crypto.spec.IvParameterSpec;

import utils.Base64;
import utils.Log;

/**
 *  Instances of the Symmetric cipher class arew used to cipher peer-to-peer datagram 
 *  packets. Cipher's secret key is exchanged using asymmetric cipher.
 *  
 *  @author Mikica B Kocic
 */
public class SymmetricCipher
{
    /**
     *  Cipher-Block Chaining mode.
     *  \see http://en.wikipedia.org/wiki/Block_cipher_modes_of_operation
     */
    private final static String mode = "/CBC";
    
    /**
     *  PKCS5 padding.
     *  \see http://www.ietf.org/rfc/rfc2898.txt
     */
    private final static String padding = "/PKCS5Padding";

    /**
     *  Instance of the cipher used to encrypt/decrypt data.
     */
    private Cipher cipher = null;
    
    /**
     *  Secret key used to encrypt/decrypt data.
     */
    private SecretKey secretKey = null;

    /**
     *  Contains name of the verificator (i.e the name associated with authorized public
     *  key that has verified this public key). Not null indicates that the public key 
     *  was successfully verified.
     */
    private String verificator = null;

    /**
     *  Wraps existing secret key with information about verificatory (if any).
     */
    public SymmetricCipher( SecretKey secretKey, String verificator )
    {
        this.secretKey = secretKey;
        this.verificator = verificator;
        
        try
        {
            this.cipher = Cipher.getInstance( secretKey.getAlgorithm () + mode + padding );
            
            Log.trace( "New remote symmetric cipher: " + this.cipher.getAlgorithm () );
        }
        catch( NoSuchAlgorithmException e )
        {
            Log.exception( Log.ERROR, e );
        }
        catch( NoSuchPaddingException e )
        {
            Log.exception( Log.ERROR, e );
        }
        
        if ( this.cipher == null ) {
            this.secretKey = null;
        }
    }

    /**
     *  Generates a new secret key using specified algorithm and key size.
     */
    public SymmetricCipher( String algorithm, int keySize, boolean attnReport )
    {
        this.secretKey = null;
        
        try
        {
            KeyGenerator keyGen = KeyGenerator.getInstance( algorithm );
            keyGen.init( keySize );
            this.secretKey = keyGen.generateKey ();
            
            this.cipher = Cipher.getInstance( secretKey.getAlgorithm () + mode + padding );
            
            Log.trace( "New local symmetric cipher: " + this.cipher.getAlgorithm () );
            
            if ( attnReport ) {
                Log.attn( "New symmetric cipher: " + algorithm + "/" + keySize );
            }
        }
        catch( InvalidParameterException e )
        {
            Log.exception( Log.ERROR, e );
            if ( attnReport ) {
                Log.attn( "Error: Invalid parameter: " + e.getMessage () );
            }
        }
        catch( NoSuchAlgorithmException e )
        {
            Log.exception( Log.ERROR, e );
            if ( attnReport ) {
                Log.attn( "Error: No such algorithm: " + e.getMessage () );
            }
        }
        catch( NoSuchPaddingException e )
        {
            Log.exception( Log.ERROR, e );
            if ( attnReport ) {
                Log.attn( "Error: No such padding: " + e.getMessage () );
            }
        }

        if ( this.cipher == null ) {
            this.secretKey = null;
        }
    }

    /**
     *  Returns secret key.
     */
    public SecretKey getSecretKey ()
    {
        return secretKey;
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
     *  Returns description of the cipher algorithm
     */
    public String getAlgorithmDesc ()
    {
        if ( secretKey == null ) {
            return "[Inactive]";
        }
        
        return this.secretKey.getAlgorithm ();
    }

    /**
     *  Encrypts random preamble of the given length appended with
     *  the input plain text.
     */
    public byte[] encrypt( int randomPreambleLen, byte[] plainText )
    {
        if ( this.cipher == null ) {
            return null;
        }
        
        /* Generate random preamble */
        byte[] preamble = new byte[ randomPreambleLen ];
        for ( int i = 0; i < randomPreambleLen; ++i ) {
            preamble[i] = (byte)( Math.random () * 0x100 - 0x100 );
        }

        /* IV specification for the CBC */
        byte[] ivBytes = new byte[8];
        IvParameterSpec ivSpec = new IvParameterSpec( ivBytes );

        byte[] cipherText = null;

        synchronized( this.cipher )
        {
            try
            {
                this.cipher.init( Cipher.ENCRYPT_MODE, this.secretKey, ivSpec );
                
                int ptLength = ivBytes.length + preamble.length + plainText.length;
                cipherText = new byte[ cipher.getOutputSize( ptLength ) ];

                int ctLength = cipher.update( ivBytes, 0, ivBytes.length, cipherText, 0 );

                ctLength += cipher.update( preamble, 0, preamble.length, cipherText, ctLength );
                
                ctLength += cipher.update( plainText, 0, plainText.length, cipherText, ctLength );

                ctLength += cipher.doFinal( cipherText, ctLength );
            }
            catch( Exception e )
            {
                Log.exception( Log.PDU, e );
            }
        }
        
        return cipherText;
    }

    /**
     *  Decrypts cipher text first then discards random preamble of the given length.
     */
    public byte[] decrypt( int randomPreambleLen, byte[] cipherText )
    {
        if ( this.cipher == null ) {
            return null;
        }

        byte[] plainText = null;
        
        synchronized( this.cipher )
        {
            try
            {
                byte[] ivBytes = new byte[8];
                IvParameterSpec ivSpec = new IvParameterSpec( ivBytes );

                this.cipher.init( Cipher.DECRYPT_MODE, this.secretKey, ivSpec );
                
                byte[] buf = new byte[ cipher.getOutputSize( cipherText.length ) ];

                int bufLen = cipher.update( cipherText, 0, cipherText.length, buf, 0 );

                bufLen += cipher.doFinal( buf, bufLen );

                /* Remove the IV and random preamble from the start of the message
                 */
                plainText = new byte[ bufLen - ivBytes.length - randomPreambleLen ];

                System.arraycopy( buf, ivBytes.length + randomPreambleLen, plainText, 0, plainText.length );
            }
            catch( Exception e )
            {
                Log.exception( Log.PDU, e );
            }
        }
        
        return plainText;
    }
    
    /**
     *  Encrypts text message with random preamble and returns Base64 encoded
     *  cipher text.
     */
    public String encrypt( String plainText )
    {
        String encodedCipherText = null;

        /* Send encrypted message to peer 
         */
        try 
        {
            byte[] plainBin = ( "[BEGIN]" + plainText ).getBytes( "UTF8" );

            byte[] cipherText = this.encrypt( /*randomPreambleLen*/ 256, plainBin );
            
            if ( cipherText != null ) 
            {
                encodedCipherText = Base64.encodeBytes( cipherText );
            }
        }
        catch( UnsupportedEncodingException e )
        {
            Log.exception( Log.TRACE, e );
        }
        
        return encodedCipherText;
    }
    
    /**
     *  Decodes Base64 encoded cipher text, decrypts text message and discards
     *  random preamble.
     */
    public String decrypt( String encodedCipherText )
    {
        String clearText = null;
        
        try 
        {
            byte[] cipherText = Base64.decode( encodedCipherText );
            byte[] data = this.decrypt( /*randomPreambleLen*/ 256, cipherText );
            if ( data != null ) 
            {
                String msg = new String( data, "UTF8" );
                if ( msg.startsWith( "[BEGIN]" ) ) 
                {
                    clearText = msg.substring( 7 ); // skip prefix
                }
            }
        }
        catch( UnsupportedEncodingException e ) 
        {
            Log.exception( Log.TRACE, e );
        }
        catch( IOException e )
        {
            Log.exception( Log.TRACE, e );
        }
        
        return clearText;
    }
}